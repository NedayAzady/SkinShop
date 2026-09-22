package me.NedayAzady.npc;

import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import me.NedayAzady.SkinShop;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.skin.SkinData;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Shopkeeper and Upgrade NPCs for each BedWars team in an arena.
 *
 * BedWars1058 Architecture Note:
 * - BedWars1058 internally spawns a vanilla Villager at team.getShop() and team.getTeamUpgrades().
 * - BedWars1058's ShopOpenListener listens to PlayerInteractAtEntityEvent: whenever a player
 *   right-clicks ANY entity located at team.getShop(), it opens the BedWars shop GUI.
 * - To display real player skins, we replace/augment the shopkeeper with a Citizens Player NPC.
 */
public class NPCShopManager {

    private final SkinShop plugin;
    private final ConfigManager configManager;
    private final CitizensHook citizensHook;
    private final ZnpcPlusHook znpcPlusHook;

    // Track dynamic Citizens NPC IDs spawned by this addon per arena name
    private final Map<String, Set<Integer>> arenaDynamicNpcIds = new ConcurrentHashMap<>();

    // Keep track of warning logged state so console isn't flooded
    private boolean npcWarningLogged = false;

    public NPCShopManager(SkinShop plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigurationManager();
        this.citizensHook = new CitizensHook(plugin.getLogger(), configManager.isDebug());
        this.znpcPlusHook = new ZnpcPlusHook(plugin);
    }

    /**
     * Resolves the NPC provider: either Citizens or ZNPCsPlus.
     */
    private boolean isCitizensPreferred() {
        String provider = configManager.getNpcProvider();
        return citizensHook.isCitizensEnabled() && (provider.equals("auto") || provider.equals("citizens"));
    }

    private boolean isZnpcPlusPreferred() {
        String provider = configManager.getNpcProvider();
        return znpcPlusHook.isZnpcPlusEnabled() && (provider.equals("auto") || provider.equals("znpcsplus"));
    }

    /**
     * Updates the Shopkeeper (and Upgrades) NPC skin for the given team.
     *
     * @param arena    BedWars arena (IArena instance)
     * @param team     Target team (ITeam instance)
     * @param skinData Skin textures to apply
     */
    public void updateTeamSkins(IArena arena, ITeam team, SkinData skinData) {
        if (isCitizensPreferred()) {
            updateTeamSkinsWithCitizens(arena, team, skinData);
        } else if (isZnpcPlusPreferred()) {
            znpcPlusHook.updateTeamSkins(arena, team, skinData);
        } else {
            if (!npcWarningLogged) {
                plugin.getLogger().warning("[SkinShop] Neither Citizens nor ZNPCsPlus is installed/enabled!");
                plugin.getLogger().warning("[SkinShop] In Minecraft, custom player skins on shopkeeper NPCs require Citizens or ZNPCsPlus.");
                plugin.getLogger().warning("[SkinShop] BedWars default villagers will be used without crashing.");
                npcWarningLogged = true;
            }
        }
    }

    /**
     * Citizens provider implementation: applies skins to real Citizens Player NPCs.
     */
    private void updateTeamSkinsWithCitizens(IArena arena, ITeam team, SkinData skinData) {

        // Apply skin to Team Shopkeeper NPC
        if (configManager.isApplyToShop()) {
            Location shopLoc = team.getShop();
            if (shopLoc != null && shopLoc.getWorld() != null) {
                applySkinAtLocation(arena, shopLoc, skinData, "Shop");
            }
        }

        // Apply skin to Team Upgrades NPC
        if (configManager.isApplyToUpgrades()) {
            Location upgradeLoc = team.getTeamUpgrades();
            if (upgradeLoc != null && upgradeLoc.getWorld() != null) {
                applySkinAtLocation(arena, upgradeLoc, skinData, "Upgrade");
            }
        }
    }

    /**
     * Finds or creates a Citizens NPC at the target location and applies the skin data.
     */
    private void applySkinAtLocation(IArena arena, Location targetLoc, SkinData skinData, String shopType) {
        // Run on Bukkit main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            NPC npc = citizensHook.findNpcAt(targetLoc, 1.5);

            if (npc == null && configManager.isSpawnCitizensNpcIfMissing()) {
                // Remove default vanilla villager spawned by BedWars1058 at this spot
                removeVanillaVillagersNear(targetLoc, 1.5);

                // Create Citizens player NPC
                npc = citizensHook.createPlayerNpc(targetLoc, "");
                if (npc != null) {
                    trackDynamicNpc(arena.getArenaName(), npc.getId());
                    if (configManager.isDebug()) {
                        plugin.getLogger().info("[SkinShop] Spawned dynamic Citizens Player NPC (ID " + npc.getId()
                                + ") for " + shopType + " at " + arena.getArenaName());
                    }
                }
            }

            if (npc != null) {
                // Ensure no default BedWars vanilla villager overlaps with the Citizens NPC
                removeVanillaVillagersNear(targetLoc, 1.5);

                citizensHook.applySkinToNpc(npc, skinData);

                // Configure LookClose trait so the NPC turns its head to look at approaching players
                citizensHook.configureLookClose(npc,
                        configManager.isNpcLookEnabled(),
                        configManager.getNpcLookRange(),
                        configManager.isNpcLookPerPlayer(),
                        configManager.isNpcLookHeadOnly(),
                        configManager.isNpcLookRealistic());

                if (configManager.isDebug()) {
                    plugin.getLogger().info("[SkinShop] Applied skin '" + skinData.getName() + "' and configured look-close on NPC ID "
                            + npc.getId() + " (" + shopType + ") in arena " + arena.getArenaName());
                }
            } else {
                plugin.getLogger().warning("[SkinShop] Could not find or spawn Citizens NPC at " + targetLoc
                        + " in arena " + arena.getArenaName());
            }
        });
    }

    /**
     * Removes vanilla villagers spawned by BedWars near a location so they don't overlap with Citizens NPC.
     */
    private void removeVanillaVillagersNear(Location loc, double radius) {
        if (loc.getWorld() == null) return;
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity.getType() == EntityType.VILLAGER) {
                // Check if it is not a Citizens NPC
                if (!CitizensAPI.getNPCRegistry().isNPC(entity)) {
                    entity.remove();
                }
            }
        }
    }

    private void trackDynamicNpc(String arenaName, int npcId) {
        arenaDynamicNpcIds.computeIfAbsent(arenaName, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(npcId);
    }

    /**
     * Cleans up any dynamically created Citizens NPCs for an arena by arena name.
     *
     * @param arenaName Target arena name
     */
    public void cleanupArena(String arenaName) {
        if (arenaName == null) return;
        Set<Integer> npcIds = arenaDynamicNpcIds.remove(arenaName);
        if (npcIds != null && citizensHook.isCitizensEnabled()) {
            for (Integer id : npcIds) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                if (npc != null) {
                    citizensHook.destroyNpc(npc);
                }
            }
        }
        znpcPlusHook.cleanupArena(arenaName);
    }

    /**
     * Cleans up any dynamically created Citizens NPCs for an arena when it restarts or is disabled.
     *
     * @param arena Target arena
     */
    public void cleanupArena(IArena arena) {
        if (arena != null) {
            cleanupArena(arena.getArenaName());
        }
    }

    /**
     * Cleans up all dynamically created NPCs across all arenas (e.g. on plugin disable).
     */
    public void cleanupAll() {
        if (citizensHook.isCitizensEnabled()) {
            for (Set<Integer> npcIds : arenaDynamicNpcIds.values()) {
                for (Integer id : npcIds) {
                    NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                    if (npc != null) {
                        citizensHook.destroyNpc(npc);
                    }
                }
            }
        }
        arenaDynamicNpcIds.clear();
        znpcPlusHook.cleanupAll();
    }

    public CitizensHook getCitizensHook() {
        return citizensHook;
    }

    public ZnpcPlusHook getZnpcPlusHook() {
        return znpcPlusHook;
    }
}
