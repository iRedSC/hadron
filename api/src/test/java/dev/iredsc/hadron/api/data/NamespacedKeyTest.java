package dev.iredsc.hadron.api.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamespacedKeyTest {
    @Test
    void aValidKeyKeepsBothParts() {
        // COVERS: NamespacedKey#NamespacedKey
        // COVERS: NamespacedKey#asString
        NamespacedKey key = new NamespacedKey("my-plugin.x_1", "homes/last.pos-2");
        assertEquals("my-plugin.x_1", key.getNamespace());
        assertEquals("homes/last.pos-2", key.getKey());
        assertEquals("my-plugin.x_1:homes/last.pos-2", key.asString());
        assertEquals(key.asString(), key.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "My", "a b", "a/b", "a:b", "é"})
    void anInvalidNamespaceIsRefusedWithAnExample(String namespace) {
        // COVERS: NamespacedKey#NamespacedKey
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey(namespace, "key"));
        assertTrue(e.getMessage().contains("namespace"), e.getMessage());
        assertTrue(e.getMessage().contains("for example"), e.getMessage());
    }

    @Test
    void theSuggestedNamespaceIsValidAndLowercase() {
        // COVERS: NamespacedKey#NamespacedKey
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("My Plugin", "key"));
        assertTrue(e.getMessage().contains("\"my_plugin\""), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Owner", "a b", "a:b"})
    void anInvalidKeyIsRefusedWithAValidSuggestion(String key) {
        // COVERS: NamespacedKey#NamespacedKey
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("plugin", key));
        assertTrue(e.getMessage().contains("new NamespacedKey(plugin, \""), e.getMessage());
    }

    @Test
    void anEmptyKeySuggestsAPlaceholderName() {
        // COVERS: NamespacedKey#NamespacedKey
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("plugin", ""));
        assertTrue(e.getMessage().contains("\"my_key\""), e.getMessage());
    }

    @Test
    void nullPartsAreRefused() {
        // COVERS: NamespacedKey#NamespacedKey
        assertThrows(NullPointerException.class, () -> new NamespacedKey((String) null, "k"));
        assertThrows(NullPointerException.class, () -> new NamespacedKey("n", null));
    }

    @Test
    void fromStringParsesNamespaceAndKey() {
        // COVERS: NamespacedKey#fromString
        assertEquals(new NamespacedKey("homes", "last/pos"), NamespacedKey.fromString("homes:last/pos"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nokey", ":key", "ns:", "NS:key", "ns:Key", "ns:a:b", "a/b:key"})
    void fromStringGivesNullForWhatIsntAKey(String text) {
        // COVERS: NamespacedKey#fromString
        assertNull(NamespacedKey.fromString(text));
    }

    @Test
    void fromStringRefusesNull() {
        // COVERS: NamespacedKey#fromString
        assertThrows(NullPointerException.class, () -> NamespacedKey.fromString(null));
    }

    @Test
    void parseGivesNullRatherThanThrowing() {
        // COVERS: NamespacedKey#parse
        assertNull(NamespacedKey.parse("Bad", "key"));
        assertEquals(new NamespacedKey("ok", "key"), NamespacedKey.parse("ok", "key"));
    }

    @Test
    void equalKeysAreEqualAndHashAlike() {
        // COVERS: NamespacedKey#equals
        // COVERS: NamespacedKey#hashCode
        assertEquals(new NamespacedKey("a", "b"), new NamespacedKey("a", "b"));
        assertEquals(new NamespacedKey("a", "b").hashCode(), new NamespacedKey("a", "b").hashCode());
        assertNotEquals(new NamespacedKey("a", "b"), new NamespacedKey("b", "a"));
        assertNotEquals(new NamespacedKey("a", "bc"), new NamespacedKey("ab", "c"));
    }
}
