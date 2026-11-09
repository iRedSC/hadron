package dev.iredsc.hadron.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.api.plugin.PluginDescription;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;
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
}
