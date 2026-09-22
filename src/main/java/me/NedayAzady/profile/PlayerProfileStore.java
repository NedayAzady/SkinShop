package me.NedayAzady.profile;

import me.NedayAzady.skin.SkinData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store of immutable, strictly-isolated per-player GameProfile skin
 * snapshots.
 *
 * <p>Each player in a BedWars match gets exactly <b>one</b> snapshot, keyed by
 * their unique UUID, containing ONLY their own textures value + signature plus
 * their display name. Nothing ever writes into another player's snapshot, so a
 * buggy plugin re-sending a stripped GameProfile can never cause a teammate's
 * texture to be swapped onto the wrong player.
 */
public class PlayerProfileStore {

    /**
     * Immutable snapshot of one player's own skin properties.
     */
    public static final class Snapshot {
        private final UUID uuid;
        private final String name;
        private final SkinData skin;

        public Snapshot(UUID uuid, String name, SkinData skin) {
            this.uuid = uuid;
            this.name = name;
            this.skin = skin;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getName() {
            return name;
        }

        public SkinData getSkin() {
            return skin;
        }

        public String getTextureValue() {
            return skin != null ? skin.getValue() : null;
        }

        public String getTextureSignature() {
            return skin != null ? skin.getSignature() : null;
        }

        public boolean hasUsableSkin() {
            return skin != null && skin.hasValue();
        }

        @Override
        public String toString() {
            return "Snapshot{uuid=" + uuid + ", name='" + name + "', hasTexture=" + hasUsableSkin() + "}";
        }
    }

    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    /**
     * Stores (or atomically replaces) the isolated snapshot for a player.
     *
     * @param uuid  the player's UUID (must never be null)
     * @param name  the player's current name
     * @param skin  the player's own skin data
     * @return the created snapshot
     */
    public Snapshot put(UUID uuid, String name, SkinData skin) {
        if (uuid == null) {
            return null;
        }
        Snapshot snapshot = new Snapshot(uuid, name, skin);
        snapshots.put(uuid, snapshot);
        if (name != null) {
            byName.put(name.toLowerCase(), uuid);
        }
        return snapshot;
    }

    /**
     * Retrieves the isolated snapshot for a UUID.
     */
    public Snapshot get(UUID uuid) {
        return uuid != null ? snapshots.get(uuid) : null;
    }

    /**
     * Retrieves a snapshot by current (case-insensitive) player name.
     */
    public Snapshot getByName(String name) {
        if (name == null) {
            return null;
        }
        UUID uuid = byName.get(name.toLowerCase());
        return uuid != null ? snapshots.get(uuid) : null;
    }

    public boolean has(UUID uuid) {
        return uuid != null && snapshots.containsKey(uuid);
    }

    /**
     * Removes the snapshot for a UUID, cleaning both indexes.
     */
    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        Snapshot snapshot = snapshots.remove(uuid);
        if (snapshot != null) {
            byName.remove(snapshot.getName().toLowerCase());
        }
    }

    public void clear() {
        snapshots.clear();
        byName.clear();
    }

    public int size() {
        return snapshots.size();
    }
}