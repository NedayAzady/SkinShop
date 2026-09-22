package me.NedayAzady.skin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe local cache for skin data with TTL support and YAML persistence.
 * Prevents hitting Mojang API rate limits.
 */
public class SkinCache {

    private final Logger logger;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SkinCache(Logger logger) {
        this.logger = logger;
    }

    public static class CacheEntry {
        private final SkinData skinData;
        private final long timestamp;

        public CacheEntry(SkinData skinData, long timestamp) {
            this.skinData = skinData;
            this.timestamp = timestamp;
        }

        public SkinData getSkinData() {
            return skinData;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isExpired(long ttlMillis) {
            return (System.currentTimeMillis() - timestamp) > ttlMillis;
        }
    }

    /**
     * Retrieves cached skin data if present and not expired.
     *
     * @param key       Skin key or username (case-insensitive)
     * @param ttlMillis Time-to-live in milliseconds
     * @return Cached SkinData or null if not found/expired
     */
    public SkinData get(String key, long ttlMillis) {
        if (key == null) return null;
        String normalizedKey = key.trim().toLowerCase();
        CacheEntry entry = cache.get(normalizedKey);
        if (entry == null) return null;

        if (entry.isExpired(ttlMillis)) {
            cache.remove(normalizedKey);
            return null;
        }
        return entry.getSkinData();
    }

    /**
     * Puts a skin in the cache with the current timestamp.
     *
     * @param key      Skin key or username
     * @param skinData Skin data
     */
    public void put(String key, SkinData skinData) {
        if (key == null || skinData == null) return;
        cache.put(key.trim().toLowerCase(), new CacheEntry(skinData, System.currentTimeMillis()));
    }

    /**
     * Clears all in-memory entries.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Saves cached skins to a YAML file for persistence across server restarts.
     *
     * @param file target YAML file
     */
    public void saveToFile(File file) {
        if (file == null) return;
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                String key = entry.getKey();
                CacheEntry ce = entry.getValue();
                ConfigurationSection sec = yaml.createSection(key);
                sec.set("name", ce.getSkinData().getName());
                sec.set("value", ce.getSkinData().getValue());
                sec.set("signature", ce.getSkinData().getSignature());
                sec.set("timestamp", ce.getTimestamp());
            }
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("[SkinShop] Failed to save skin cache to file: " + e.getMessage());
        }
    }

    /**
     * Loads cached skins from a YAML file.
     *
     * @param file target YAML file
     */
    public void loadFromFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                ConfigurationSection sec = yaml.getConfigurationSection(key);
                if (sec != null) {
                    String name = sec.getString("name", key);
                    String value = sec.getString("value", "");
                    String signature = sec.getString("signature", "");
                    long timestamp = sec.getLong("timestamp", System.currentTimeMillis());
                    SkinData data = new SkinData(name, value, signature);
                    cache.put(key.toLowerCase(), new CacheEntry(data, timestamp));
                }
            }
        } catch (Exception e) {
            logger.warning("[SkinShop] Failed to load skin cache from file: " + e.getMessage());
        }
    }

    public int size() {
        return cache.size();
    }
}
