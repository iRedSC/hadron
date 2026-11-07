package dev.iredsc.hadron.selftest.config;

import dev.iredsc.hadron.api.config.ConfigurationSection;
import dev.iredsc.hadron.api.config.InvalidConfigurationException;
import dev.iredsc.hadron.api.config.MemorySection;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** config.yml as plugins read and write it, with Bukkit's getters and defaults behind the values. */
public final class ConfigTests {
    private static final String YAML = String.join("\n",
            "name: Shop",
            "open: true",
            "slots: 27",
            "big: 9000000000",
            "price: 2.5",
            "tags: [food, tools]",
            "levels: [1, 2, 3]",
            "weights: [0.5, 1]",
            "items:",
            "  - {id: bread, cost: 2}",
            "nested:",
            "  inner:",
            "    deep: here",
            "");

    private static YamlConfiguration loaded(TestContext t) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(YAML);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("The test's YAML should parse: " + e.getMessage(), e);
        }
        return config;
    }

    @HadronTest
    @Covers(value = YamlConfiguration.class, method = "loadFromString")
    @Covers(value = ConfigurationSection.class, method = "getString")
    @Covers(value = ConfigurationSection.class, method = "isString")
    @Covers(value = ConfigurationSection.class, method = "getInt")
    @Covers(value = ConfigurationSection.class, method = "isInt")
    @Covers(value = ConfigurationSection.class, method = "getLong")
    @Covers(value = ConfigurationSection.class, method = "getDouble")
    @Covers(value = ConfigurationSection.class, method = "isDouble")
    @Covers(value = ConfigurationSection.class, method = "getBoolean")
    @Covers(value = ConfigurationSection.class, method = "isBoolean")
    @Covers(value = ConfigurationSection.class, method = "getList")
    @Covers(value = ConfigurationSection.class, method = "isList")
    @Covers(value = ConfigurationSection.class, method = "getStringList")
    @Covers(value = ConfigurationSection.class, method = "getIntegerList")
    @Covers(value = ConfigurationSection.class, method = "getDoubleList")
    @Covers(value = ConfigurationSection.class, method = "getMapList")
    @Covers(value = ConfigurationSection.class, method = "get")
    void typedGettersReadEachValueAndAWrongTypeCountsAsMissing(TestContext t) {
        YamlConfiguration c = loaded(t);
        t.assertEquals("Shop", c.getString("name"), "getString");
        t.assertEquals("fallback", c.getString("missing", "fallback"), "getString with a default");
        t.assertTrue(c.isString("name") && !c.isString("slots"), "isString");
        t.assertEquals(27, c.getInt("slots"), "getInt");
        t.assertEquals(5, c.getInt("name", 5), "getInt of a string falls back");
        t.assertTrue(c.isInt("slots") && !c.isInt("price"), "isInt");
        t.assertEquals(9000000000L, c.getLong("big"), "getLong");
        t.assertEquals(7L, c.getLong("missing", 7L), "getLong with a default");
        t.assertEquals(2.5, c.getDouble("price"), "getDouble");
        t.assertEquals(27.0, c.getDouble("slots"), "getDouble of an int");
        t.assertEquals(1.5, c.getDouble("missing", 1.5), "getDouble with a default");
        t.assertTrue(c.isDouble("price") && c.isDouble("slots") && !c.isDouble("name"), "isDouble is any number");
        t.assertTrue(c.getBoolean("open"), "getBoolean");
        t.assertTrue(c.getBoolean("missing", true), "getBoolean with a default");
        t.assertTrue(c.isBoolean("open") && !c.isBoolean("slots"), "isBoolean");
        t.assertEquals(List.of("food", "tools"), c.getList("tags"), "getList");
        t.assertEquals(List.of("x"), c.getList("missing", List.of("x")), "getList with a default");
        t.assertTrue(c.isList("tags") && !c.isList("name"), "isList");
        t.assertEquals(List.of("food", "tools"), c.getStringList("tags"), "getStringList");
        t.assertEquals(List.of(), c.getStringList("missing"), "getStringList with no list");
        t.assertEquals(List.of(1, 2, 3), c.getIntegerList("levels"), "getIntegerList");
        t.assertEquals(List.of(0.5, 1.0), c.getDoubleList("weights"), "getDoubleList");
        t.assertEquals("bread", c.getMapList("items").get(0).get("id"), "getMapList");
        t.assertEquals("here", c.get("nested.inner.deep"), "get by a dotted path");
        t.assertEquals("dflt", c.get("missing", "dflt"), "get with a default");
    }

    @HadronTest
    @Covers(value = ConfigurationSection.class, method = "getKeys")
    @Covers(value = ConfigurationSection.class, method = "getValues")
    @Covers(value = ConfigurationSection.class, method = "getConfigurationSection")
    @Covers(value = ConfigurationSection.class, method = "isConfigurationSection")
    @Covers(value = ConfigurationSection.class, method = "getCurrentPath")
    @Covers(value = ConfigurationSection.class, method = "getName")
    @Covers(value = ConfigurationSection.class, method = "getParent")
    @Covers(value = ConfigurationSection.class, method = "createSection")
    @Covers(value = ConfigurationSection.class, method = "set")
    @Covers(value = ConfigurationSection.class, method = "contains")
    @Covers(value = ConfigurationSection.class, method = "isSet")
    void sectionsNestAndSetWritesThroughDottedPaths(TestContext t) {
        YamlConfiguration c = loaded(t);
        t.assertTrue(c.getKeys(false).containsAll(Set.of("name", "nested")) && !c.getKeys(false).contains("nested.inner"),
                "shallow keys");
        t.assertTrue(c.getKeys(true).contains("nested.inner.deep"), "deep keys");
        t.assertTrue(c.getValues(true).containsKey("nested.inner.deep"), "deep values");
        t.assertTrue(c.isConfigurationSection("nested") && !c.isConfigurationSection("name"), "isConfigurationSection");
        ConfigurationSection inner = c.getConfigurationSection("nested.inner");
        t.assertEquals("nested.inner", inner.getCurrentPath(), "a section's path");
        t.assertEquals("inner", inner.getName(), "a section's name");
        t.assertEquals("nested", inner.getParent().getName(), "a section's parent");
        t.assertEquals("", c.getCurrentPath(), "the root's path");
        ConfigurationSection made = c.createSection("homes.alice");
        made.set("x", 10);
        t.assertEquals(10, c.getInt("homes.alice.x"), "a value set in a created section");
        c.set("homes.bob.y", 4);
        t.assertTrue(c.isConfigurationSection("homes.bob"), "set makes the sections on the way");
        c.set("homes.bob", null);
        t.assertFalse(c.contains("homes.bob"), "setting null removes it");
        c.set("map", Map.of("a", 1));
        t.assertTrue(c.isConfigurationSection("map"), "a map becomes a section");
        t.assertTrue(c.isSet("name") && !c.isSet("missing"), "isSet");
    }

    @HadronTest
    @Covers(value = YamlConfiguration.class, method = "setDefaults")
    @Covers(value = YamlConfiguration.class, method = "getDefaults")
    @Covers(value = YamlConfiguration.class, method = "options")
    @Covers(value = YamlConfiguration.Options.class, method = "copyDefaults")
    @Covers(value = YamlConfiguration.class, method = "saveToString")
    @Covers(value = ConfigurationSection.class, method = "addDefault")
    @Covers(value = ConfigurationSection.class, method = "contains")
    @Covers(value = ConfigurationSection.class, method = "isSet")
    void defaultsAnswerForMissingValuesAndCopyDefaultsWritesThem(TestContext t) {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new StringReader("motd: Welcome\nlimit: 3\n"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader("limit: 5\n"));
        config.setDefaults(defaults);
        t.assertTrue(config.getDefaults() == defaults, "getDefaults");
        t.assertEquals("Welcome", config.getString("motd"), "a value only the defaults have");
        t.assertEquals(5, config.getInt("limit"), "the config's own value wins");
        t.assertTrue(config.contains("motd") && !config.isSet("motd"), "contains counts defaults; isSet doesn't");
        config.addDefault("color", "green");
        t.assertEquals("green", config.getString("color"), "a value added with addDefault");
        t.assertFalse(config.saveToString().contains("motd"), "defaults aren't saved unless copyDefaults is on");
        t.assertFalse(config.options().copyDefaults(), "copyDefaults starts off");
        config.options().copyDefaults(true);
        t.assertTrue(config.options().copyDefaults(), "copyDefaults once turned on");
        String saved = config.saveToString();
        t.assertTrue(saved.contains("motd: Welcome") && saved.contains("limit: 5"), "with copyDefaults the defaults "
                + "are written under the config's own values, but saved:\n" + saved);
    }

    @HadronTest
    @Covers(value = YamlConfiguration.class, method = "save")
    @Covers(value = YamlConfiguration.class, method = "load")
    @Covers(value = YamlConfiguration.class, method = "loadConfiguration")
    @Covers(value = YamlConfiguration.class, method = "loadFromString")
    void savedConfigsLoadBackAndBrokenOnesSayWhy(TestContext t) throws Exception {
        File dir = Files.createTempDirectory("hadron-config").toFile();
        try {
            File file = new File(dir, "sub/config.yml");
            YamlConfiguration config = new YamlConfiguration();
            config.set("a.b", "c");
            config.save(file);
            t.assertTrue(file.isFile(), "save should create the file and its folders");
            YamlConfiguration back = new YamlConfiguration();
            back.load(file);
            t.assertEquals("c", back.getString("a.b"), "a value loaded back with load(File)");
            t.assertEquals("c", YamlConfiguration.loadConfiguration(file).getString("a.b"), "loadConfiguration(File)");
            t.assertTrue(YamlConfiguration.loadConfiguration(new File(dir, "none.yml")).getKeys(true).isEmpty(),
                    "a missing file loads as an empty config");
            YamlConfiguration fromReader = new YamlConfiguration();
            fromReader.load(new StringReader("x: 1\n"));
            t.assertEquals(1, fromReader.getInt("x"), "load(Reader)");
            try {
                new YamlConfiguration().loadFromString("- just\n- a list\n");
                t.fail("a list at the top should be refused");
            } catch (InvalidConfigurationException e) {
                t.assertTrue(e.getMessage().contains("keys and values"), "the refusal should say what's wrong, but "
                        + "said: " + e.getMessage());
            }
            t.assertTrue(YamlConfiguration.loadConfiguration(new StringReader("a: [unclosed\n")).getKeys(true).isEmpty(),
                    "malformed YAML loads as an empty config instead of throwing");
        } finally {
            try (var walk = Files.walk(dir.toPath())) {
                for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    /** A section under a root, as a subclass sees it: its defaults are the root defaults' section at its place. */
    private static final class Child extends MemorySection {
        Child(MemorySection parent, String name) {
            super(parent, name);
        }

        ConfigurationSection exposedDefaults() {
            return defaults();
        }
    }

    @HadronTest
    @Covers(value = MemorySection.class, method = "defaults")
    void aSectionsDefaultsAreTheRootDefaultsAtItsPath(TestContext t) {
        YamlConfiguration root = new YamlConfiguration();
        root.setDefaults(YamlConfiguration.loadConfiguration(new StringReader("shop:\n  open: true\n")));
        Child shop = new Child(root, "shop");
        ConfigurationSection defaults = shop.exposedDefaults();
        t.assertTrue(defaults != null && defaults.getBoolean("open"), "the shop section's defaults should be the "
                + "defaults' shop section, but were " + defaults);
        t.assertEquals(null, new Child(root, "nothing").exposedDefaults(), "defaults where the root defaults have none");
    }
}
