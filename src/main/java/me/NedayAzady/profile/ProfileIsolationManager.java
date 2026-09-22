package me.NedayAzady.profile;

import com.andrei1058.bedwars.api.arena.IArena;
import me.NedayAzady.SkinShop;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.skin.SkinData;
import me.NedayAzady.skin.SkinManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the BedWars "Team Skin Rendering Fix".
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Keeps a strictly per-player isolated {@link PlayerProfileStore}.</li>
 *   <li>Injects a {@link ProfilePacketInterceptor} into every connected
 *       player's Netty pipeline so {@code PacketPlayOutPlayerInfo},
 *       {@code PacketPlayOutNamedEntitySpawn} and scoreboard team packets
 *       never carry a teammate's (or a stripped/default) skin property.</li>
 *   <li>Refreshes each player's correct GameProfile skin properties
 *       immediately upon spawning into the BedWars arena world.</li>
 *   <li>Tracks which players are currently inside an active match so the
 *       interceptor only runs while a match is happening.</li>
 * </ul>
 */
public class ProfileIsolationManager implements Listener {

    public static final String HANDLER_NAME = "skinshop_profile_fix";

    private final SkinShop plugin;
    private final ConfigManager configManager;
    private final SkinManager skinManager;
    private final PlayerProfileStore store = new PlayerProfileStore();

    // Players currently in an active BedWars match
    private final Set<UUID> trackedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Arena name -> players in that arena (so we can un-mark on arena end)
    private final Map<String, Set<UUID>> arenaPlayers = new ConcurrentHashMap<>();

    private volatile boolean enabled = false;
    private volatile boolean featureWarningLogged = false;

    public ProfileIsolationManager(SkinShop plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigurationManager();
        this.skinManager = plugin.getSkinManager();
    }

    // ---------------------------------------------------------------- lifecycle

    public void enable() {
        if (enabled) {
            reload();
            return;
        }
        enabled = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (configManager.isIsolateProfiles()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                inject(player);
                capture(player);
            }
        }
        plugin.getLogger().info("[SkinShop] [RenderFix] BedWars team skin rendering fix armed.");
    }

    public void reload() {
        if (!configManager.isEnableSkinRenderFix()) {
            disable();
            return;
        }
        if (!enabled) {
            enable();
            return;
        }
        // Re-capture fresh profiles for tracked players (e.g. /skin changed mid-match)
        if (configManager.isIsolateProfiles()) {
            for (UUID uuid : trackedPlayers) {
                ensureSnapshot(uuid);
            }
        }
    }

    public void disable() {
        if (!enabled) {
            return;
        }
        enabled = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            eject(player);
        }
        store.clear();
        trackedPlayers.clear();
        arenaPlayers.clear();
        plugin.getLogger().info("[SkinShop] [RenderFix] BedWars team skin rendering fix disarmed.");
    }

    public void shutdown() {
        disable();
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        inject(player);
        capture(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        eject(player);
        store.remove(player.getUniqueId());
        trackedPlayers.remove(player.getUniqueId());
        for (Set<UUID> members : arenaPlayers.values()) {
            members.remove(player.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- arena integration

    /**
     * Called when an arena transitions to "playing". Captures every member's
     * isolated profile and pushes a proactive, fully-corrected profile refresh
     * to everyone so no client can render a wrong/duplicate skin at match init.
     */
    public void onArenaStart(IArena arena) {
        if (!enabled || arena == null) {
            return;
        }
        String arenaName = arena.getArenaName();
        Set<UUID> members = arenaPlayers.computeIfAbsent(arenaName, k -> new HashSet<>());
        for (Player player : getArenaPlayers(arena)) {
            members.add(player.getUniqueId());
            trackedPlayers.add(player.getUniqueId());
            ensureSnapshot(player.getUniqueId());
        }
        if (configManager.isProactivelyPushSkins()) {
            // Run on main thread after BedWars finishes spawning bases.
            Bukkit.getScheduler().runTask(plugin, () -> refreshMatchPlayers(arena));
        }
    }

    /**
     * Called whenever a player spawns into the arena for the first time or
     * rejoins: refresh that player's skin to every other member, and refresh
     * every other member's skin to the joining player.
     */
    public void onArenaPlayerJoin(IArena arena, Player joined) {
        if (!enabled || arena == null || joined == null) {
            return;
        }
        UUID joinedId = joined.getUniqueId();
        trackedPlayers.add(joinedId);
        arenaPlayers.computeIfAbsent(arena.getArenaName(), k -> new HashSet<>()).add(joinedId);
        ensureSnapshot(joinedId);

        if (!configManager.isProactivelyPushSkins()) {
            return;
        }
        for (Player other : getArenaPlayers(arena)) {
            if (other.getUniqueId().equals(joinedId)) {
                continue;
            }
            ensureSnapshot(other.getUniqueId());
            scheduleProactivePush(other.getUniqueId(), joinedId);
            scheduleProactivePush(joinedId, other.getUniqueId());
        }
    }

    /**
     * Called when an arena restarts/ends: un-tracks its players.
     */
    public void onArenaEnd(String arenaName) {
        if (arenaName == null) {
            return;
        }
        Set<UUID> members = arenaPlayers.remove(arenaName);
        if (members != null) {
            for (UUID uuid : members) {
                trackedPlayers.remove(uuid);
            }
        }
    }

    // ---------------------------------------------------------------- profile capture

    /**
     * Captures (or refreshes) the isolated snapshot for a player UUID by
     * reading their own live CraftPlayer GameProfile.
     */
    public void ensureSnapshot(UUID uuid) {
        if (!enabled || !configManager.isIsolateProfiles() || uuid == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        capture(player);
    }

    public void capture(Player player) {
        if (player == null) {
            return;
        }
        SkinData skin = skinManager.readSkinFromPlayerProfile(player);
        if (skin == null) {
            return;
        }
        store.put(player.getUniqueId(), player.getName(), skin);
    }

    // ---------------------------------------------------------------- proactive corrected profile push

    /**
     * Builds a fresh {@code PacketPlayOutPlayerInfo}(ADD_PLAYER) containing a
     * strictly isolated GameProfile for the target and sends it to the viewer.
     * Best-effort: if NMS construction fails on an exotic version, the
     * in-flight interceptor is the safety net, so we silently skip.
     */
    public void pushCorrectedProfile(Player viewer, PlayerProfileStore.Snapshot target) {
        if (viewer == null || target == null || !target.hasUsableSkin()) {
            return;
        }
        try {
            Object packet = buildAddPlayerInfoPacket(target, viewer);
            if (packet == null) {
                return;
            }
            if (sendPacket(viewer, packet) && configManager.isRenderFixDebug()) {
                plugin.getLogger().info("[SkinShop] [RenderFix] Pushed corrected isolated profile for '"
                        + target.getName() + "' to '" + viewer.getName() + "'.");
            }
        } catch (Throwable t) {
            if (configManager.isRenderFixDebug()) {
                plugin.getLogger().warning("[SkinShop] [RenderFix] Proactive profile push failed: " + NmsReflection.describe(t));
            }
        }
    }

    private Object buildAddPlayerInfoPacket(PlayerProfileStore.Snapshot target, Player viewer) {
        Object packet = NmsReflection.newInstance("PacketPlayOutPlayerInfo", new Object[0]);
        if (packet == null) {
            return null;
        }

        // 1) Set action to ADD_PLAYER.
        setAddPlayerAction(packet);

        // 2) Build the isolated GameProfile.
        Object profile = NmsReflection.buildIsolatedGameProfile(target.getUuid(),
                target.getName(), target.getTextureValue(), target.getTextureSignature());
        if (profile == null) {
            return null;
        }

        // 3) Build the PlayerInfoData. Gamemode/latency are cosmetic for a tab
        //    entry; we read the target's real gamemode if reachable, else 0.
        int gamemodeOrdinal = 0;
        org.bukkit.GameMode gm = viewer.getGameMode();
        if (gm != null) {
            gamemodeOrdinal = gm.ordinal();
        }
        Object entry = NmsReflection.newInstance("PacketPlayOutPlayerInfo$PlayerInfoData",
                new Object[]{profile, 0, gamemodeOrdinal, null});
        if (entry == null) {
            return null;
        }

        // 4) Replace the entries container with our corrected list.
        java.util.List<Object> entries = new ArrayList<>();
        entries.add(entry);
        java.lang.reflect.Field entriesField = NmsReflection.findField(packet, "List", "Set", "Collection");
        if (entriesField == null) {
            return null;
        }
        if (!NmsReflection.setField(packet, entriesField, entries)) {
            return null;
        }
        return packet;
    }

    private void setAddPlayerAction(Object packet) {
        try {
            for (java.lang.reflect.Field field : packet.getClass().getDeclaredFields()) {
                if (field.getType().isEnum()) {
                    for (Object constant : field.getType().getEnumConstants()) {
                        if ("ADD_PLAYER".equals(((Enum<?>) constant).name())) {
                            field.setAccessible(true);
                            field.set(packet, constant);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean sendPacket(Player player, Object packet) {
        try {
            Object handle = NmsReflection.call(player, "getHandle");
            if (handle == null) {
                return false;
            }
            Object connection = NmsReflection.readField(handle, "PlayerConnection", "ServerGamePacketListenerImpl", "ServerCommonPacketListenerImpl");
            if (connection == null) {
                return false;
            }
            // sendPacket (<=1.18) / send (1.19+) — dispatch to whichever exists.
            if (NmsReflection.invokeIfPresent(connection, "sendPacket", packet)) {
                return true;
            }
            return NmsReflection.invokeIfPresent(connection, "send", packet);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Netty pipeline injection

    /**
     * Injects the outbound interceptor into the player's channel, before the
     * vanilla packet_handler. Idempotent and never throws.
     */
    public void inject(Player player) {
        if (!enabled || player == null) {
            return;
        }
        try {
            Object channelObj = channelOf(player);
            if (!(channelObj instanceof io.netty.channel.Channel)) {
                return;
            }
            io.netty.channel.Channel channel = (io.netty.channel.Channel) channelObj;
            io.netty.channel.ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) == null) {
                try {
                    pipeline.addBefore("packet_handler", HANDLER_NAME,
                            new ProfilePacketInterceptor(this, player.getUniqueId()));
                } catch (Throwable t) {
                    pipeline.addLast(HANDLER_NAME,
                            new ProfilePacketInterceptor(this, player.getUniqueId()));
                }
                if (configManager.isRenderFixDebug()) {
                    plugin.getLogger().info("[SkinShop] [RenderFix] Injected interceptor for '" + player.getName() + "'.");
                }
            }
        } catch (Throwable t) {
            if (!featureWarningLogged) {
                featureWarningLogged = true;
                plugin.getLogger().warning("[SkinShop] [RenderFix] Could not install packet interceptor on this server version: "
                        + NmsReflection.describe(t) + ". Skin duplicate-fix degraded (interceptor offline).");
            }
            if (configManager.isRenderFixDebug()) {
                plugin.getLogger().warning("[SkinShop] [RenderFix] Inject failed: " + t);
            }
        }
    }

    public void eject(Player player) {
        if (player == null) {
            return;
        }
        try {
            Object channelObj = channelOf(player);
            if (channelObj instanceof io.netty.channel.Channel) {
                io.netty.channel.ChannelPipeline pipeline = ((io.netty.channel.Channel) channelObj).pipeline();
                if (pipeline.get(HANDLER_NAME) != null) {
                    pipeline.remove(HANDLER_NAME);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private Object channelOf(Player player) {
        Object handle = NmsReflection.call(player, "getHandle");
        if (handle == null) {
            return null;
        }
        // NetworkManager (<=1.18) / Connection (1.19+)
        Object networkManager = NmsReflection.readField(handle, "NetworkManager", "Connection");
        if (networkManager == null) {
            return null;
        }
        return NmsReflection.readField(networkManager, "Channel");
    }

    // ---------------------------------------------------------------- helpers

    public void scheduleProactivePush(UUID viewerUuid, UUID targetUuid) {
        if (!enabled || viewerUuid == null || targetUuid == null) {
            return;
        }
        Player viewer = Bukkit.getPlayer(viewerUuid);
        PlayerProfileStore.Snapshot target = store.get(targetUuid);
        if (viewer == null || target == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            pushCorrectedProfile(viewer, target);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> pushCorrectedProfile(viewer, target));
        }
    }

    private void refreshMatchPlayers(IArena arena) {
        java.util.List<Player> players = getArenaPlayers(arena);
        for (Player viewer : players) {
            for (Player target : players) {
                if (viewer == target) {
                    continue;
                }
                ensureSnapshot(target.getUniqueId());
                pushCorrectedProfile(viewer, store.get(target.getUniqueId()));
            }
        }
        if (configManager.isRenderFixDebug()) {
            plugin.getLogger().info("[SkinShop] [RenderFix] Proactive profile refresh sent for " + players.size()
                    + " player(s) in arena '" + arena.getArenaName() + "'.");
        }
    }

    private java.util.List<Player> getArenaPlayers(IArena arena) {
        java.util.List<Player> players = new ArrayList<>();
        if (arena == null) {
            return players;
        }
        if (arena.getPlayers() != null) {
            players.addAll(arena.getPlayers());
        }
        if (arena.getSpectators() != null) {
            players.addAll(arena.getSpectators());
        }
        return players;
    }

    public boolean isViewerTracked(UUID viewerUuid) {
        return viewerUuid != null && trackedPlayers.contains(viewerUuid);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDebug() {
        return configManager.isRenderFixDebug();
    }

    public boolean isFixPlayerInfo() {
        return configManager.isFixPlayerInfo() && enabled;
    }

    public boolean isFixScoreboardTeam() {
        return configManager.isFixScoreboardTeam() && enabled;
    }

    public boolean isFixSpawnWatch() {
        return configManager.isFixSpawnWatch() && enabled;
    }

    public boolean isProactivelyPushSkins() {
        return configManager.isProactivelyPushSkins() && enabled;
    }

    public PlayerProfileStore getStore() {
        return store;
    }

    public SkinShop getPlugin() {
        return plugin;
    }
}