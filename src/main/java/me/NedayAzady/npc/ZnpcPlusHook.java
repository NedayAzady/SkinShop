package me.NedayAzady.npc;

import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import lol.pyr.znpcsplus.api.NpcApi;
import lol.pyr.znpcsplus.api.NpcApiProvider;
import lol.pyr.znpcsplus.api.entity.EntityProperty;
import lol.pyr.znpcsplus.api.entity.EntityPropertyRegistry;
import lol.pyr.znpcsplus.api.entity.PropertyHolder;
import lol.pyr.znpcsplus.api.npc.NpcEntry;
import lol.pyr.znpcsplus.api.npc.NpcRegistry;
import lol.pyr.znpcsplus.api.npc.NpcType;
import lol.pyr.znpcsplus.api.npc.NpcTypeRegistry;
import lol.pyr.znpcsplus.api.skin.SkinDescriptor;
import lol.pyr.znpcsplus.api.skin.SkinDescriptorFactory;
import lol.pyr.znpcsplus.util.LookType;
import lol.pyr.znpcsplus.util.NpcLocation;
import me.NedayAzady.SkinShop;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.skin.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Handles direct integration with the ZNPCsPlus plugin (by Pyrbu) API.
 *
 * ZNPCsPlus is a packet-based NPC library: its fake entities only exist on the
 * client and are NOT real Bukkit entities. BedWars1058 opens the shop GUI when a
 * player right-clicks the vanilla villager it spawned (ShopOpenListener /
 * UpgradeOpenListener match on the clicked entity's block location), so every
 * skin apply here:
 *   1. hides the default BedWars vanilla villager at the block with an
 *      invisibility effect (it stays alive as the real click target for BedWars),
 *   2. deletes any previous addon NPC and creates a fresh skinned fake-player NPC
 *      (ZNPCsPlus only reads the skin property while spawning the NPC to a viewer,
 *      so a fresh entry guarantees the skin is actually rendered).
 *
 * This lets the addon work on servers that have ZNPCsPlus but not Citizens.
 */
public class ZnpcPlusHook {

    private final SkinShop plugin;
    private final ConfigManager configManager;
    private final Logger logger;
    private final boolean debug;

    // Track dynamic ZNPCsPlus NPC ids spawned by this addon per arena name
    private final Map<String, Set<String>> arenaNpcIds = new ConcurrentHashMap<>();
    // Track invisible ArmorStand click-markers per arena name (defensive cleanup
    // for any markers created by earlier builds of this addon)
    private final Map<String, Set<String>> arenaMarkerKeys = new ConcurrentHashMap<>();
    // key (same as NPC id) -> marker entity UUID
    private final Map<String, UUID> arenaMarkers = new ConcurrentHashMap<>();

    public ZnpcPlusHook(SkinShop plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigurationManager();
        this.logger = plugin.getLogger();
        this.debug = configManager.isDebug();
    }

    /**
     * Checks if the ZNPCsPlus plugin is installed and ready.
     */
    public boolean isZnpcPlusEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ZNPCsPlus");
    }

    /**
     * Updates the Shopkeeper (and Upgrades) NPC skin for the given team.
     */
    public void updateTeamSkins(IArena arena, ITeam team, SkinData skinData) {
        String arenaName = arena != null ? arena.getArenaName() : "unknown";
        String teamName = team != null ? team.getName() : "unknown";

        if (configManager.isApplyToShop()) {
            Location shopLoc = team != null ? team.getShop() : null;
            if (shopLoc != null && shopLoc.getWorld() != null) {
                applySkinAtLocation(arenaName, teamName, shopLoc, skinData, "shop");
            }
        }

        if (configManager.isApplyToUpgrades()) {
            Location upgradeLoc = team != null ? team.getTeamUpgrades() : null;
            if (upgradeLoc != null && upgradeLoc.getWorld() != null) {
                applySkinAtLocation(arenaName, teamName, upgradeLoc, skinData, "upgrade");
            }
        }
    }

    /**
     * Applies a skin at the target location: hides the BedWars villager (kept as
     * the click target) and (re)creates a fresh fake-player NPC carrying the skin.
     */
    private void applySkinAtLocation(String arenaName, String teamName, Location targetLoc, SkinData skinData, String shopType) {
        if (targetLoc == null || targetLoc.getWorld() == null) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> applySkinAtLocation(arenaName, teamName, targetLoc, skinData, shopType));
            return;
        }

        String id = buildNpcId(arenaName, teamName, shopType);
        try {
            // 1. Hide BedWars' default vanilla villager so it is not visible under
            //    the fake player, but keep it alive as the real shop click target.
            hideVillagersNear(targetLoc);

            // 2. Recreate the fake-player NPC from scratch. ZNPCsPlus only reads the
            //    skin property while spawning the NPC to a viewer, so a fresh entry
            //    guarantees the skin is applied (no stale Steve/Alex viewers).
            NpcApi api = NpcApiProvider.get();
            NpcRegistry registry = api.getNpcRegistry();
            NpcEntry existing = registry.getById(id);
            if (existing != null) {
                registry.delete(id);
            }

            NpcTypeRegistry typeRegistry = api.getNpcTypeRegistry();
            NpcType type = typeRegistry.getByName("player");
            if (type == null) {
                logger.warning("[SkinShop] ZNPCsPlus has no 'player' NPC type registered.");
                return;
            }

            NpcEntry entry = registry.create(id, targetLoc.getWorld(), type, new NpcLocation(targetLoc));
            // Dynamic addon NPC: never persist to ZNPCsPlus storage, never list in /npc.
            entry.setSave(false);
            entry.setAllowCommandModification(false);
            trackNpc(arenaName, id);

            // 3. Apply skin + look properties BEFORE processing (spawning) the NPC.
            applyProperties(entry, skinData);

            // 4. Start rendering the fake NPC to players.
            if (!entry.isProcessed()) {
                entry.setProcessed(true);
            }

            if (debug) {
                logger.info("[ZnpcPlusHook] Applied skin '" + (skinData != null ? skinData.getName() : "null")
                        + "' to ZNPCsPlus NPC '" + id + "' (" + shopType + ") in arena " + arenaName);
            }
        } catch (Throwable t) {
            logger.warning("[SkinShop] Error applying skin via ZNPCsPlus for '" + shopType + "' in arena "
                    + arenaName + " (" + id + "): " + t.getMessage());
            if (debug) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Sets the static skin descriptor and look-at-players properties on the NPC.
     */
    private void applyProperties(NpcEntry entry, SkinData skinData) {
        try {
            NpcApi api = NpcApiProvider.get();
            EntityPropertyRegistry propertyRegistry = api.getPropertyRegistry();
            PropertyHolder holder = entry.getNpc();

            if (skinData != null && skinData.hasValue()) {
                SkinDescriptorFactory skinFactory = api.getSkinDescriptorFactory();
                SkinDescriptor descriptor = skinFactory.createStaticDescriptor(skinData.getValue(), skinData.getSignature());
                EntityProperty<SkinDescriptor> skinProperty = propertyRegistry.getByName("skin", SkinDescriptor.class);
                if (skinProperty != null) {
                    holder.setProperty(skinProperty, descriptor);
                } else {
                    logger.warning("[SkinShop] ZNPCsPlus has no 'skin' property registered.");
                }
            }

            EntityProperty<LookType> lookProperty = propertyRegistry.getByName("look", LookType.class);
            if (lookProperty != null) {
                holder.setProperty(lookProperty, configManager.isNpcLookEnabled() ? LookType.PER_PLAYER : LookType.FIXED);
            }

            EntityProperty<Double> lookDistanceProperty = propertyRegistry.getByName("look_distance", Double.class);
            if (lookDistanceProperty != null) {
                holder.setProperty(lookDistanceProperty, Math.max(1.0, configManager.getNpcLookRange()));
            }
        } catch (Throwable t) {
            logger.warning("[SkinShop] Error setting ZNPCsPlus NPC properties: " + t.getMessage());
            if (debug) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Hides BedWars' default vanilla villagers near a location with an
     * invisibility effect. The villagers stay alive and are used by BedWars as the
     * real click target for the shop/upgrade GUI. Never throws.
     */
    private void hideVillagersNear(Location loc) {
        try {
            if (loc.getWorld() == null) return;
            PotionEffect invisible = new PotionEffect(PotionEffectType.INVISIBILITY, 1000000, 0, false, false);
            // Note: the effect survives the whole game (addPotionEffect keeps the raw
            // duration), so the villager stays hidden; re-applying also covers
            // villagers BedWars (re)spawns after an arena restart.
            hideNearbyVillagers(loc, 1.5, invisible);
        } catch (Throwable t) {
            if (debug) {
                logger.warning("[SkinShop] Error hiding vanilla villagers at " + loc + ": " + t.getMessage());
            }
        }
    }

    private void hideNearbyVillagers(Location loc, double radius, PotionEffect effect) {
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity.getType() == EntityType.VILLAGER && entity instanceof LivingEntity) {
                ((LivingEntity) entity).addPotionEffect(effect);
            }
        }
    }

    /**
     * Cleans up all dynamically created ZNPCsPlus NPCs and markers for an arena by arena name.
     */
    public void cleanupArena(String arenaName) {
        if (arenaName == null) return;
        removeMarkers(arenaMarkerKeys.remove(arenaName));
        deleteNpcs(arenaNpcIds.remove(arenaName));
    }

    /**
     * Cleans up all dynamically created ZNPCsPlus NPCs and markers for an arena.
     */
    public void cleanupArena(IArena arena) {
        if (arena != null) {
            cleanupArena(arena.getArenaName());
        }
    }

    /**
     * Cleans up all dynamically created ZNPCsPlus NPCs and markers (plugin disable).
     */
    public void cleanupAll() {
        if (Bukkit.isPrimaryThread()) {
            removeMarkers(allMarkerKeys());
            deleteNpcs(allTrackedIds());
        } else {
            Bukkit.getScheduler().runTask(plugin, this::cleanupAll);
            return;
        }
        arenaMarkers.clear();
        arenaMarkerKeys.clear();
        arenaNpcIds.clear();
    }

    private void removeMarkers(Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        for (String key : keys) {
            UUID uuid = arenaMarkers.remove(key);
            if (uuid == null) continue;
            Entity marker = Bukkit.getEntity(uuid);
            if (marker != null) {
                marker.remove();
            }
        }
    }

    private Set<String> allMarkerKeys() {
        Set<String> all = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (Set<String> keys : arenaMarkerKeys.values()) {
            all.addAll(keys);
        }
        return all;
    }

    private void deleteNpcs(Set<String> npcIds) {
        if (npcIds == null || npcIds.isEmpty() || !isZnpcPlusEnabled()) return;
        try {
            NpcRegistry registry = NpcApiProvider.get().getNpcRegistry();
            for (String id : npcIds) {
                registry.delete(id);
            }
        } catch (Throwable t) {
            logger.warning("[SkinShop] Error cleaning up ZNPCsPlus NPCs: " + t.getMessage());
            if (debug) {
                t.printStackTrace();
            }
        }
    }

    private Set<String> allTrackedIds() {
        Set<String> all = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (Set<String> ids : arenaNpcIds.values()) {
            all.addAll(ids);
        }
        return all;
    }

    private void trackNpc(String arenaName, String npcId) {
        arenaNpcIds.computeIfAbsent(arenaName, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(npcId);
    }

    private String buildNpcId(String arenaName, String teamName, String shopType) {
        String raw = (arenaName + "_" + teamName + "_" + shopType).toLowerCase();
        return "skinshop_" + raw.replaceAll("[^a-z0-9_]+", "_");
    }
}