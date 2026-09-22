package me.NedayAzady.skin;

import me.NedayAzady.SkinShop;
import me.NedayAzady.config.ConfigManager;
import me.NedayAzady.config.SkinSourceType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * High-level skin management orchestrator.
 * Resolves skins using the following prioritized hierarchy:
 * 1. Online player profile (0ms latency, zero HTTP requests)
 * 2. Local skin cache (respects cache-skins-minutes TTL)
 * 3. Mojang API (async HTTP lookup)
 * 4. Fallback configured empty-team skin (default username: "Dewier")
 * 5. Hardcoded default safety skin (guarantees server never crashes)
 */
public class SkinManager {

    // Default hardcoded safety skin texture (only used if even the fallback lookup fails)
    private static final String DEFAULT_SAFETY_TEXTURE =
            "ewogICJ0aW1lc3RhbXAiIDogMTYwMDAwMDAwMDAwMCwKICAicHJvZmlsZUlkIiA6ICIwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJEd2FyZiIsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzkxMmZlZjNmMGQ3ODk1MjMzNWQ4ODJjODRmMTYzNTlmZmQzODMxNDY4NmRmNGZmMTM3NzczOWNjMTRmODc3IgogICAgfQogIH0KfQ==";

    private final SkinShop plugin;
    private final ConfigManager configManager;
    private final SkinCache skinCache;
    private final MojangAPI mojangAPI;

    public SkinManager(SkinShop plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigurationManager();
        this.skinCache = new SkinCache(plugin.getLogger());
        this.mojangAPI = new MojangAPI(plugin.getLogger(), configManager.isDebug());

        // Load cache from disk
        File cacheFile = new File(plugin.getDataFolder(), "skin-cache.yml");
        skinCache.loadFromFile(cacheFile);
    }

    /**
     * Resolves a skin for an online player or target username asynchronously.
     *
     * @param player   Online player instance (may be null if resolving an offline name)
     * @param username Minecraft username
     * @return CompletableFuture containing resolved SkinData (or fallback empty-team skin on failure)
     */
    public CompletableFuture<SkinData> resolvePlayerSkin(Player player, String username) {
        // 1. Direct retrieval from online Player's GameProfile (fastest, no network)
        if (player != null && player.isOnline()) {
            SkinData profileSkin = extractSkinFromPlayer(player);
            if (profileSkin != null && profileSkin.hasValue()) {
                skinCache.put(player.getName(), profileSkin);
                return CompletableFuture.completedFuture(profileSkin);
            }
        }

        // 2. Check local memory/disk cache
        long ttlMillis = configManager.getCacheSkinsMillis();
        SkinData cachedSkin = skinCache.get(username, ttlMillis);
        if (cachedSkin != null) {
            if (configManager.isDebug()) {
                plugin.getLogger().info("[SkinShop] Skin for '" + username + "' retrieved from local cache.");
            }
            return CompletableFuture.completedFuture(cachedSkin);
        }

        // 3. Asynchronously fetch from Mojang API
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (configManager.isDebug()) {
                    plugin.getLogger().info("[SkinShop] Fetching skin for '" + username + "' from Mojang API...");
                }
                SkinData mojangSkin = mojangAPI.fetchSkinByUsername(username);
                if (mojangSkin != null && mojangSkin.hasValue()) {
                    skinCache.put(username, mojangSkin);
                    return mojangSkin;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[SkinShop] Failed to fetch skin for '" + username + "': " + e.getMessage());
            }

            // Fallback if Mojang lookup fails
            plugin.getLogger().warning("[SkinShop] Skin lookup failed for '" + username + "'. Falling back to empty-team skin ('" + configManager.getEmptyTeamUsername() + "').");
            return getFallbackSkinSync();
        });
    }

    /**
     * Resolves the configured empty-team fallback skin (default: "Dewier").
     */
    public CompletableFuture<SkinData> resolveFallbackSkin() {
        SkinData prebuilt = configManager.getPrebuiltFallbackSkin();
        if (prebuilt != null && prebuilt.hasValue()) {
            return CompletableFuture.completedFuture(prebuilt);
        }

        if (configManager.getEmptyTeamSkinType() == SkinSourceType.USERNAME) {
            String fallbackUsername = configManager.getEmptyTeamUsername();
            long ttlMillis = configManager.getCacheSkinsMillis();
            SkinData cached = skinCache.get(fallbackUsername, ttlMillis);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }

            return CompletableFuture.supplyAsync(() -> {
                SkinData fetched = mojangAPI.fetchSkinByUsername(fallbackUsername);
                if (fetched != null && fetched.hasValue()) {
                    skinCache.put(fallbackUsername, fetched);
                    return fetched;
                }
                // Return safety default if fallback username lookup fails
                return getHardcodedSafetySkin();
            });
        }

        return CompletableFuture.completedFuture(getHardcodedSafetySkin());
    }

    /**
     * Synchronous fallback getter for the empty-team fallback skin.
     */
    public SkinData getFallbackSkinSync() {
        SkinData prebuilt = configManager.getPrebuiltFallbackSkin();
        if (prebuilt != null && prebuilt.hasValue()) {
            return prebuilt;
        }

        String fallbackUsername = configManager.getEmptyTeamUsername();
        SkinData cached = skinCache.get(fallbackUsername, configManager.getCacheSkinsMillis());
        if (cached != null) {
            return cached;
        }

        return getHardcodedSafetySkin();
    }

    /**
     * Hardcoded fallback skin guaranteed to be valid and never fail.
     */
    public SkinData getHardcodedSafetySkin() {
        return new SkinData(configManager.getEmptyTeamUsername(), DEFAULT_SAFETY_TEXTURE, null);
    }

    /**
     * Attempts to read skin textures directly from the online Player GameProfile
     * using CraftPlayer reflection. Works across CraftBukkit / Spigot / Paper versions.
     */
    public SkinData readSkinFromPlayerProfile(Player player) {
        return extractSkinFromPlayer(player);
    }

    /**
     * Attempts to read skin textures directly from the online Player GameProfile
     * using CraftPlayer reflection. Works across CraftBukkit / Spigot / Paper versions.
     */
    private SkinData extractSkinFromPlayer(Player player) {
        try {
            Method getProfileMethod = player.getClass().getMethod("getProfile");
            Object gameProfile = getProfileMethod.invoke(player);
            if (gameProfile != null) {
                Method getPropertiesMethod = gameProfile.getClass().getMethod("getProperties");
                Object propertyMap = getPropertiesMethod.invoke(gameProfile);

                if (propertyMap instanceof com.google.common.collect.Multimap) {
                    @SuppressWarnings("rawtypes")
                    com.google.common.collect.Multimap rawMap = (com.google.common.collect.Multimap) propertyMap;
                    @SuppressWarnings("unchecked")
                    Collection<?> textures = rawMap.get("textures");
                    if (textures != null && !textures.isEmpty()) {
                        Object prop = textures.iterator().next();
                        Method getValueMethod = prop.getClass().getMethod("getValue");
                        Method getSignatureMethod = prop.getClass().getMethod("getSignature");
                        String value = (String) getValueMethod.invoke(prop);
                        String signature = (String) getSignatureMethod.invoke(prop);
                        if (value != null && !value.isEmpty()) {
                            return new SkinData(player.getName(), value, signature);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (configManager.isDebug()) {
                plugin.getLogger().info("[SkinShop] Reflection extraction from player profile not supported on this platform: " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * Saves cache to disk when disabling plugin.
     */
    public void saveCache() {
        File cacheFile = new File(plugin.getDataFolder(), "skin-cache.yml");
        skinCache.saveToFile(cacheFile);
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }
}
