package me.NedayAzady.command;

import com.andrei1058.bedwars.api.BedWars;
import com.andrei1058.bedwars.api.arena.IArena;
import me.NedayAzady.SkinShop;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Administrative command executor and tab-completer for SkinShop.
 * Commands:
 *   /skinshop reload        - Reloads configuration and cache
 *   /skinshop info <arena>  - Shows skin assignment for an arena
 *   /skinshop update <arena>- Forces skin re-application for an arena
 */
public class SkinShopCommand implements CommandExecutor, TabCompleter {

    private final SkinShop plugin;

    public SkinShopCommand(SkinShop plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skinshop.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to execute this command!");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.getConfigurationManager().loadConfig();
            if (plugin.getProfileIsolationManager() != null) {
                plugin.getProfileIsolationManager().reload();
            }
            sender.sendMessage(ChatColor.GREEN + "[SkinShop] Configuration reloaded successfully!");
            return true;
        }

        BedWars bwApi = plugin.getBedWarsApi();
        if (bwApi == null) {
            sender.sendMessage(ChatColor.RED + "[SkinShop] BedWars1058 API is currently unavailable!");
            return true;
        }

        if (sub.equals("info")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /skinshop info <arenaName>");
                return true;
            }
            String arenaName = args[1];
            IArena arena = bwApi.getArenaUtil().getArenaByName(arenaName);
            if (arena == null) {
                sender.sendMessage(ChatColor.RED + "[SkinShop] Arena '" + arenaName + "' not found!");
                return true;
            }

            sender.sendMessage(ChatColor.GOLD + "=== SkinShop Info: " + arena.getArenaName() + " (" + arena.getStatus() + ") ===");
            Map<String, String> assigned = plugin.getArenaListener().getAssignedPlayersForArena(arenaName);
            if (assigned == null || assigned.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No team skins currently tracked (arena may not be playing).");
            } else {
                for (Map.Entry<String, String> entry : assigned.entrySet()) {
                    sender.sendMessage(ChatColor.YELLOW + " Team " + entry.getKey() + ": " + ChatColor.AQUA + entry.getValue());
                }
            }
            return true;
        }

        if (sub.equals("update")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /skinshop update <arenaName>");
                return true;
            }
            String arenaName = args[1];
            IArena arena = bwApi.getArenaUtil().getArenaByName(arenaName);
            if (arena == null) {
                sender.sendMessage(ChatColor.RED + "[SkinShop] Arena '" + arenaName + "' not found!");
                return true;
            }

            plugin.getArenaListener().processArenaStart(arena);
            sender.sendMessage(ChatColor.GREEN + "[SkinShop] Triggered skin update for arena '" + arenaName + "'.");
            return true;
        }

        if (sub.equals("refresh")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /skinshop refresh <arenaName>");
                return true;
            }
            String arenaName = args[1];
            IArena arena = bwApi.getArenaUtil().getArenaByName(arenaName);
            if (arena == null) {
                sender.sendMessage(ChatColor.RED + "[SkinShop] Arena '" + arenaName + "' not found!");
                return true;
            }

            if (plugin.getProfileIsolationManager() != null && plugin.getProfileIsolationManager().isEnabled()) {
                plugin.getProfileIsolationManager().onArenaStart(arena);
                sender.sendMessage(ChatColor.GREEN + "[SkinShop] Forced isolated profile refresh for arena '" + arenaName + "'.");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "[SkinShop] Team skin render fix is disabled; nothing to refresh.");
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== BedWars1058 SkinShop Addon Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/skinshop reload " + ChatColor.GRAY + "- Reload config.yml");
        sender.sendMessage(ChatColor.YELLOW + "/skinshop info <arena> " + ChatColor.GRAY + "- Show assigned team skins");
        sender.sendMessage(ChatColor.YELLOW + "/skinshop update <arena> " + ChatColor.GRAY + "- Force refresh skins in arena");
        sender.sendMessage(ChatColor.YELLOW + "/skinshop refresh <arena> " + ChatColor.GRAY + "- Force isolated profile packet refresh");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("skinshop.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return Arrays.asList("reload", "info", "update", "refresh", "help").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("update")
                || args[0].equalsIgnoreCase("refresh"))) {
            BedWars bwApi = plugin.getBedWarsApi();
            if (bwApi != null) {
                return bwApi.getArenaUtil().getArenas().stream()
                        .map(IArena::getArenaName)
                        .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return new ArrayList<>();
    }
}
