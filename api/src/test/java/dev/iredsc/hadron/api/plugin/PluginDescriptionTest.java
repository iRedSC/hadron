package dev.iredsc.hadron.api.plugin;

import dev.iredsc.hadron.test.Covers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.Default;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

/** plugin.yml checks: every mistake fails the load with a message naming the key and the fix. */
class PluginDescriptionTest {
    private static Map<String, Object> minimal() {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("name", "Homes");
        yaml.put("version", "1.0");
        yaml.put("main", "com.example.Homes");
        yaml.put("minecraft", "1.21.1");
        return yaml;
    }

    private static PluginDescription parse(String text) {
        return PluginDescription.parse(new Yaml().load(text));
    }

    private static String refusal(Map<String, Object> yaml) {
        return assertThrows(PluginException.class, () -> PluginDescription.parse(yaml)).getMessage();
    }

    @Covers(value = PluginDescription.class, method = "parse")
    @Test
    void aMinimalPluginYmlParsesWithEmptyDefaults() {
        PluginDescription d = PluginDescription.parse(minimal());
        assertEquals("Homes", d.getName());
        assertEquals("1.0", d.getVersion());
        assertEquals("com.example.Homes", d.getMain());
        assertEquals("1.21.1", d.getMinecraft());
        assertEquals("", d.getDescription());
        assertEquals("", d.getWebsite());
        assertEquals(List.of(), d.getAuthors());
        assertEquals(List.of(), d.getDepend());
        assertEquals(List.of(), d.getSoftDepend());
        assertEquals(List.of(), d.getModDepend());
        assertNull(d.getLoader());
        assertTrue(d.getCommands().isEmpty());
        assertTrue(d.getPermissions().isEmpty());
        assertEquals("Homes v1.0", d.getFullName());
    }

    @Covers(value = PluginDescription.class, method = "parse")
    @ParameterizedTest
    @ValueSource(strings = {"name", "version", "main"})
    void eachRequiredKeyIsNamedWhenMissing(String key) {
        Map<String, Object> yaml = minimal();
        yaml.remove(key);
        assertEquals("plugin.yml needs '" + key + "'", refusal(yaml));
        yaml.put(key, "");
        assertEquals("plugin.yml needs '" + key + "'", refusal(yaml));
    }

    @Covers(value = PluginDescription.class, method = "parse")
    @Test
    void aMissingMinecraftVersionSaysHowToFillItIn() {
        Map<String, Object> yaml = minimal();
        yaml.remove("minecraft");
        String message = refusal(yaml);
        assertTrue(message.contains("minecraft: ${minecraft_version}"), message);
    }

    @Covers(value = PluginDescription.class, method = "parse")
    @ParameterizedTest
    @ValueSource(strings = {"My Plugin", "a/b", "ü", "x:y"})
    void aNameWithOtherCharactersIsRefused(String name) {
        Map<String, Object> yaml = minimal();
        yaml.put("name", name);
        assertTrue(refusal(yaml).contains("may only use letters, digits"));
    }

    @Covers(value = PluginDescription.class, method = "getVersion")
    @Test
    void anUnquotedVersionIsRefusedSinceYamlWouldHaveChangedIt() {
        // YAML reads 1.10 as the number 1.1, so an unquoted version can't be trusted: refuse it with the fix.
        PluginException e = assertThrows(PluginException.class,
                () -> parse("name: Homes\nversion: 1.10\nmain: a.B\nminecraft: \"1.21\"\n"));
        assertTrue(e.getMessage().contains("must be quoted") && e.getMessage().contains("version: \"1.1\""),
                e.getMessage());
        assertThrows(PluginException.class, () -> parse("name: Homes\nversion: \"1.10\"\nmain: a.B\nminecraft: 1.21\n"));
        PluginDescription d = parse("name: Homes\nversion: \"1.10\"\nmain: a.B\nminecraft: \"1.21\"\n");
        assertEquals("1.10", d.getVersion());
        assertEquals("1.21", d.getMinecraft());
    }

    @Covers(value = PluginDescription.class, method = "parse")
    @Test
    void aSingleValueKeyGivenAListIsRefused() {
        Map<String, Object> yaml = minimal();
        yaml.put("description", List.of("a", "b"));
        assertEquals("plugin.yml 'description' must be a single value", refusal(yaml));
    }

    @Covers(value = PluginDescription.class, method = "getAuthors")
    @Test
    void authorComesBeforeAuthors() {
        Map<String, Object> yaml = minimal();
        yaml.put("author", "Ann");
        yaml.put("authors", List.of("Bo", "Cy"));
        assertEquals(List.of("Ann", "Bo", "Cy"), PluginDescription.parse(yaml).getAuthors());
    }

    @Covers(value = PluginDescription.class, method = "getDepend")
    @Covers(value = PluginDescription.class, method = "getSoftDepend")
    @Test
    void aListKeyTakesOneValueOrAListButNotAMap() {
        Map<String, Object> yaml = minimal();
        yaml.put("depend", "Vault");
        yaml.put("softdepend", List.of("A", "B"));
        PluginDescription d = PluginDescription.parse(yaml);
        assertEquals(List.of("Vault"), d.getDepend());
        assertEquals(List.of("A", "B"), d.getSoftDepend());
        yaml.put("depend", Map.of("x", 1));
        assertEquals("plugin.yml 'depend' must be a list", refusal(yaml));
    }

    @Covers(value = PluginDescription.class, method = "getModDepend")
    @Covers(value = PluginDescription.class, method = "getLoader")
    @Test
    void modDependNeedsALoader() {
        Map<String, Object> yaml = minimal();
        yaml.put("mod-depend", List.of("ae2"));
        assertTrue(refusal(yaml).contains("loader: neoforge"));
        yaml.put("loader", "neoforge");
        PluginDescription d = PluginDescription.parse(yaml);
        assertEquals(List.of("ae2"), d.getModDepend());
        assertEquals("neoforge", d.getLoader());
    }

    @Covers(value = PluginDescription.class, method = "getCommands")
    @Test
    void commandsAreLowercasedWithUsageAndAliases() {
        PluginDescription d = parse("""
                name: Homes
                version: '1'
                main: a.B
                minecraft: 1.21.1
                commands:
                  Home:
                    description: Go home
                    usage: /<command> [name]
                    aliases: [H, Hm]
                    permission: homes.use
                  sethome:
                """);
        PluginDescription.CommandSpec home = d.getCommands().get("home");
        assertEquals("home", home.name);
        assertEquals("Go home", home.description);
        assertEquals("/home [name]", home.usage);
        assertEquals(List.of("h", "hm"), home.aliases);
        assertEquals("homes.use", home.permission);
        PluginDescription.CommandSpec bare = d.getCommands().get("sethome");
        assertEquals("/sethome", bare.usage);
        assertNull(bare.permission);
        assertEquals(List.of("home", "sethome"), List.copyOf(d.getCommands().keySet()));
    }

    @Covers(value = PluginDescription.class, method = "getCommands")
    @Test
    void permissionMessageIsRefusedBecauseItNeverShows() {
        Map<String, Object> yaml = minimal();
        yaml.put("commands", Map.of("home", Map.of("permission-message", "no")));
        assertTrue(refusal(yaml).contains("Remove the line"));
    }

    @Covers(value = PluginDescription.class, method = "getCommands")
    @Test
    void commandsMustBeAMapOfMaps() {
        Map<String, Object> yaml = minimal();
        yaml.put("commands", List.of("home"));
        assertEquals("plugin.yml 'commands' must be a map of names", refusal(yaml));
        yaml.put("commands", Map.of("home", "text"));
        assertEquals("plugin.yml 'commands.home' must be a map", refusal(yaml));
    }

    @Covers(value = PluginDescription.class, method = "getPermissions")
    @Test
    void permissionDefaultsMapToWhoHasThem() {
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("homes.a", Map.of("default", true));
        permissions.put("homes.b", Map.of("default", false));
        permissions.put("homes.c", Map.of("default", "OP", "description", "C"));
        permissions.put("homes.d", null);
        Map<String, Object> yaml = minimal();
        yaml.put("permissions", permissions);
        List<PluginDescription.PermissionSpec> specs = PluginDescription.parse(yaml).getPermissions();
        assertEquals(Default.EVERYONE, specs.get(0).fallback);
        assertEquals(Default.NOBODY, specs.get(1).fallback);
        assertEquals(Default.OPS, specs.get(2).fallback);
        assertEquals("C", specs.get(2).description);
        assertEquals(Default.OPS, specs.get(3).fallback); // op unless it says otherwise, as in Bukkit
        assertEquals("homes.d", specs.get(3).node);
    }

    @Covers(value = PluginDescription.class, method = "getPermissions")
    @Test
    void anUnknownPermissionDefaultIsRefusedListingTheChoices() {
        Map<String, Object> yaml = minimal();
        yaml.put("permissions", Map.of("homes.a", Map.of("default", "notop")));
        assertTrue(refusal(yaml).contains("use true, false or op"));
    }

    @Covers(value = PluginDescription.class, method = "getPermissions")
    @Test
    void permissionChildrenAreRefusedPointingAtWildcards() {
        Map<String, Object> yaml = minimal();
        yaml.put("permissions", Map.of("homes.admin", Map.of("children", Map.of("homes.a", true))));
        assertTrue(refusal(yaml).contains("homes.*"));
    }

    @Covers(value = PluginDescription.class, method = "getDepend")
    @Test
    void theParsedListsCantBeChanged() {
        Map<String, Object> yaml = new HashMap<>(minimal());
        yaml.put("depend", List.of("A"));
        PluginDescription d = PluginDescription.parse(yaml);
        assertThrows(UnsupportedOperationException.class, () -> d.getDepend().add("B"));
        assertThrows(UnsupportedOperationException.class, () -> d.getCommands().clear());
    }
}
