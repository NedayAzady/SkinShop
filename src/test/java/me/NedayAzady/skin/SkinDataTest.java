package me.NedayAzady.skin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

public class SkinDataTest {

    @Test
    public void testSkinDataCreation() {
        SkinData skin = new SkinData("TestPlayer", "val123", "sig456");
        assertEquals("TestPlayer", skin.getName());
        assertEquals("val123", skin.getValue());
        assertEquals("sig456", skin.getSignature());
        assertTrue(skin.hasValue());
        assertTrue(skin.hasSignature());
        assertTrue(skin.hasValueAndSignature());
    }

    @Test
    public void testFromUrl() {
        String testUrl = "http://textures.minecraft.net/texture/abc123def456";
        SkinData skin = SkinData.fromUrl("Dewier", testUrl);

        assertEquals("Dewier", skin.getName());
        assertTrue(skin.hasValue());
        assertFalse(skin.hasSignature());

        // Decode Base64 and verify JSON structure
        byte[] decodedBytes = Base64.getDecoder().decode(skin.getValue());
        String json = new String(decodedBytes, StandardCharsets.UTF_8);

        assertTrue(json.contains(testUrl));
        assertTrue(json.contains("\"SKIN\""));
        assertTrue(json.contains("\"url\""));
    }

    @Test
    public void testNullOrEmptyUrl() {
        SkinData skinNull = SkinData.fromUrl("Dewier", null);
        assertFalse(skinNull.hasValue());

        SkinData skinEmpty = SkinData.fromUrl("Dewier", "   ");
        assertFalse(skinEmpty.hasValue());
    }
}
