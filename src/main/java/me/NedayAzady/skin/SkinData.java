package me.NedayAzady.skin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Encapsulates Minecraft skin texture data:
 * - Skin/player name
 * - Texture value (Base64 encoded JSON)
 * - Texture signature (Mojang cryptographic signature)
 */
public class SkinData {

    private final String name;
    private final String value;
    private final String signature;

    public SkinData(String name, String value, String signature) {
        this.name = name != null ? name : "Fallback";
        this.value = value;
        this.signature = signature;
    }

    public SkinData(String name, String value) {
        this(name, value, null);
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getSignature() {
        return signature;
    }

    public boolean hasValue() {
        return value != null && !value.trim().isEmpty();
    }

    public boolean hasSignature() {
        return signature != null && !signature.trim().isEmpty();
    }

    public boolean hasValueAndSignature() {
        return hasValue() && hasSignature();
    }

    /**
     * Constructs a SkinData object from a direct skin texture URL.
     * Generates standard Minecraft Base64 texture JSON.
     *
     * @param name Name identifier
     * @param url  Direct URL to texture (e.g. http://textures.minecraft.net/texture/...)
     * @return SkinData instance
     */
    public static SkinData fromUrl(String name, String url) {
        if (url == null || url.trim().isEmpty()) {
            return new SkinData(name, null, null);
        }
        String cleanUrl = url.trim();
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + cleanUrl + "\"}}}";
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return new SkinData(name, encoded, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkinData skinData = (SkinData) o;
        return Objects.equals(name, skinData.name) &&
                Objects.equals(value, skinData.value) &&
                Objects.equals(signature, skinData.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, signature);
    }

    @Override
    public String toString() {
        return "SkinData{" +
                "name='" + name + '\'' +
                ", hasValue=" + hasValue() +
                ", hasSignature=" + hasSignature() +
                '}';
    }
}
