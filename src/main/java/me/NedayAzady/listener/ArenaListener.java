package me.NedayAzady.listener;

import com.andrei1058.bedwars.api.arena.GameState;
import com.andrei1058.bedwars.api.arena.IArena;
import com.andrei1058.bedwars.api.arena.team.ITeam;
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent;
import com.andrei1058.bedwars.api.events.player.PlayerFirstSpawnEvent;
import com.andrei1058.bedwars.api.events.player.PlayerLeaveArenaEvent;
import com.andrei1058.bedwars.api.events.player.PlayerReJoinEvent;
import com.andrei1058.bedwars.api.events.server.ArenaDisableEvent;
import com.andrei1058.bedwars.api.events.server.ArenaRestartEvent;
import me.NedayAzady.SkinShop;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.npc.NPCShopManager;
import me.NedayAzady.profile.ProfileIsolationManager;
import me.NedayAzady.skin.SkinData;
import me.NedayAzady.skin.SkinManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * =========================================================================================
 *                         BEDWARS1058 API VERIFICATION REFERENCE
 * =========================================================================================
 * Double-check the following BedWars1058 API classes and methods against your server's jar:
 *
 * 1. com.andrei1058.bedwars.api.BedWars
 *    - Main API interface registered in Bukkit ServicesManager.
 *
 * 2. com.andrei1058.bedwars.api.arena.IArena
 *    - arena.getTeams()           -> List<ITeam> (All teams configured for this arena)
 *    - arena.getStatus()          -> GameState (Current state: waiting, starting, playing, restarting)
 *    - arena.getArenaName()       -> String (Unique identifier/world name of the arena)
 *    - arena.getPlayers()         -> List<Player> (Players currently in the match)
 *
 * 3. com.andrei1058.bedwars.api.arena.team.ITeam
 *    - team.getName()             -> String (Internal name: Red, Blue, etc.)
 *    - team.getMembers()          -> List<Player> (Alive team members currently in match)
 *    - team.getShop()             -> Location (Location where team's Shopkeeper NPC stands)
 *    - team.getTeamUpgrades()     -> Location (Location where team's Upgrade NPC stands)
 *    - team.getArena()            -> IArena (Parent arena instance)
 *
 * 4. Events in com.andrei1058.bedwars.api.events:
 *    - GameStateChangeEvent       -> Fired when arena changes state (e.g. starting -> playing)
 *    - PlayerFirstSpawnEvent      -> Fired when a player spawns for the first time in a match
 *    - PlayerReJoinEvent          -> Fired when a player rejoins an active match
 *    - PlayerLeaveArenaEvent      -> Fired when a player abandons or leaves an arena
 *    - ArenaRestartEvent          -> Fired when an arena triggers map reset/restart
 *    - ArenaDisableEvent          -> Fired when an arena is disabled
 * =========================================================================================
 */
public class ArenaListener implements Listener {

    private final SkinShop plugin;
    private final ConfigManager configManager;
    private final SkinManager skinManager;
    private final NPCShopManager npcShopManager;
    private final ProfileIsolationManager profileIsolationManager;

    // Track assigned player name per team per arena: arenaName -> (teamName -> playerName)
    private final Map<String, Map<String, String>> arenaTeamAssignedPlayer = new ConcurrentHashMap<>();

    public ArenaListener(SkinShop plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigurationManager();
        this.skinManager = plugin.getSkinManager();
        this.npcShopManager = plugin.getNpcShopManager();
        this.profileIsolationManager = plugin.getProfileIsolationManager();
    }

    /**
     * Handles arena state changes.
     * When state switches to 'playing', applies random player skin or empty-team fallback skin.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameStateChange(GameStateChangeEvent event) {
        IArena arena = event.getArena();
        if (arena == null) return;

        GameState newState = event.getNewState();
        if (newState == GameState.playing) {
            // BedWars re-clones/resets the arena world when the match starts, which
            // orphans any Citizens NPC spawned during the 'waiting' phase (its entity
            // tracker entry becomes null). Citizens' rotation trait then spams this NPE:
            //   "Cannot read field 'xLoc' because 'entry' is null" (NMSImpl.sendPositionUpdate).
            // Destroy those stale NPCs now so fresh ones are created after the reset.
            npcShopManager.cleanupArena(arena.getArenaName());

            // Team Skin Rendering Fix: isolate + refresh every member's own GameProfile
            // skin properties at match init (PacketPlayOutPlayerInfo correctness).
            if (profileIsolationManager != null) {
                profileIsolationManager.onArenaStart(arena);
            }

            // Delay skin application slightly so BedWars1058 finishes initializing team bases and NPCs
            long delay = configManager.getApplyDelayTicks();
            Bukkit.getScheduler().runTaskLater(plugin, () -> processArenaStart(arena), delay);
        } else if (newState == GameState.restarting) {
            // Clean up arena data and dynamic NPCs on match end
            cleanupArena(arena);
        } else if (newState == GameState.waiting) {
            // While the arena is in waiting phase, fix any Shop NPC visible from the
            // lobby to show the configured empty-team fallback skin (default: Dewier).
            processArenaWaiting(arena);
        }
    }

    /**
     * Applies the configured empty-team fallback skin (default: "Dewier") to every
     * team's shopkeeper NPCs while the arena is in the waiting phase, so whatever
     * Shop NPCs are already visible before the game starts show the correct skin.
     */
    public void processArenaWaiting(IArena arena) {
        if (arena == null || arena.getStatus() != GameState.waiting) {
            return;
        }

        String arenaName = arena.getArenaName();
        Map<String, String> teamMap = arenaTeamAssignedPlayer.computeIfAbsent(arenaName, k -> new ConcurrentHashMap<>());
        String fallbackUsername = configManager.getEmptyTeamUsername();

        for (ITeam team : arena.getTeams()) {
            teamMap.put(team.getName(), fallbackUsername);
            skinManager.resolveFallbackSkin().thenAccept(skinData -> {
                npcShopManager.updateTeamSkins(arena, team, skinData);
            });
        }

        if (configManager.isDebug()) {
            plugin.getLogger().info("[SkinShop] Arena '" + arenaName + "' is in waiting state. "
                    + "Applied fallback skin '" + fallbackUsername + "' to all team shopkeepers.");
        }
    }

    /**
     * Iterates all teams in the started arena:
     * - If team has >= 1 member: picks a random member and applies their skin.
     * - If team is empty: applies the configured empty-team fallback skin (default: "Dewier").
     */
    public void processArenaStart(IArena arena) {
        if (arena == null || arena.getStatus() != GameState.playing) {
            return;
        }

        String arenaName = arena.getArenaName();
        Map<String, String> teamMap = arenaTeamAssignedPlayer.computeIfAbsent(arenaName, k -> new ConcurrentHashMap<>());
        String fallbackUsername = configManager.getEmptyTeamUsername();

        for (ITeam team : arena.getTeams()) {
            List<Player> members = team.getMembers();

            if (members != null && !members.isEmpty()) {
                // Team has at least one player: pick one random player from this team
                Player chosenPlayer = members.get(ThreadLocalRandom.current().nextInt(members.size()));
                String chosenName = chosenPlayer.getName();
                teamMap.put(team.getName(), chosenName);

                if (configManager.isDebug()) {
                    plugin.getLogger().info("[SkinShop] Arena '" + arenaName + "' Team '" + team.getName()
                            + "': Picked random player '" + chosenName + "' (" + members.size() + " member(s)).");
                }

                // Resolve skin and apply to NPC
                skinManager.resolvePlayerSkin(chosenPlayer, chosenName).thenAccept(skinData -> {
                    npcShopManager.updateTeamSkins(arena, team, skinData);
                });

            } else {
                // Team is empty (e.g. solo/duo mode with unfilled team slots): apply fallback skin
                teamMap.put(team.getName(), fallbackUsername);

                if (configManager.isDebug()) {
                    plugin.getLogger().info("[SkinShop] Arena '" + arenaName + "' Team '" + team.getName()
                            + "' is empty. Applying fallback skin '" + fallbackUsername + "'.");
                }

                skinManager.resolveFallbackSkin().thenAccept(skinData -> {
                    npcShopManager.updateTeamSkins(arena, team, skinData);
                });
            }
        }
    }

    /**
     * Handles player first spawn in match (e.g. game start or late team assignment).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFirstSpawn(PlayerFirstSpawnEvent event) {
        handlePlayerJoinTeam(event.getArena(), event.getTeam(), event.getPlayer());
    }

    /**
     * Handles player rejoin to an arena in progress.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRejoin(PlayerReJoinEvent event) {
        IArena arena = event.getArena();
        Player player = event.getPlayer();
        if (arena != null && player != null) {
            ITeam team = arena.getTeam(player);
            if (team == null) {
                team = arena.getExTeam(player.getUniqueId());
            }
            if (team != null) {
                handlePlayerJoinTeam(arena, team, player);
            }
        }
    }

    /**
     * When a player joins a team mid-game:
     * - If team already has an assigned player and rerollOnlyOnArenaStart is true, do NOT change it.
     * - If team was previously empty (used the fallback skin), now update to the newly joined player's skin.
     */
    private void handlePlayerJoinTeam(IArena arena, ITeam team, Player player) {
        if (arena == null || team == null || player == null) return;
        if (arena.getStatus() != GameState.playing) return;

        // Team Skin Rendering Fix: immediately re-apply and refresh this player's
        // correct GameProfile skin properties for everyone in the arena world.
        if (profileIsolationManager != null) {
            profileIsolationManager.onArenaPlayerJoin(arena, player);
        }

        String arenaName = arena.getArenaName();
        Map<String, String> teamMap = arenaTeamAssignedPlayer.computeIfAbsent(arenaName, k -> new ConcurrentHashMap<>());
        String existingAssigned = teamMap.get(team.getName());
        String fallbackUsername = configManager.getEmptyTeamUsername();

        if (existingAssigned == null || existingAssigned.equalsIgnoreCase(fallbackUsername)) {
            // Team was empty! Now it has a player, so assign this player's skin
            teamMap.put(team.getName(), player.getName());
            skinManager.resolvePlayerSkin(player, player.getName()).thenAccept(skinData -> {
                npcShopManager.updateTeamSkins(arena, team, skinData);
            });
        } else if (!configManager.isRerollOnlyOnArenaStart()) {
            // If admin explicitly allowed mid-game re-rolling:
            List<Player> members = team.getMembers();
            if (members != null && !members.isEmpty()) {
                Player chosen = members.get(ThreadLocalRandom.current().nextInt(members.size()));
                teamMap.put(team.getName(), chosen.getName());
                skinManager.resolvePlayerSkin(chosen, chosen.getName()).thenAccept(skinData -> {
                    npcShopManager.updateTeamSkins(arena, team, skinData);
                });
            }
        }
    }

    /**
     * Handles player leaving an arena mid-game.
     * If all players leave a team, falls back to the empty-team fallback skin.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLeaveArena(PlayerLeaveArenaEvent event) {
        IArena arena = event.getArena();
        Player player = event.getPlayer();
        if (arena == null || player == null || arena.getStatus() != GameState.playing) return;

        ITeam team = arena.getTeam(player);
        if (team == null) return;

        // Schedule check 1 tick later to allow BedWars1058 to update member list
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (arena.getStatus() != GameState.playing) return;
            List<Player> remaining = team.getMembers();
            if (remaining == null || remaining.isEmpty()) {
                // Team is now empty, switch back to fallback skin
                Map<String, String> teamMap = arenaTeamAssignedPlayer.get(arena.getArenaName());
                if (teamMap != null) {
                    teamMap.put(team.getName(), configManager.getEmptyTeamUsername());
                }
                skinManager.resolveFallbackSkin().thenAccept(skinData -> {
                    npcShopManager.updateTeamSkins(arena, team, skinData);
                });
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaRestart(ArenaRestartEvent event) {
        if (event.getArenaName() != null) {
            cleanupArena(event.getArenaName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaDisable(ArenaDisableEvent event) {
        if (event.getArenaName() != null) {
            cleanupArena(event.getArenaName());
        }
    }

    private void cleanupArena(String arenaName) {
        if (arenaName != null) {
            arenaTeamAssignedPlayer.remove(arenaName);
            if (profileIsolationManager != null) {
                profileIsolationManager.onArenaEnd(arenaName);
            }
            npcShopManager.cleanupArena(arenaName);
        }
    }

    private void cleanupArena(IArena arena) {
        if (arena != null) {
            cleanupArena(arena.getArenaName());
        }
    }

    public Map<String, String> getAssignedPlayersForArena(String arenaName) {
        return arenaTeamAssignedPlayer.get(arenaName);
    }
}
