package me.NedayAzady;

import com.andrei1058.bedwars.api.BedWars;
import me.NedayAzady.command.SkinShopCommand;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.listener.ArenaListener;
import me.NedayAzady.npc.NPCShopManager;
import me.NedayAzady.profile.ProfileIsolationManager;
import me.NedayAzady.skin.SkinManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BedWars1058 Shop Keeper Random Team-Skin Addon
 *
 * @author NedayAzady
 */
public class SkinShop extends JavaPlugin {

    private static SkinShop instance;

    private ConfigManager configManager;
    private SkinManager skinManager;
    private NPCShopManager npcShopManager;
    private ArenaListener arenaListener;
    private BedWars bedWarsApi;
    private ProfileIsolationManager profileIsolationManager;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("===============================================");
        getLogger().info("    BedWars1058 SkinShop Addon - by NedayAzady  ");
        getLogger().info("===============================================");

        // 1. Hook into BedWars1058 API
        if (!hookBedWars()) {
            getLogger().severe("[SkinShop] Could not find BedWars1058 API! Disabling addon.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize configuration
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 3. Initialize skin manager and cache
        this.skinManager = new SkinManager(this);

        // 3b. Team Skin Rendering Fix: isolated per-player GameProfile packet fix
        this.profileIsolationManager = new ProfileIsolationManager(this);
        if (configManager.isEnableSkinRenderFix()) {
            profileIsolationManager.enable();
        }

        // 4. Initialize NPC manager
        this.npcShopManager = new NPCShopManager(this);

        // 5. Register listeners
        this.arenaListener = new ArenaListener(this);
        getServer().getPluginManager().registerEvents(arenaListener, this);

        // 6. Register commands
        SkinShopCommand cmd = new SkinShopCommand(this);
        if (getCommand("skinshop") != null) {
            getCommand("skinshop").setExecutor(cmd);
            getCommand("skinshop").setTabCompleter(cmd);
        }

        // 7. Check NPC provider (Citizens or ZNPCsPlus) status
        if (getServer().getPluginManager().isPluginEnabled("Citizens")) {
            getLogger().info("[SkinShop] Successfully hooked into Citizens support.");
        } else if (getServer().getPluginManager().isPluginEnabled("ZNPCsPlus")) {
            getLogger().info("[SkinShop] Citizens not found; hooked into ZNPCsPlus support instead.");
        } else {
            getLogger().warning("[SkinShop] Neither Citizens nor ZNPCsPlus found. In order for shopkeeper NPCs to render custom player skins, install Citizens or ZNPCsPlus.");
        }

        getLogger().info("[SkinShop] Addon enabled successfully!");
    }

    @Override
    public void onDisable() {
        getLogger().info("[SkinShop] Disabling SkinShop addon...");

        // Clean up all dynamically created Citizens NPCs
        if (npcShopManager != null) {
            npcShopManager.cleanupAll();
        }

        // Disarm team skin rendering fix and eject packet interceptors
        if (profileIsolationManager != null) {
            profileIsolationManager.shutdown();
        }

        // Persist cache to file
        if (skinManager != null) {
            skinManager.saveCache();
        }

        getLogger().info("[SkinShop] Addon disabled.");
    }

    /**
     * Hooks into the BedWars1058 API via Bukkit ServicesManager.
     *
     * In BedWars1058, the API provider is registered as:
     * Bukkit.getServicesManager().register(com.andrei1058.bedwars.api.BedWars.class, api, this, ServicePriority.Highest);
     */
    private boolean hookBedWars() {
        if (getServer().getPluginManager().getPlugin("BedWars1058") == null) {
            return false;
        }
        RegisteredServiceProvider<BedWars> rsp = getServer().getServicesManager().getRegistration(BedWars.class);
        if (rsp != null) {
            this.bedWarsApi = rsp.getProvider();
            return this.bedWarsApi != null;
        }
        return false;
    }

    public static SkinShop getInstance() {
        return instance;
    }

    public ConfigManager getConfigurationManager() {
        return configManager;
    }

    public SkinManager getSkinManager() {
        return skinManager;
    }

    public NPCShopManager getNpcShopManager() {
        return npcShopManager;
    }

    public ArenaListener getArenaListener() {
        return arenaListener;
    }

    public ProfileIsolationManager getProfileIsolationManager() {
        return profileIsolationManager;
    }

    public BedWars getBedWarsApi() {
        return bedWarsApi;
    }
}
