package dev.iredsc.hadron.regions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/** regions.yml round-trips, and a mistake in it is refused with where it is, never dropped. */
class RegionsFileTest {
    private static Map<String, Region> read(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return RegionsFile.read(yaml);
    }

    @Test
    void aRegionSurvivesSavingAndLoading() throws Exception {
        Region spawn = Region.of("spawn", ResourceLocation.withDefaultNamespace("overworld"),
                        BoundingBox.fromCorners(new BlockPos(-5, -64, 3), new BlockPos(5, 320, -3)))
                .withPriority(4).withOwner(new UUID(1, 2)).withMember(new UUID(3, 4))
                .withFlag(Flag.PVP, false).withFlag(Flag.USE, true);
        Map<String, Region> back = read(RegionsFile.write(List.of(spawn)).saveToString());
        assertEquals(Map.of("spawn", spawn), back);
    }

    @Test
    void anEmptyFileHasNoRegions() throws Exception {
        assertTrue(read("").isEmpty());
    }

    @Test
    void aMistakeNamesWhereItIs() {
        String text = """
                regions:
                  spawn:
                    world: minecraft:overworld
                    min: [0, 0, 0]
                    max: [1, 1]
                """;
        RegionsFile.Problem p = assertThrows(RegionsFile.Problem.class, () -> read(text));
        assertTrue(p.getMessage().contains("region spawn, max"), p.getMessage());
    }

    @Test
    void anUnknownFlagIsRefused() {
        String text = """
                regions:
                  spawn: {world: 'minecraft:overworld', min: [0, 0, 0], max: [1, 1, 1], flags: {fly: deny}}
                """;
        RegionsFile.Problem p = assertThrows(RegionsFile.Problem.class, () -> read(text));
        assertTrue(p.getMessage().contains("build, use, entry, pvp"), p.getMessage());
    }

    @Test
    void aNewerFormatIsRefused() {
        assertThrows(RegionsFile.Problem.class, () -> read("format: 2\n"));
    }

    @Test
    void pluginYmlPassesHadronsChecks() throws Exception {
        try (InputStream in = RegionsFileTest.class.getResourceAsStream("/plugin.yml")) {
            Map<?, ?> yaml = new Yaml().load(in);
            PluginDescription d = PluginDescription.parse(yaml);
            assertEquals(RegionsPlugin.class.getName(), d.getMain());
            assertEquals(List.of("rg"), d.getCommands().get("region").aliases);
        }
    }

    @Test
    void bypassSurvivesSavingAndLoading() throws Exception {
        java.util.Set<UUID> on = java.util.Set.of(new UUID(5, 6), new UUID(7, 8));
        YamlConfiguration back = new YamlConfiguration();
        back.loadFromString(Bypass.write(on).saveToString());
        assertEquals(on, Bypass.read(back));
    }

    @Test
    void aBadBypassLineIsRefused() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("players: [notauuid]\n");
        assertThrows(RegionsFile.Problem.class, () -> Bypass.read(yaml));
    }

    private static void refused(String region, String expect) {
        String text = "regions:\n  spawn: {world: 'minecraft:overworld', min: [0, 0, 0], max: [1, 1, 1], " + region + "}\n";
        RegionsFile.Problem p = assertThrows(RegionsFile.Problem.class, () -> read(text), region);
        assertTrue(p.getMessage().contains(expect), p.getMessage());
    }

    @Test
    void aWronglyTypedFieldIsRefusedNotDropped() {
        refused("flags: [{pvp: deny}]", "flags");
        refused("owners: 5f2b", "owners");
        refused("members: [12]", "members");
        refused("priority: 4294967296", "priority");
        refused("owner: []", "owner");
    }

    /** Loads a hand-edited file the way the plugin does, and checks it is refused, with the key named, and kept. */
    private static void refusedOnDisk(Path dir, String name, String text, boolean regions, String... expect)
            throws Exception {
        File file = dir.resolve(name).toFile();
        byte[] before = text.getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), before);
        Executable load = regions
                ? () -> RegionsFile.read(RegionsFile.open(file))
                : () -> Bypass.read(RegionsFile.open(file));
        RegionsFile.Problem p = assertThrows(RegionsFile.Problem.class, load, text);
        for (String part : expect) assertTrue(p.getMessage().contains(part), p.getMessage());
        assertArrayEquals(before, Files.readAllBytes(file.toPath()), name + " changed on disk");
        assertEquals(List.of(name), List.of(dir.toFile().list()), "only the file itself is left beside it");
    }

    @Test
    void aRepeatedRegionIdIsRefused(@TempDir Path dir) throws Exception {
        refusedOnDisk(dir, "regions.yml", """
                format: 1
                regions:
                  spawn: {world: 'minecraft:overworld', min: [0, 0, 0], max: [1, 1, 1]}
                  market: {world: 'minecraft:overworld', min: [5, 0, 5], max: [9, 9, 9]}
                  spawn: {world: 'minecraft:the_nether', min: [0, 0, 0], max: [1, 1, 1]}
                """, true, "regions.yml, line ", "\"spawn\" is written twice", "Rename or remove one");
    }

    @Test
    void aRepeatedKeyInsideARegionIsRefused(@TempDir Path dir) throws Exception {
        refusedOnDisk(dir, "regions.yml", """
                regions:
                  spawn:
                    world: minecraft:overworld
                    min: [0, 0, 0]
                    max: [1, 1, 1]
                    members: [00000000-0000-0001-0000-000000000002]
                    members: []
                """, true, "regions.yml, line ", "\"members\" is written twice", "Rename or remove one");
    }

    @Test
    void keysTheConfigWouldFoldIntoOneAreRefused(@TempDir Path dir) throws Exception {
        String spawn = "{world: 'minecraft:overworld', min: [0, 0, 0], max: [1, 1, 1]}";
        refusedOnDisk(dir, "regions.yml", "regions:\n  7: " + spawn + "\n  '7': " + spawn + "\n", true,
                "under regions: \"7\" is written twice");
        refusedOnDisk(dir, "regions.yml", "regions:\n  spawn: " + spawn + "\nregions.spawn: {world: 'minecraft:the_end', "
                + "min: [0, 0, 0], max: [1, 1, 1]}\n", true, "\"regions.spawn\" has a dot in it", "Remove the dot");
    }

    @Test
    void anUnknownKeyInBypassIsRefused(@TempDir Path dir) throws Exception {
        refusedOnDisk(dir, "bypass.yml", "player:\n- 00000000-0000-0005-0000-000000000006\n", false,
                "\"player\" isn't a setting", "only players");
        refusedOnDisk(dir, "bypass.yml", "players: []\nplayers:\n- 00000000-0000-0005-0000-000000000006\n", false,
                "\"players\" is written twice");
    }

    @Test
    void anUnknownKeyInsideARegionIsRefused(@TempDir Path dir) throws Exception {
        refusedOnDisk(dir, "regions.yml", """
                regions:
                  spawn:
                    world: minecraft:overworld
                    min: [0, 0, 0]
                    max: [1, 1, 1]
                    member: [00000000-0000-0001-0000-000000000002]
                """, true, "region spawn, member: isn't a region setting", "owners, members and flags");
        refusedOnDisk(dir, "regions.yml", "region: {}\n", true, "\"region\" isn't a setting", "format and regions");
    }
}
