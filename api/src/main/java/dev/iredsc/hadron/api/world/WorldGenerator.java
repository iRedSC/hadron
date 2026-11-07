package dev.iredsc.hadron.api.world;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPreset;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;

/**
 * How a created world makes its terrain, for {@link WorldCreator#generator(WorldGenerator)}. Every choice names
 * something the server already has (a dimension, a flat preset, noise settings, biomes), so it is checked when the
 * world is created and comes back the same after a restart.
 *
 * <pre>{@code
 * WorldGenerator.copyOf(Level.NETHER)                       // terrain like the nether, with the world's own seed
 * WorldGenerator.flat()                                     // a superflat world (grass, dirt, bedrock)
 * WorldGenerator.flat(FlatLevelGeneratorPresets.DESERT)     // any flat preset, a datapack's too
 * WorldGenerator.flat(List.of(new FlatLayerInfo(1, Blocks.BEDROCK), new FlatLayerInfo(60, Blocks.STONE)), Biomes.PLAINS)
 * WorldGenerator.voidWorld()                                // nothing at all
 * WorldGenerator.noise(NoiseGeneratorSettings.AMPLIFIED, NoiseBiomes.overworld())
 * }</pre>
 */
public sealed interface WorldGenerator {
    /** The same generator as a loaded dimension (vanilla's or a mod's), with the created world's own seed. */
    static WorldGenerator copyOf(ResourceKey<Level> dimension) {
        return new Copy(dimension);
    }

    /** Vanilla's classic superflat: one bedrock, two dirt, one grass, plains. */
    static WorldGenerator flat() {
        return new FlatPreset(FlatLevelGeneratorPresets.CLASSIC_FLAT);
    }

    /** A flat preset from the server's registry ({@link FlatLevelGeneratorPresets}, or a datapack's). */
    static WorldGenerator flat(ResourceKey<FlatLevelGeneratorPreset> preset) {
        return new FlatPreset(preset);
    }

    /** Flat layers listed bottom first, in one biome, with no structures. */
    static WorldGenerator flat(List<FlatLayerInfo> layers, ResourceKey<Biome> biome) {
        return new FlatLayers(layers, biome);
    }

    /** No blocks at all, in {@code minecraft:the_void}. Spawn defaults to y 64; build a platform there. */
    static WorldGenerator voidWorld() {
        return new VoidWorld(Biomes.THE_VOID);
    }

    /** Vanilla's noise terrain from a noise settings id ({@link NoiseGeneratorSettings#OVERWORLD} and the rest). */
    static WorldGenerator noise(ResourceKey<NoiseGeneratorSettings> settings, NoiseBiomes biomes) {
        return new Noise(settings, biomes);
    }

    record Copy(ResourceKey<Level> dimension) implements WorldGenerator {
        public Copy {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    record FlatPreset(ResourceKey<FlatLevelGeneratorPreset> preset) implements WorldGenerator {
        public FlatPreset {
            Objects.requireNonNull(preset, "preset");
        }
    }

    record FlatLayers(List<FlatLayerInfo> layers, ResourceKey<Biome> biome) implements WorldGenerator {
        public FlatLayers {
            layers = List.copyOf(layers);
            Objects.requireNonNull(biome, "biome");
            if (layers.isEmpty()) {
                throw new IllegalArgumentException(
                        "WorldGenerator.flat needs at least one layer; for an empty world use WorldGenerator.voidWorld()");
            }
        }
    }

    record VoidWorld(ResourceKey<Biome> biome) implements WorldGenerator {
        public VoidWorld {
            Objects.requireNonNull(biome, "biome");
        }
    }

    record Noise(ResourceKey<NoiseGeneratorSettings> settings, NoiseBiomes biomes) implements WorldGenerator {
        public Noise {
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(biomes, "biomes");
        }
    }

    /** Where a {@link #noise} world's biomes come from. */
    sealed interface NoiseBiomes {
        /** The overworld's biome layout. */
        static NoiseBiomes overworld() {
            return new MultiNoise(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        }

        /** The nether's biome layout. */
        static NoiseBiomes nether() {
            return new MultiNoise(MultiNoiseBiomeSourceParameterLists.NETHER);
        }

        /** A biome layout preset from the server's registry, a datapack's or mod's included. */
        static NoiseBiomes multiNoise(ResourceKey<MultiNoiseBiomeSourceParameterList> preset) {
            return new MultiNoise(preset);
        }

        /** One biome everywhere. */
        static NoiseBiomes single(ResourceKey<Biome> biome) {
            return new Single(biome);
        }

        /** The end's islands layout. */
        static NoiseBiomes end() {
            return new End();
        }

        record MultiNoise(ResourceKey<MultiNoiseBiomeSourceParameterList> preset) implements NoiseBiomes {
            public MultiNoise {
                Objects.requireNonNull(preset, "preset");
                if (!preset.isFor(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)) {
                    throw new IllegalArgumentException(preset + " is not a biome layout preset");
                }
            }
        }

        record Single(ResourceKey<Biome> biome) implements NoiseBiomes {
            public Single {
                Objects.requireNonNull(biome, "biome");
            }
        }

        record End() implements NoiseBiomes {}
    }
}
