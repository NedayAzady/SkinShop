package me.NedayAzady.config;

/**
 * Defines the configuration source type for custom skin definitions.
 */
public enum SkinSourceType {
    /**
     * Resolve skin via Minecraft username using Mojang API.
     */
    USERNAME,

    /**
     * Raw Mojang Base64 texture value and cryptographic signature.
     */
    VALUE_SIGNATURE,

    /**
     * Direct skin image texture URL.
     */
    URL;

    /**
     * Parses a string safely into SkinSourceType. Defaults to USERNAME if unknown.
     *
     * @param str string representation
     * @return SkinSourceType
     */
    public static SkinSourceType fromString(String str) {
        if (str == null) return USERNAME;
        try {
            return SkinSourceType.valueOf(str.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return USERNAME;
        }
    }
}
