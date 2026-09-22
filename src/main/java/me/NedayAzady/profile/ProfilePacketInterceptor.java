package me.NedayAzady.profile;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound Netty packet interceptor that guarantees every player's own
 * GameProfile skin properties (textures + signature) are sent strictly
 * isolated per player while a BedWars match is running.
 *
 * <p>It guards against the classic BedWars/team-tablist bug where a plugin
 * re-sends {@code PacketPlayOutPlayerInfo} (or team membership packets that
 * lead to profile-less player infos) built from a stripped {@code GameProfile}
 * (UUID-only, no textures). Left un-fixed such packets make clients cache a
 * teammate's or a default skin. This interceptor:
 *
 * <ol>
 *   <li>Detects outbound player-info / named-entity-spawn / scoreboard-team
 *       packets addressed to a player inside an active BedWars arena.</li>
 *   <li>Ensures every profile entry carries that player's OWN texture from the
 *       isolated {@link PlayerProfileStore} (never a teammate's).</li>
 *   <li>When a packet profile was rebuilt without textures, replaces it in
 *       place with the isolated one before the packet leaves the server.</li>
 * </ol>
 */
public class ProfilePacketInterceptor extends ChannelDuplexHandler {

    private final ProfileIsolationManager manager;
    private final UUID viewerUuid;

    // Matched exactly-once per player to avoid reflection churn on every packet
    private static final String PACKET_PLAYER_INFO = "PacketPlayOutPlayerInfo";
    private static final String PACKET_PLAYER_INFO_UPDATE = "ClientboundPlayerInfoUpdatePacket";
    private static final String PACKET_PLAYER_INFO_MODERN = "ClientboundPlayerInfoPacket";
    private static final String PACKET_NAMED_SPAWN = "PacketPlayOutNamedEntitySpawn";
    private static final String PACKET_ADD_PLAYER = "ClientboundAddPlayerPacket";
    private static final String PACKET_SCOREBOARD_TEAM = "PacketPlayOutScoreboardTeam";
    private static final String PACKET_SET_PLAYER_TEAM = "ClientboundSetPlayerTeamPacket";

    public ProfilePacketInterceptor(ProfileIsolationManager manager, UUID viewerUuid) {
        this.manager = manager;
        this.viewerUuid = viewerUuid;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            handleOutbound(msg);
        } catch (Throwable t) {
            if (manager.isDebug()) {
                manager.getPlugin().getLogger().warning("[SkinShop] [RenderFix] Outbound packet error: " + NmsReflection.describe(t));
            }
        }
        super.write(ctx, msg, promise);
    }

    private void handleOutbound(Object msg) {
        if (!manager.isEnabled() || msg == null) {
            return;
        }

        String simple = NmsReflection.simpleName(msg);

        if (NmsReflection.isSimpleName(msg, PACKET_PLAYER_INFO)
                || NmsReflection.isSimpleName(msg, PACKET_PLAYER_INFO_UPDATE)
                || NmsReflection.isSimpleName(msg, PACKET_PLAYER_INFO_MODERN)) {
            if (manager.isFixPlayerInfo()) {
                fixPlayerInfoPacket(msg);
            }
            return;
        }

        if (NmsReflection.isSimpleName(msg, PACKET_NAMED_SPAWN)
                || NmsReflection.isSimpleName(msg, PACKET_ADD_PLAYER)) {
            if (manager.isFixSpawnWatch()) {
                fixNamedEntitySpawn(msg);
            }
            return;
        }

        if (NmsReflection.isSimpleName(msg, PACKET_SCOREBOARD_TEAM)
                || NmsReflection.isSimpleName(msg, PACKET_SET_PLAYER_TEAM)) {
            if (manager.isFixScoreboardTeam()) {
                fixScoreboardTeam(msg);
            }
            return;
        }
    }

    /**
     * Dominant fix: iterates all player-info entries in the packet and, when a
     * profile was built without its textures, replaces it with the isolated
     * one for that exact UUID. Teams two teammates share nothing: only their
     * own UUID's store entry is ever applied.
     */
    private void fixPlayerInfoPacket(Object packet) {
        if (!manager.isViewerTracked(viewerUuid)) {
            return;
        }

        for (Object container : NmsReflection.readAllFields(packet, "Map", "List", "Set", "Collection")) {
            fixContainer(container);
        }
    }

    private void fixContainer(Object container) {
        if (container instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) container;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isSkinEntry(entry.getValue())) {
                    Object replacement = fixEntry(entry.getValue());
                    if (replacement != null && replacement != entry.getValue()) {
                        try {
                            Map<Object, Object> writable = (Map<Object, Object>) container;
                            writable.put(entry.getKey(), replacement);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return;
        }
        if (container instanceof List) {
            List<?> list = (List<?>) container;
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (isSkinEntry(element)) {
                    Object replacement = fixEntry(element);
                    if (replacement != null && replacement != element) {
                        try {
                            List<Object> writable = (List<Object>) container;
                            writable.set(i, replacement);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return;
        }
        if (container instanceof Collection) {
            Collection<?> collection = (Collection<?>) container;
            Object[] elements = collection.toArray();
            for (Object element : elements) {
                if (isSkinEntry(element)) {
                    Object replacement = fixEntry(element);
                    if (replacement != null && replacement != element) {
                        try {
                            Collection<Object> writable = (Collection<Object>) container;
                            writable.remove(element);
                            writable.add(replacement);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private boolean isSkinEntry(Object obj) {
        if (obj == null) {
            return false;
        }
        String name = obj.getClass().getName();
        return name.contains("PlayerInfoData")
                || (name.contains("PlayerInfoUpdatePacket") && name.contains("$"))
                || (name.contains("PlayerInfo") && name.contains("$Entry"));
    }

    /**
     * Ensures the entry carries the correct, isolated profile for its UUID.
     *
     * @return the (possibly new) entry, or the original if nothing needed to change
     */
    private Object fixEntry(Object entry) {
        Object profile = NmsReflection.readField(entry, "GameProfile");
        if (profile == null) {
            return entry;
        }

        UUID pid = NmsReflection.readProfileId(profile);
        if (pid == null) {
            return entry;
        }

        // Fresh, isolated snapshot already present? Otherwise capture on demand.
        if (!manager.getStore().has(pid)) {
            manager.ensureSnapshot(pid);
        }

        PlayerProfileStore.Snapshot snapshot = manager.getStore().get(pid);
        if (snapshot == null || !snapshot.hasUsableSkin()) {
            return entry;
        }

        // Already correct? Skip (idempotent, cheap for latency/display updates).
        String currentValue = NmsReflection.readTextureValue(profile);
        if (snapshot.getTextureValue().equals(currentValue)) {
            return entry;
        }

        Object isolated = NmsReflection.buildIsolatedGameProfile(pid, snapshot.getName(),
                snapshot.getTextureValue(), snapshot.getTextureSignature());
        if (isolated == null) {
            return entry;
        }

        // Try to write in place first (modern packet entries tolerate this).
        if (writeProfileField(entry, isolated)) {
            if (manager.isDebug()) {
                manager.getPlugin().getLogger().info("[SkinShop] [RenderFix] Refreshed isolated profile for '" + snapshot.getName() + "' in player-info entry.");
            }
            return entry;
        }

        // Fallback: rebuild the entry object replacing the stale profile.
        Object rebuilt = rebuildInfoEntry(entry, isolated, snapshot);
        if (rebuilt != null && rebuilt != entry) {
            if (manager.isDebug()) {
                manager.getPlugin().getLogger().info("[SkinShop] [RenderFix] Rebuilt player-info entry with isolated profile for '" + snapshot.getName() + "'.");
            }
            return rebuilt;
        }
        return entry;
    }

    private boolean writeProfileField(Object entry, Object isolatedProfile) {
        java.lang.reflect.Field field = NmsReflection.findField(entry, "GameProfile");
        return field != null && NmsReflection.setField(entry, field, isolatedProfile);
    }

    /**
     * Rebuilds a classic {@code PacketPlayOutPlayerInfo$PlayerInfoData} with a
     * corrected profile, copying latency / gamemode / display-name. Used when
     * the GameProfile field is final. Null on failure (best-effort version).
     */
    private Object rebuildInfoEntry(Object entry, Object isolatedProfile, PlayerProfileStore.Snapshot snapshot) {
        Class<?> cls = entry.getClass();

        // Prefer authoritative values from the live player (getPing/getGameMode
        // exist on 1.16.5+); fall back to the packet's own fields on older MC.
        int latency = resolveLatency(entry, snapshot);
        int gamemodeOrdinal = resolveGamemodeOrdinal(entry, snapshot);

        for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 4) {
                continue;
            }
            if (!params[0].getSimpleName().contains("GameProfile")) {
                continue;
            }
            Object[] args = new Object[4];
            args[0] = isolatedProfile;
            args[1] = latency;
            // param[2] = gamemode (enum sorted by ordinal or plain int)
            args[2] = gamemodeOrdinal;
            // param[3] = IChatBaseComponent (nullable)
            args[3] = readDisplayName(entry);

            Object rebuilt = NmsReflection.newInstance(ctor, args);
            if (rebuilt != null) {
                return rebuilt;
            }
        }
        return entry;
    }

    private int resolveLatency(Object entry, PlayerProfileStore.Snapshot snapshot) {
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(snapshot.getUuid());
        if (target != null) {
            try {
                return Math.max(0, target.getPing());
            } catch (Throwable ignored) {
            }
        }
        List<Object> ints = NmsReflection.readAllFields(entry, "int", "Integer");
        return ints.isEmpty() ? 0 : ((Number) ints.get(0)).intValue();
    }

    private int resolveGamemodeOrdinal(Object entry, PlayerProfileStore.Snapshot snapshot) {
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(snapshot.getUuid());
        if (target != null && target.getGameMode() != null) {
            return target.getGameMode().ordinal();
        }
        for (java.lang.reflect.Field field : enumFields(entry)) {
            try {
                Object value = field.get(entry);
                if (value instanceof Enum<?>) {
                    return ((Enum<?>) value).ordinal();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private List<java.lang.reflect.Field> enumFields(Object entry) {
        List<java.lang.reflect.Field> result = new ArrayList<>();
        Class<?> cls = entry.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field field : cls.getDeclaredFields()) {
                if (field.getType().isEnum()) {
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        return result;
    }

    private Object readDisplayName(Object entry) {
        return NmsReflection.readField(entry, "IChatBaseComponent", "Component");
    }

    /**
     * Named-entity spawn: makes sure the isolated snapshot exists before the
     * entity spawns (the client only renders the correct skin if its tab-list
     * entry already carries it). The player-info fixer above guarantees that
     * ADD_PLAYER packets are corrected, so after the spawn we force a final
     * corrective profile push for the spawned player to be extra safe.
     */
    private void fixNamedEntitySpawn(Object packet) {
        if (!manager.isViewerTracked(viewerUuid)) {
            return;
        }
        Object uuidField = NmsReflection.readField(packet, "UUID");
        if (!(uuidField instanceof UUID)) {
            return;
        }
        UUID target = (UUID) uuidField;
        PlayerProfileStore.Snapshot snapshot = manager.getStore().get(target);
        if (snapshot == null || !manager.isViewerTracked(target)) {
            manager.ensureSnapshot(target);
            return;
        }
        if (snapshot.hasUsableSkin() && manager.isProactivelyPushSkins()) {
            manager.scheduleProactivePush(viewerUuid, target);
        }
    }

    /**
     * Scoreboard team packet (team colors / glowing outline): the team packet
     * itself carries no skin, but a later profile-less player-info rebuild is
     * the common cause of skin overwrite. Re-capture the referenced players
     * and, if proactive refresh is enabled, push their corrected profiles back.
     */
    private void fixScoreboardTeam(Object packet) {
        if (!manager.isViewerTracked(viewerUuid)) {
            return;
        }
        List<Object> nameCollections = NmsReflection.readAllFields(packet, "List", "Set", "String[]");
        for (Object names : nameCollections) {
            if (names instanceof List) {
                for (Object name : (List<?>) names) {
                    if (name instanceof String) {
                        handleTeamMember((String) name);
                    }
                }
            } else if (names instanceof java.util.Set) {
                for (Object name : (java.util.Set<?>) names) {
                    if (name instanceof String) {
                        handleTeamMember((String) name);
                    }
                }
            } else if (names instanceof String[]) {
                for (String name : (String[]) names) {
                    handleTeamMember(name);
                }
            }
        }
    }

    private void handleTeamMember(String playerName) {
        PlayerProfileStore.Snapshot snapshot = manager.getStore().getByName(playerName);
        if (snapshot == null) {
            return;
        }
        manager.ensureSnapshot(snapshot.getUuid());
        if (snapshot.hasUsableSkin() && manager.isProactivelyPushSkins()) {
            manager.scheduleProactivePush(viewerUuid, snapshot.getUuid());
        }
    }
}