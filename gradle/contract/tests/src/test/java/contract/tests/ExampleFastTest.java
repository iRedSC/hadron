package contract.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.test.Covers;
import org.junit.jupiter.api.Test;

/**
 * An example of the fast tier: plain JUnit, run by {@code ./gradlew test} with no server. Use it for what Hadron decides
 * without a world (parsing, rules, values). Put {@code @WithMinecraft} on a class whose tests need Minecraft's
 * registries ({@code ItemStack}, {@code BlockState}). Every expectation here comes from NamespacedKey's Javadoc.
 */
class ExampleFastTest {
    @Test
    @Covers(value = NamespacedKey.class, method = "fromString")
    void aKeyParsesFromItsStringForm() {
        NamespacedKey key = NamespacedKey.fromString("myplugin:owner");
        assertEquals("myplugin", key.getNamespace());
        assertEquals("owner", key.getKey());
    }

    @Test
    @Covers(value = NamespacedKey.class, method = "fromString")
    void aStringThatIsntAKeyParsesToNull() {
        // "Returns null when it isn't one, as Bukkit does."
        assertNull(NamespacedKey.fromString("no colon here"));
    }
}
