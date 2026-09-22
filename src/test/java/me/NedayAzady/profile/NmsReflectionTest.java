package me.NedayAzady.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the packet classification helpers used by the interceptor: it must
 * recognize the exact simple class names of the player-info / named-spawn /
 * scoreboard-team packets it fixes, independent of the NMS package version.
 */
public class NmsReflectionTest {

    @Test
    public void testSimpleNameOfPacketClasses() {
        Object classicPlayerInfo = new me.NedayAzady.profile.nms.PacketPlayOutPlayerInfo();
        Object modernInfoUpdate = new me.NedayAzady.profile.nms.ClientboundPlayerInfoUpdatePacket();

        assertEquals("PacketPlayOutPlayerInfo", NmsReflection.simpleName(classicPlayerInfo), "1.8-1.19.2 classic tablist packet");
        assertEquals("ClientboundPlayerInfoUpdatePacket", NmsReflection.simpleName(modernInfoUpdate), "1.19.3+ modern tablist packet");
        assertTrue(NmsReflection.isSimpleName(classicPlayerInfo, "PacketPlayOutPlayerInfo"));
        assertFalse(NmsReflection.isSimpleName(classicPlayerInfo, "PacketPlayOutNamedEntitySpawn"));
        assertTrue(NmsReflection.isSimpleName(modernInfoUpdate, "ClientboundPlayerInfoUpdatePacket"));
    }

    @Test
    public void testReplaceInContainer() {
        Object a = "a";
        Object b = "b";
        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add(a);
        list.add(b);

        assertTrue(NmsReflection.replaceInContainer(list, b, "replacement"));
        assertEquals("a", list.get(0));
        assertEquals("replacement", list.get(1));

        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("k", a);
        assertTrue(NmsReflection.replaceInContainer(map, a, "mapped"));
        assertEquals("mapped", map.get("k"));

        assertFalse(NmsReflection.replaceInContainer(null, a, b));
    }
}