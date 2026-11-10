package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
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

    /**
     * What a generated chunk holds: its blocks counted, one column read bottom to top, and its biomes sampled. Built from
     * the chunk's terrain status, so it reads what the generator made without generating the neighbours' features.
     */
    private static final class Terrain {
        private final ChunkAccess chunk;
        private final Map<Block, Integer> counts = new HashMap<>();
        private final List<Holder<Biome>> biomes = new ArrayList<>();

        Terrain(ChunkAccess chunk) {
            this.chunk = chunk;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        counts.merge(chunk.getBlockState(pos.set(x, y, z)).getBlock(), 1, Integer::sum);
                    }
                }
            }
            // Y 40 as a quart: the nether's biomes change with height, the others don't matter at one.
            for (int qx = 0; qx < 4; qx++) for (int qz = 0; qz < 4; qz++) biomes.add(chunk.getNoiseBiome(qx, 10, qz));
        }

        int count(Block... blocks) {
            int total = 0;
            for (Block block : blocks) total += counts.getOrDefault(block, 0);
            return total;
        }

        /** The solid blocks of one column, bottom to top. */
        List<Block> column(int x, int z) {
            List<Block> blocks = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                Block block = chunk.getBlockState(pos.set(x, y, z)).getBlock();
                if (block != Blocks.AIR) blocks.add(block);
            }
            return blocks;
        }

        boolean everyBiomeIs(ResourceKey<Biome> key) {
            return biomes.stream().allMatch(b -> b.is(key));
        }

        boolean everyBiomeIs(TagKey<Biome> tag) {
            return biomes.stream().allMatch(b -> b.is(tag));
        }

        /** Ordinary overworld rock and nothing of the nether or the end. */
        boolean isOverworldRock() {
            return count(Blocks.STONE, Blocks.DEEPSLATE) > 8000 && count(Blocks.NETHERRACK, Blocks.END_STONE) == 0;
        }

        String describe() {
            String common = counts.entrySet().stream().filter(e -> e.getKey() != Blocks.AIR)
                    .sorted(Map.Entry.<Block, Integer>comparingByValue().reversed()).limit(5)
                    .map(e -> BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath() + " x" + e.getValue())
                    .collect(Collectors.joining(", "));
            List<Block> column = column(0, 0);
            return "the most common blocks " + common + ", the column at 0, 0 (bottom to top, first 6 and last 3 of "
                    + column.size() + ") " + column.subList(0, Math.min(6, column.size())).stream().map(b ->
                    BuiltInRegistries.BLOCK.getKey(b).getPath()).toList() + " ... " + column.subList(Math.max(0,
                    column.size() - 3), column.size()).stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).getPath())
                    .toList() + ", and the biome at 40 high " + biomes.get(0).unwrapKey().map(k -> k.location().getPath())
                    .orElse("?");
        }
    }

    /** Makes a world with the generator, generates its chunk at 0, 0 and holds what is there to {@code ok}. */
    private static void check(TestContext t, String name, WorldGenerator generator, String expected,
            Predicate<Terrain> ok) {
        ResourceLocation key = key("gen_" + name);
        try {
            ServerLevel level = Worlds.create(new WorldCreator(key).generator(generator).seed(7)
                    .spawn(new BlockPos(0, 80, 0)));
            Terrain terrain = new Terrain(level.getChunk(0, 0, ChunkStatus.SURFACE));
            t.assertTrue(ok.test(terrain), name + " should make " + expected + ", but its chunk at 0, 0 holds "
                    + terrain.describe());
        } finally {
            if (Worlds.isCreated(key)) Worlds.delete(key);
        }
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
        check(t, "flat", WorldGenerator.flat(), "classic superflat: bedrock, two dirt, grass",
                g -> g.column(0, 0).equals(List.of(Blocks.BEDROCK, Blocks.DIRT, Blocks.DIRT, Blocks.GRASS_BLOCK)));
        check(t, "flat_preset", WorldGenerator.flat(FlatLevelGeneratorPresets.SNOWY_KINGDOM),
                "the snowy kingdom preset: bedrock under stone, grass and a layer of snow", g -> {
                    List<Block> column = g.column(0, 0);
                    return column.size() > 50 && column.getFirst() == Blocks.BEDROCK
                            && column.subList(column.size() - 2, column.size()).equals(List.of(Blocks.GRASS_BLOCK,
                            Blocks.SNOW));
                });
        check(t, "flat_layers", WorldGenerator.flat(List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
                new FlatLayerInfo(3, Blocks.SANDSTONE)), Biomes.DESERT), "bedrock and three sandstone in the desert",
                g -> g.column(0, 0).equals(List.of(Blocks.BEDROCK, Blocks.SANDSTONE, Blocks.SANDSTONE,
                        Blocks.SANDSTONE)) && g.everyBiomeIs(Biomes.DESERT));
        // The test server's overworld is itself a flat world, so a copy that quietly fell back to superflat would match
        // it; the nether's terrain shares nothing with superflat.
        List<Block> overworldColumn = new Terrain(t.server().overworld().getChunk(0, 0, ChunkStatus.SURFACE))
                .column(0, 0);
        check(t, "copy", WorldGenerator.copyOf(Level.OVERWORLD), "the overworld's own column " + overworldColumn,
                g -> g.column(0, 0).equals(overworldColumn));
        check(t, "copy_nether", WorldGenerator.copyOf(Level.NETHER), "the nether's terrain: netherrack",
                g -> g.count(Blocks.NETHERRACK) > 4000 && g.everyBiomeIs(BiomeTags.IS_NETHER));
        check(t, "noise_overworld", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.overworld()), "overworld rock under the overworld's biomes",
                g -> g.isOverworldRock() && !g.everyBiomeIs(BiomeTags.IS_NETHER));
        check(t, "noise_nether", WorldGenerator.noise(NoiseGeneratorSettings.NETHER,
                WorldGenerator.NoiseBiomes.nether()), "netherrack under the nether's biomes",
                g -> g.count(Blocks.NETHERRACK) > 4000 && g.count(Blocks.STONE) == 0
                        && g.everyBiomeIs(BiomeTags.IS_NETHER));
        check(t, "noise_preset", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.multiNoise(MultiNoiseBiomeSourceParameterLists.NETHER)),
                "overworld rock under a multi-noise preset's (the nether's) biomes",
                g -> g.isOverworldRock() && g.everyBiomeIs(BiomeTags.IS_NETHER));
        check(t, "noise_single", WorldGenerator.noise(NoiseGeneratorSettings.OVERWORLD,
                WorldGenerator.NoiseBiomes.single(Biomes.JUNGLE)), "overworld rock in one biome, the jungle",
                g -> g.isOverworldRock() && g.everyBiomeIs(Biomes.JUNGLE));
        check(t, "noise_end", WorldGenerator.noise(NoiseGeneratorSettings.END, WorldGenerator.NoiseBiomes.end()),
                "the end's islands: end stone in the end's biome",
                g -> g.count(Blocks.END_STONE) > 200 && g.count(Blocks.STONE) == 0 && g.everyBiomeIs(Biomes.THE_END));
    }
}
