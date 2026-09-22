package me.NedayAzady.profile;

import me.NedayAzady.skin.SkinData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the core requirement of the team skin rendering fix: each player's
 * GameProfile skin properties are kept strictly isolated per UUID. Two
 * teammates whose skins must never cross-contaminate each other's snapshot.
 */
public class PlayerProfileStoreTest {

    @Test
    public void testIsolationBetweenTeammates() {
        PlayerProfileStore store = new PlayerProfileStore();

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        SkinData skin1 = new SkinData("Alex", "texture-value-A", "signature-A");
        SkinData skin2 = new SkinData("Steve", "texture-value-B", "signature-B");

        store.put(u1, "Alex", skin1);
        store.put(u2, "Steve", skin2);

        // No cross-talk: each player only ever receives their OWN textures.
        PlayerProfileStore.Snapshot s1 = store.get(u1);
        PlayerProfileStore.Snapshot s2 = store.get(u2);

        assertNotNull(s1);
        assertNotNull(s2);
        assertEquals("texture-value-A", s1.getTextureValue());
        assertEquals("signature-A", s1.getTextureSignature());
        assertEquals("texture-value-B", s2.getTextureValue());
        assertEquals("signature-B", s2.getTextureSignature());
        assertNotEquals(s1.getTextureValue(), s2.getTextureValue());
        assertNotEquals(s1.getUuid(), s2.getUuid());
    }

    @Test
    public void testOverwriteDoesNotLeak() {
        PlayerProfileStore store = new PlayerProfileStore();
        UUID u1 = UUID.randomUUID();

        store.put(u1, "Alex", new SkinData("Alex", "old", "old-sig"));
        // Same player changes skin mid-match (e.g. /skin) -> snapshot replaced atomically.
        PlayerProfileStore.Snapshot refreshed = store.put(u1, "Alex", new SkinData("Alex", "new", "new-sig"));

        assertEquals("new", refreshed.getTextureValue());
        assertEquals("new", store.get(u1).getTextureValue());
        assertEquals("new-sig", store.get(u1).getTextureSignature());

        // Other teammates remain untouched
        UUID other = UUID.randomUUID();
        store.put(other, "Steve", new SkinData("Steve", "other", "other-sig"));
        assertEquals("other", store.get(other).getTextureValue());
        assertEquals("new", store.get(u1).getTextureValue());
    }

    @Test
    public void testLookupByNameCaseInsensitive() {
        PlayerProfileStore store = new PlayerProfileStore();
        UUID uuid = UUID.randomUUID();
        store.put(uuid, "MyPlayer", new SkinData("MyPlayer", "v", "s"));

        assertEquals(uuid, store.getByName("myplayer").getUuid());
        assertEquals(uuid, store.getByName("MYPLAYER").getUuid());
        assertNull(store.getByName("nobody"));
    }

    @Test
    public void testRemoveAndClear() {
        PlayerProfileStore store = new PlayerProfileStore();
        UUID uuid = UUID.randomUUID();
        store.put(uuid, "Player", new SkinData("Player", "v", "s"));

        store.remove(uuid);
        assertFalse(store.has(uuid));
        assertNull(store.getByName("player"));
        assertEquals(0, store.size());

        store.put(uuid, "Player", new SkinData("Player", "v2", "s2"));
        assertEquals(1, store.size());
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    public void testNullCreationGuards() {
        PlayerProfileStore store = new PlayerProfileStore();
        assertNull(store.put(null, "X", new SkinData("X", "v", "s")));
        assertNull(store.get(null));
        assertNull(store.getByName(null));
    }
}