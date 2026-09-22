package me.NedayAzady.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Handles communication with the Mojang API to resolve player skins.
 * Uses:
 * 1. api.mojang.com to convert username -> UUID
 * 2. sessionserver.mojang.com to convert UUID -> Base64 texture value and signature
 */
public class MojangAPI {

    private static final String MOJANG_UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String MOJANG_SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final int TIMEOUT_MILLIS = 5000;

    private final Logger logger;
    private final boolean debug;

    public MojangAPI(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Resolves skin texture data for a given Minecraft username via Mojang API.
     *
     * @param username Target username
     * @return SkinData or null if lookup fails
     */
    public SkinData fetchSkinByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        String cleanUsername = username.trim();

        // Step 1: Query Mojang to get UUID
        String uuidWithoutDashes = fetchUUID(cleanUsername);
        if (uuidWithoutDashes == null) {
            return null;
        }

        // Step 2: Query Mojang Session Server for textures
        return fetchSkinByUUID(uuidWithoutDashes, cleanUsername);
    }

    /**
     * Fetches raw UUID string without dashes for a username.
     */
    public String fetchUUID(String username) {
        String endpoint = MOJANG_UUID_URL + username;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MILLIS);
            conn.setReadTimeout(TIMEOUT_MILLIS);
            conn.setRequestProperty("User-Agent", "BedWars1058-SkinShop");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonElement jsonElement = new JsonParser().parse(reader);
                    if (jsonElement.isJsonObject()) {
                        JsonObject obj = jsonElement.getAsJsonObject();
                        if (obj.has("id")) {
                            return obj.get("id").getAsString();
                        }
                    }
                }
            } else if (responseCode == 429) {
                logger.warning("[SkinShop] Mojang API rate limit reached (HTTP 429) while fetching UUID for '" + username + "'!");
            } else if (responseCode == 204 || responseCode == 404) {
                if (debug) {
                    logger.info("[SkinShop] Minecraft user '" + username + "' not found on Mojang (HTTP " + responseCode + ").");
                }
            } else {
                logger.warning("[SkinShop] Unexpected HTTP response " + responseCode + " from Mojang UUID API for '" + username + "'.");
            }
        } catch (Exception e) {
            logger.warning("[SkinShop] Network error connecting to Mojang UUID API for '" + username + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetches skin texture value and signature from Mojang Session Server.
     */
    public SkinData fetchSkinByUUID(String uuidWithoutDashes, String fallbackName) {
        String endpoint = MOJANG_SESSION_URL + uuidWithoutDashes + "?unsigned=false";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MILLIS);
            conn.setReadTimeout(TIMEOUT_MILLIS);
            conn.setRequestProperty("User-Agent", "BedWars1058-SkinShop");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonElement jsonElement = new JsonParser().parse(reader);
                    if (jsonElement.isJsonObject()) {
                        JsonObject root = jsonElement.getAsJsonObject();
                        String resolvedName = root.has("name") ? root.get("name").getAsString() : fallbackName;
                        if (root.has("properties")) {
                            JsonArray properties = root.getAsJsonArray("properties");
                            for (JsonElement propElem : properties) {
                                if (propElem.isJsonObject()) {
                                    JsonObject prop = propElem.getAsJsonObject();
                                    if (prop.has("name") && "textures".equals(prop.get("name").getAsString())) {
                                        String value = prop.has("value") ? prop.get("value").getAsString() : null;
                                        String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;
                                        if (value != null) {
                                            return new SkinData(resolvedName, value, signature);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (responseCode == 429) {
                logger.warning("[SkinShop] Mojang Session Server rate limit reached (HTTP 429) for UUID " + uuidWithoutDashes + "!");
            } else {
                logger.warning("[SkinShop] Unexpected HTTP response " + responseCode + " from Mojang Session API for UUID " + uuidWithoutDashes + ".");
            }
        } catch (Exception e) {
            logger.warning("[SkinShop] Network error connecting to Mojang Session API: " + e.getMessage());
        }
        return null;
    }
}
