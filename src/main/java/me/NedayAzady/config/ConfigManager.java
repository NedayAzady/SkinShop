package me.NedayAzady.config;

import me.NedayAzady.SkinShop;
import me.NedayAzady.skin.SkinData;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Level;

/**
 * Manages configuration loading, parsing, and caching for the SkinShop addon.
 */
public class ConfigManager {

    private final SkinShop plugin;

    private SkinSourceType emptyTeamSkinType;
    private String emptyTeamUsername;
    private String emptyTeamTextureValue;
    private String emptyTeamTextureSignature;
    private String emptyTeamUrl;

    private int cacheSkinsMinutes;
    private boolean applyToShop;
    private boolean applyToUpgrades;
    private boolean rerollOnlyOnArenaStart;
    private boolean spawnCitizensNpcIfMissing;
    private String npcProvider;
    private long applyDelayTicks;
    private boolean debug;

    // NPC Look / Head Rotation Settings
    private boolean npcLookEnabled;
    private double npcLookRange;
    private boolean npcLookPerPlayer;
    private boolean npcLookHeadOnly;
    private boolean npcLookRealistic;

    private SkinData cachedFallbackSkin;

    public ConfigManager(SkinShop plugin) {
        this.plugin = plugin;
    }

    /**
     * Normalizes the npc-provider value: one of auto / citizens / znpcsplus.
     * Anything else (or null) falls back to "auto".
     */
    private String normalizeNpcProvider(String raw) {
        if (raw == null) return "auto";
        String value = raw.trim().toLowerCase();
        switch (value) {
            case "citizens":
            case "znpcsplus":
                return value;
            default:
                if (!value.equals("auto")) {
                    plugin.getLogger().warning("[Config] Unknown npc-provider '" + raw + "'. Falling back to 'auto'.");
                }
                return "auto";
        }
    }

    /**
     * Loads or reloads the config.yml from disk.
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // Parse empty-team-skin settings
        String typeStr = config.getString("empty-team-skin.type", "USERNAME");
        this.emptyTeamSkinType = SkinSourceType.fromString(typeStr);

        String rawValue = config.getString("empty-team-skin.value", "Dewier");
        this.emptyTeamUsername = config.getString("empty-team-skin.username", rawValue);
        this.emptyTeamTextureValue = config.getString("empty-team-skin.texture-value", "");
        this.emptyTeamTextureSignature = config.getString("empty-team-skin.texture-signature", "");
        this.emptyTeamUrl = config.getString("empty-team-skin.url", rawValue);

        // General settings
        this.cacheSkinsMinutes = Math.max(1, config.getInt("cache-skins-minutes", 60));
        this.applyToShop = config.getBoolean("apply-to-shop", true);
        this.applyToUpgrades = config.getBoolean("apply-to-upgrades", true);
        this.rerollOnlyOnArenaStart = config.getBoolean("reroll-only-on-arena-start", true);
        this.spawnCitizensNpcIfMissing = config.getBoolean("spawn-citizens-npc-if-missing", true);
        this.npcProvider = normalizeNpcProvider(config.getString("npc-provider", "auto"));
        this.applyDelayTicks = Math.max(1L, config.getLong("apply-delay-ticks", 25L));
        this.debug = config.getBoolean("debug", false);

        // Parse NPC look / head rotation settings
        this.npcLookEnabled = config.getBoolean("npc-look-at-players.enabled", true);
        this.npcLookRange = Math.max(1.0, config.getDouble("npc-look-at-players.range", 10.0));
        this.npcLookPerPlayer = config.getBoolean("npc-look-at-players.per-player", true);
        this.npcLookHeadOnly = config.getBoolean("npc-look-at-players.head-only", true);
        this.npcLookRealistic = config.getBoolean("npc-look-at-players.realistic-looking", true);

        // Pre-build empty-team fallback skin if VALUE_SIGNATURE or URL
        buildFallbackSkin();

        if (debug) {
            plugin.getLogger().info("[Config] Config loaded successfully: type=" + emptyTeamSkinType
                    + ", username=" + emptyTeamUsername
                    + ", cacheMins=" + cacheSkinsMinutes
                    + ", applyShop=" + applyToShop
                    + ", applyUpgrades=" + applyToUpgrades
                    + ", npcLook=" + npcLookEnabled
                    + " (range=" + npcLookRange + ", perPlayer=" + npcLookPerPlayer + ", headOnly=" + npcLookHeadOnly + ")");
        }
    }

    private void buildFallbackSkin() {
        switch (emptyTeamSkinType) {
            case VALUE_SIGNATURE:
                if (emptyTeamTextureValue != null && !emptyTeamTextureValue.trim().isEmpty()) {
                    this.cachedFallbackSkin = new SkinData(getEmptyTeamUsername(), emptyTeamTextureValue.trim(), emptyTeamTextureSignature.trim());
                } else {
                    plugin.getLogger().warning("[Config] empty-team-skin type is VALUE_SIGNATURE, but texture-value is empty! Falling back to USERNAME.");
                    this.cachedFallbackSkin = null;
                }
                break;
            case URL:
                if (emptyTeamUrl != null && !emptyTeamUrl.trim().isEmpty()) {
                    this.cachedFallbackSkin = SkinData.fromUrl(getEmptyTeamUsername(), emptyTeamUrl.trim());
                } else {
                    plugin.getLogger().warning("[Config] empty-team-skin type is URL, but url is empty! Falling back to USERNAME.");
                    this.cachedFallbackSkin = null;
                }
                break;
            case USERNAME:
            default:
                // Will be resolved asynchronously via Mojang API / cache by SkinManager
                this.cachedFallbackSkin = null;
                break;
        }
    }

    public SkinSourceType getEmptyTeamSkinType() {
        return emptyTeamSkinType;
    }

    public String getEmptyTeamUsername() {
        return (emptyTeamUsername != null && !emptyTeamUsername.trim().isEmpty()) ? emptyTeamUsername.trim() : "Dewier";
    }

    public String getEmptyTeamTextureValue() {
        return emptyTeamTextureValue;
    }

    public String getEmptyTeamTextureSignature() {
        return emptyTeamTextureSignature;
    }

    public String getEmptyTeamUrl() {
        return emptyTeamUrl;
    }

    public SkinData getPrebuiltFallbackSkin() {
        return cachedFallbackSkin;
    }

    public int getCacheSkinsMinutes() {
        return cacheSkinsMinutes;
    }

    public long getCacheSkinsMillis() {
        return cacheSkinsMinutes * 60L * 1000L;
    }

    public boolean isApplyToShop() {
        return applyToShop;
    }

    public boolean isApplyToUpgrades() {
        return applyToUpgrades;
    }

    public boolean isRerollOnlyOnArenaStart() {
        return rerollOnlyOnArenaStart;
    }

    public boolean isSpawnCitizensNpcIfMissing() {
        return spawnCitizensNpcIfMissing;
    }

    /**
     * @return The configured NPC provider: "auto", "citizens" or "znpcsplus".
     */
    public String getNpcProvider() {
        return npcProvider;
    }

    public long getApplyDelayTicks() {
        return applyDelayTicks;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isNpcLookEnabled() {
        return npcLookEnabled;
    }

    public double getNpcLookRange() {
        return npcLookRange;
    }

    public boolean isNpcLookPerPlayer() {
        return npcLookPerPlayer;
    }

    public boolean isNpcLookHeadOnly() {
        return npcLookHeadOnly;
    }

    public boolean isNpcLookRealistic() {
        return npcLookRealistic;
    }
}
