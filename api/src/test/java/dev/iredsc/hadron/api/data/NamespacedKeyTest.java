package dev.iredsc.hadron.api.data;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamespacedKeyTest {
    @Covers(value = NamespacedKey.class, method = "asString")
    @Covers(value = NamespacedKey.class, method = "toString")
    @Test
    void aValidKeyKeepsBothParts() {
        NamespacedKey key = new NamespacedKey("my-plugin.x_1", "homes/last.pos-2");
        assertEquals("my-plugin.x_1", key.getNamespace());
        assertEquals("homes/last.pos-2", key.getKey());
        assertEquals("my-plugin.x_1:homes/last.pos-2", key.asString());
        assertEquals(key.asString(), key.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "My", "a b", "a/b", "a:b", "é"})
    void anInvalidNamespaceIsRefusedWithAnExample(String namespace) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey(namespace, "key"));
        assertTrue(e.getMessage().contains("namespace"), e.getMessage());
        assertTrue(e.getMessage().contains("for example"), e.getMessage());
    }

    @Test
    void theSuggestedNamespaceIsValidAndLowercase() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("My Plugin", "key"));
        assertTrue(e.getMessage().contains("\"my_plugin\""), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Owner", "a b", "a:b"})
    void anInvalidKeyIsRefusedWithAValidSuggestion(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("plugin", key));
        assertTrue(e.getMessage().contains("new NamespacedKey(plugin, \""), e.getMessage());
    }

    @Test
    void aSuggestionKeepsEveryAllowedCharacterAndReplacesTheRest() {
        IllegalArgumentException key = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("plugin", "Az09._-/~`{:"));
        assertTrue(key.getMessage().contains("\"az09._-/____\""), key.getMessage());
        IllegalArgumentException namespace = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("Az09._-/~`{:", "key"));
        assertTrue(namespace.getMessage().contains("\"az09._-_____\""), namespace.getMessage());
    }

    @Test
    void anEmptyKeySuggestsAPlaceholderName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new NamespacedKey("plugin", ""));
        assertTrue(e.getMessage().contains("\"my_key\""), e.getMessage());
    }

    @Test
    void nullPartsAreRefused() {
        assertThrows(NullPointerException.class, () -> new NamespacedKey((String) null, "k"));
        assertThrows(NullPointerException.class, () -> new NamespacedKey("n", null));
    }

    @Covers(value = NamespacedKey.class, method = "fromString")
    @Test
    void fromStringParsesNamespaceAndKey() {
        assertEquals(new NamespacedKey("homes", "last/pos"), NamespacedKey.fromString("homes:last/pos"));
    }

    @Covers(value = NamespacedKey.class, method = "fromString")
    @ParameterizedTest
    @ValueSource(strings = {"nokey", ":key", "ns:", "NS:key", "ns:Key", "ns:a:b", "a/b:key"})
    void fromStringGivesNullForWhatIsntAKey(String text) {
        assertNull(NamespacedKey.fromString(text));
    }

    @Covers(value = NamespacedKey.class, method = "fromString")
    @Test
    void fromStringRefusesNull() {
        assertThrows(NullPointerException.class, () -> NamespacedKey.fromString(null));
    }

    @Test
    void parseGivesNullRatherThanThrowing() {
        assertNull(NamespacedKey.parse("Bad", "key"));
        assertEquals(new NamespacedKey("ok", "key"), NamespacedKey.parse("ok", "key"));
    }
    @Covers(value = NamespacedKey.class, method = "equals")
    @Covers(value = NamespacedKey.class, method = "hashCode")
    @Test
    void equalKeysAreEqualAndHashAlike() {
        assertEquals(new NamespacedKey("a", "b"), new NamespacedKey("a", "b"));
        assertEquals(new NamespacedKey("a", "b").hashCode(), new NamespacedKey("a", "b").hashCode());
        assertNotEquals(new NamespacedKey("a", "b"), new NamespacedKey("b", "a"));
        assertNotEquals(new NamespacedKey("a", "bc"), new NamespacedKey("ab", "c"));
        assertNotEquals(new NamespacedKey("a", "b").hashCode(), new NamespacedKey("a", "c").hashCode());
        assertNotEquals(new NamespacedKey("a", "b"), "a:b", "a key never equals its text");
    }
}
