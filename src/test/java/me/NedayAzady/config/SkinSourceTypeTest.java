package me.NedayAzady.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SkinSourceTypeTest {

    @Test
    public void testFromStringValid() {
        assertEquals(SkinSourceType.USERNAME, SkinSourceType.fromString("USERNAME"));
        assertEquals(SkinSourceType.USERNAME, SkinSourceType.fromString("username"));
        assertEquals(SkinSourceType.VALUE_SIGNATURE, SkinSourceType.fromString("VALUE_SIGNATURE"));
        assertEquals(SkinSourceType.VALUE_SIGNATURE, SkinSourceType.fromString("value_signature"));
        assertEquals(SkinSourceType.URL, SkinSourceType.fromString("URL"));
        assertEquals(SkinSourceType.URL, SkinSourceType.fromString("url"));
    }

    @Test
    public void testFromStringInvalidOrNull() {
        assertEquals(SkinSourceType.USERNAME, SkinSourceType.fromString(null));
        assertEquals(SkinSourceType.USERNAME, SkinSourceType.fromString("INVALID_TYPE"));
        assertEquals(SkinSourceType.USERNAME, SkinSourceType.fromString(""));
    }
}
