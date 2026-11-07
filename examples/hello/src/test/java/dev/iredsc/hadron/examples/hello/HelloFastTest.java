package dev.iredsc.hadron.examples.hello;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.plugin.PluginDescription;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Fast tests: plain JUnit, run by {@code ./gradlew test} in about a second with no server. Use them for what the
 * plugin decides (its plugin.yml, prices, rules, parsing); use {@code @HadronTest}s (see {@link HelloTests}) for what
 * happens in the world. Put {@code @WithMinecraft} (from test-api) on a class whose tests build {@code ItemStack}s,
 * {@code BlockState}s or anything else from Minecraft's registries.
 */
class HelloFastTest {
    /** The plugin.yml the build packs, read with Hadron's own checks: a mistake fails here, not at server start. */
    private static PluginDescription pluginYml() throws Exception {
        try (InputStream in = HelloFastTest.class.getResourceAsStream("/plugin.yml")) {
            Map<?, ?> yaml = new Yaml().load(in);
            return PluginDescription.parse(yaml);
        }
    }

    @Test
    void pluginYmlPassesHadronsChecks() throws Exception {
        // COVERS: HelloPlugin
        PluginDescription description = pluginYml();
        assertEquals("Hello", description.getName());
        assertEquals(HelloPlugin.class.getName(), description.getMain());
        assertTrue(description.getMinecraft().matches("\\d+\\.\\d+(\\.\\d+)?"),
                "the build fills in the Minecraft version: " + description.getMinecraft());
        assertEquals(List.of("hi"), description.getCommands().get("hello").aliases);
    }

    @Test
    void aHelloEventStartsUncancelled() {
        // COVERS: HelloEvent
        HelloEvent event = new HelloEvent("world");
        assertEquals("world", event.getWho());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
