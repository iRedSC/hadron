package dev.iredsc.hadron.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.test.Covers;
import org.junit.jupiter.api.Test;

class JavaPluginTextTest {
    @Covers(value = JavaPlugin.class, method = "toString")
    @Test
    void aPluginReadsAsItsNameAndVersion() {
        JavaPlugin plugin = TestPlugins.create("Shops", null);
        assertEquals("Shops v1", plugin.toString(), "as getDescription().getFullName(), for logs");
    }
}
