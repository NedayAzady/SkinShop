package me.NedayAzady.skin;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

public class SkinCacheTest {

    @Test
    public void testPutAndGet() {
        SkinCache cache = new SkinCache(Logger.getGlobal());
        SkinData skin = new SkinData("Notch", "texture_val", "sig_val");

        cache.put("Notch", skin);
        assertEquals(1, cache.size());

        // Case-insensitive lookup
        SkinData retrieved = cache.get("notch", 60000L);
        assertNotNull(retrieved);
        assertEquals("Notch", retrieved.getName());
        assertEquals("texture_val", retrieved.getValue());
    }

    @Test
    public void testTtlExpiration() throws InterruptedException {
        SkinCache cache = new SkinCache(Logger.getGlobal());
        SkinData skin = new SkinData("Alex", "texture_val", "sig_val");

        cache.put("Alex", skin);

        // Immediately check with positive TTL
        assertNotNull(cache.get("Alex", 5000L));

        // Check with 0ms or negative TTL (forces expiration)
        assertNull(cache.get("Alex", -1L));
        assertEquals(0, cache.size());
    }

    @Test
    public void testClear() {
        SkinCache cache = new SkinCache(Logger.getGlobal());
        cache.put("Player1", new SkinData("Player1", "val1"));
        cache.put("Player2", new SkinData("Player2", "val2"));

        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
    }
}
