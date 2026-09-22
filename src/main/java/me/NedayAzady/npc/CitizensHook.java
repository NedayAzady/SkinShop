package me.NedayAzady.npc;

import me.NedayAzady.skin.SkinData;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.skin.SkinnableEntity;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.logging.Logger;

/**
 * Handles direct integration with Citizens 2 API.
 * Encapsulates NPC skin application and NPC lifecycle management.
 */
public class CitizensHook {

    private final Logger logger;
    private final boolean debug;

    public CitizensHook(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Checks if Citizens plugin is installed and ready.
     */
    public boolean isCitizensEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens") && CitizensAPI.hasImplementation();
    }

    /**
     * Finds an existing Citizens NPC near the specified location within a given radius.
     *
     * @param location Target location (e.g., team.getShop())
     * @param radius   Search radius in blocks
     * @return NPC instance if found, or null
     */
    public NPC findNpcAt(Location location, double radius) {
        if (!isCitizensEnabled() || location == null || location.getWorld() == null) {
            return null;
        }

        double radiusSq = radius * radius;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc.isSpawned() && npc.getStoredLocation() != null) {
                Location stored = npc.getStoredLocation();
                if (location.getWorld().equals(stored.getWorld()) && location.distanceSquared(stored) <= radiusSq) {
                    return npc;
                }
            }
        }
        return null;
    }

    /**
     * Spawns a new Citizens Player NPC at the given location.
     *
     * @param location Spawn location
     * @param name     Display name of the NPC (empty string keeps it clean for holograms)
     * @return Created NPC instance
     */
    public NPC createPlayerNpc(Location location, String name) {
        if (!isCitizensEnabled() || location == null) {
            return null;
        }
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name != null ? name : "");
        npc.setProtected(true);
        npc.spawn(location);
        return npc;
    }

    /**
     * Applies skin textures to a Citizens NPC using Citizens SkinTrait and SkinnableEntity.
     *
     * @param npc      Target NPC
     * @param skinData Skin texture and signature data
     * @return true if skin was applied successfully
     */
    public boolean applySkinToNpc(NPC npc, SkinData skinData) {
        if (npc == null || skinData == null) {
            return false;
        }

        try {
            // Apply via Citizens SkinTrait
            SkinTrait skinTrait = npc.getOrAddTrait(SkinTrait.class);
            skinTrait.setFetchDefaultSkin(false);
            skinTrait.setShouldUpdateSkins(false);

            if (skinData.hasValueAndSignature()) {
                // Persistent Mojang skin with cryptographic signature
                skinTrait.setSkinPersistent(skinData.getName(), skinData.getSignature(), skinData.getValue());
                if (debug) {
                    logger.info("[CitizensHook] Set persistent skin with signature on NPC ID " + npc.getId() + " (" + skinData.getName() + ").");
                }
            } else if (skinData.hasValue()) {
                // Custom skin texture value without signature (e.g. direct URL texture)
                skinTrait.setTexture(skinData.getValue(), skinData.getSignature());
                if (debug) {
                    logger.info("[CitizensHook] Set texture value on NPC ID " + npc.getId() + ".");
                }
            } else if (skinData.getName() != null) {
                // Username fallback
                skinTrait.setSkinName(skinData.getName(), true);
                if (debug) {
                    logger.info("[CitizensHook] Set skin name '" + skinData.getName() + "' on NPC ID " + npc.getId() + ".");
                }
            }

            // Also update SkinnableEntity if the entity is currently spawned
            if (npc.isSpawned() && npc.getEntity() instanceof SkinnableEntity) {
                SkinnableEntity skinnable = (SkinnableEntity) npc.getEntity();
                if (skinData.hasValueAndSignature()) {
                    skinnable.setSkinPersistent(skinData.getName(), skinData.getSignature(), skinData.getValue());
                } else if (skinData.getName() != null) {
                    skinnable.setSkinName(skinData.getName(), true);
                }
            }

            // Despawn and respawn NPC to force client packet refresh
            if (npc.isSpawned()) {
                Location currentLoc = npc.getStoredLocation();
                npc.despawn();
                npc.spawn(currentLoc);
            }

            return true;
        } catch (Throwable t) {
            logger.warning("[CitizensHook] Error applying skin to NPC ID " + npc.getId() + ": " + t.getMessage());
            if (debug) {
                t.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Configures the NPC to look at nearby players (turning/rotating head towards players).
     *
     * @param npc              Target NPC
     * @param enabled          Whether look-at-players is enabled
     * @param range            Detection range in blocks
     * @param perPlayer        Whether rotation is per-player packet based
     * @param headOnly         Whether only head turns (while body stays stationary)
     * @param realisticLooking Smooth, realistic head rotation
     */
    public void configureLookClose(NPC npc, boolean enabled, double range, boolean perPlayer, boolean headOnly, boolean realisticLooking) {
        if (npc == null || !isCitizensEnabled()) {
            return;
        }

        try {
            // Guard: only configure the trait while the NPC is actually spawned in a
            // loaded world. Ticking the rotation trait on an entity with a stale/missing
            // tracker entry makes Citizens 1.8 NMS throw "Cannot read field 'xLoc' ...".
            if (!npc.isSpawned() || npc.getEntity() == null || npc.getEntity().getWorld() == null) {
                if (debug) {
                    logger.info("[CitizensHook] Skipping LookClose for NPC ID " + npc.getId() + " (not spawned in a valid world).");
                }
                return;
            }

            LookClose lookClose = npc.getOrAddTrait(LookClose.class);
            lookClose.lookClose(enabled);
            if (enabled) {
                lookClose.setRange(range);
                lookClose.setRealisticLooking(realisticLooking);
                lookClose.setHeadOnly(headOnly);
                lookClose.setPerPlayer(perPlayer);
                lookClose.setRandomlySwitchTargets(true);
            }
            if (debug) {
                logger.info("[CitizensHook] Configured LookClose on NPC ID " + npc.getId()
                        + " (enabled=" + enabled + ", range=" + range + ", headOnly=" + headOnly + ", perPlayer=" + perPlayer + ").");
            }
        } catch (Throwable t) {
            logger.warning("[CitizensHook] Failed to configure LookClose on NPC ID " + npc.getId() + ": " + t.getMessage());
            if (debug) {
                t.printStackTrace();
            }
        }
    }

    /**
     * Safely destroys/removes an NPC from Citizens registry.
     */
    public void destroyNpc(NPC npc) {
        if (npc != null && isCitizensEnabled()) {
            try {
                npc.destroy();
            } catch (Exception ignored) {
            }
        }
    }
}
