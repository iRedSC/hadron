package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;

/** WorldCreator's settings and every generator, each checked on a world made with it. */
public final class WorldApiTests {
    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("selftest", path);
    }

    @HadronTest(timeout = 900)
    @Covers(value = WorldCreator.class, method = "name")
    @Covers(value = WorldCreator.class, method = "type")
    @Covers(value = WorldCreator.class, method = "hasType")
    @Covers(value = WorldCreator.class, method = "seed")
    @Covers(value = WorldCreator.class, method = "keepSpawnLoaded")
    @Covers(value = WorldCreator.class, method = "spawn")
    @Covers(value = WorldCreator.class, method = "generator")
    @Covers(value = WorldCreator.class, method = "key")
    @Covers(value = WorldCreator.class, method = "createWorld")
    @Covers(value = Worlds.class, method = "created")
    @Covers(value = Worlds.class, method = "list")
    @Covers(value = Worlds.class, method = "delete")
    void aCreatorsSettingsAreTheWorldsAndItIsListed(TestContext t) {
        ResourceLocation key = key("settings");
        WorldCreator creator = WorldCreator.name(key).type(BuiltinDimensionTypes.NETHER).seed(42)
                .spawn(new BlockPos(0, 70, 0)).keepSpawnLoaded(false).generator(WorldGenerator.voidWorld());
        t.assertEquals(key, creator.key(), "the creator's key");
        t.assertTrue(creator.hasType() && creator.type() == BuiltinDimensionTypes.NETHER, "the type set");
        t.assertFalse(new WorldCreator(key).hasType(), "a creator without a type");
        t.assertEquals(BuiltinDimensionTypes.OVERWORLD, new WorldCreator(key).type(), "the default type");
        t.assertEquals(OptionalLong.of(42), creator.seed(), "the seed set");
        t.assertEquals(Optional.of(new BlockPos(0, 70, 0)), creator.spawn(), "the spawn set");
        t.assertFalse(creator.keepSpawnLoaded(), "keepSpawnLoaded as set");
        t.assertTrue(creator.generator().orElse(null) instanceof WorldGenerator.VoidWorld, "the generator set");
        try {
            ServerLevel level = creator.createWorld();
            t.assertTrue(level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER), "the world's type "
                    + "should be the nether's, but is " + level.dimensionTypeRegistration());
            t.assertEquals(42L, level.getSeed(), "the world's own seed");
            t.assertEquals(new BlockPos(0, 70, 0), level.getSharedSpawnPos(), "the world's spawn");
            WorldCreator listed = Worlds.created().stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
            t.assertTrue(listed != null && listed.seed().equals(OptionalLong.of(42)), "created() should list the "
                    + "world with its seed, but listed " + listed);
            List<ServerLevel> loaded = Worlds.list();
            t.assertTrue(loaded.get(0) == t.server().overworld(), "list starts with the overworld");
            t.assertTrue(loaded.contains(level) && loaded.contains(t.level()), "list has every loaded world");
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
        t.assertTrue(Worlds.created().stream().noneMatch(c -> c.key().equals(key)), "a deleted world isn't listed");
    }

    @HadronTest
    @Covers(value = WorldCreator.class, method = "checkedKey")
    void savedIdsAreCheckedLikeNewOnes(TestContext t) {
        t.assertEquals(key("fine"), WorldCreator.checkedKey(key("fine")), "a valid id comes back");
        try {
            WorldCreator.checkedKey(key("maps/arena"));
            t.fail("a nested id should be refused");
        } catch (IllegalArgumentException e) {
            t.assertTrue(e.getMessage().contains("selftest:maps_arena"), "the refusal should suggest the fix, but "
                    + "said: " + e.getMessage());
        }
    }

    private static void check(TestContext t, String name, WorldGenerator generator, Predicate<ChunkGenerator> ok,
            String expected) {
        ResourceLocation key = key("gen_" + name);
        try {
            ServerLevel level = Worlds.create(new WorldCreator(key).generator(generator).spawn(new BlockPos(0, 80, 0)));
            ChunkGenerator made = level.getChunkSource().getGenerator();
            t.assertTrue(ok.test(made), name + " should make " + expected + ", but made " + made.getClass().getSimpleName()
                    + " with " + made.getBiomeSource().getClass().getSimpleName());
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
    }

    private static boolean noise(ChunkGenerator g, Class<?> biomes) {
        return g instanceof NoiseBasedChunkGenerator && biomes.isInstance(g.getBiomeSource());
    }

    @HadronTest(timeout = 1500)
    @Covers(value = WorldGenerator.class, method = "flat")
    @Covers(value = WorldGenerator.class, method = "copyOf")
    @Covers(value = WorldGenerator.class, method = "noise")
    @Covers(value = WorldGenerator.NoiseBiomes.class, method = "overworld")
    @Covers(value = WorldGenerator.NoiseBiomes.class, method = "nether")
    @Covers(value = WorldGenerator.NoiseBiomes.class, method = "multiNoise")
    @Covers(value = WorldGenerator.NoiseBiomes.class, method = "single")
    @Covers(value = WorldGenerator.NoiseBiomes.class, method = "end")
    void everyGeneratorMakesTheTerrainItNames(TestContext t) {
        check(t, "flat", WorldGenerator.flat(), g -> g instanceof FlatLevelSource f
                && f.settings().getLayers().stream().anyMatch(s -> s.is(Blocks.GRASS_BLOCK)), "classic superflat");
        check(t, "flat_preset", WorldGenerator.flat(FlatLevelGeneratorPresets.SNOWY_KINGDOM), g ->
                g instanceof FlatLevelSource f && f.settings().getLayers().stream().anyMatch(s -> s.is(Blocks.SNOW)),
                "the snowy kingdom preset");
        check(t, "flat_layers", WorldGenerator.flat(List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
                new FlatLayerInfo(3, Blocks.SANDSTONE)), Biomes.DESERT), g -> g instanceof FlatLevelSource f
                && f.settings().getLayers().stream().anyMatch(s -> s.is(Blocks.SANDSTONE))
                && f.getBiomeSource().possibleBiomes().stream().allMatch(b -> b.is(Biomes.DESERT)),
                "bedrock and sandstone in the desert");
        Class<?> overworld = t.server().overworld().getChunkSource().getGenerator().getClass();
        check(t, "copy", WorldGenerator.copyOf(Level.OVERWORLD), g -> g.getClass() == overworld,
                "the overworld's generator");
        check(t, "noise_overworld", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.overworld()), g -> noise(g, MultiNoiseBiomeSource.class)
                && g.getBiomeSource().possibleBiomes().stream().anyMatch(b -> b.is(Biomes.PLAINS)),
                "noise terrain with the overworld's biomes");
        check(t, "noise_nether", WorldGenerator.noise(NoiseGeneratorSettings.NETHER,
                WorldGenerator.NoiseBiomes.nether()), g -> noise(g, MultiNoiseBiomeSource.class)
                && g.getBiomeSource().possibleBiomes().stream().anyMatch(b -> b.is(Biomes.CRIMSON_FOREST)),
                "noise terrain with the nether's biomes");
        check(t, "noise_preset", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.multiNoise(MultiNoiseBiomeSourceParameterLists.NETHER)),
                g -> noise(g, MultiNoiseBiomeSource.class)
                        && g.getBiomeSource().possibleBiomes().stream().anyMatch(b -> b.is(Biomes.SOUL_SAND_VALLEY)),
                "a multi-noise preset's biomes");
        check(t, "noise_single", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.single(Biomes.JUNGLE)), g -> noise(g, FixedBiomeSource.class)
                && g.getBiomeSource().possibleBiomes().stream().allMatch(b -> b.is(Biomes.JUNGLE)), "one biome");
        check(t, "noise_end", WorldGenerator.noise(NoiseGeneratorSettings.END, WorldGenerator.NoiseBiomes.end()),
                g -> noise(g, TheEndBiomeSource.class), "the end's islands");
    }
}
