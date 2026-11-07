package dev.iredsc.hadron.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.WorldGenerator.NoiseBiomes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

/** Turns a {@link WorldGenerator} into Minecraft's generator, and into Hadron's saved JSON and back. */
final class WorldsGenerators {
    private WorldsGenerators() {}

    /** The generator a type gets when none is set: a copy of the vanilla dimension with that type. */
    static WorldGenerator defaultFor(ResourceKey<DimensionType> type, ResourceLocation world) {
        if (type.equals(BuiltinDimensionTypes.OVERWORLD)) return WorldGenerator.copyOf(Level.OVERWORLD);
        if (type.equals(BuiltinDimensionTypes.NETHER)) return WorldGenerator.copyOf(Level.NETHER);
        if (type.equals(BuiltinDimensionTypes.END)) return WorldGenerator.copyOf(Level.END);
        throw new IllegalArgumentException("World " + world + " has dimension type " + type.location()
                + ", which has no default generator; set one with .generator(WorldGenerator.copyOf(...)), "
                + ".generator(WorldGenerator.flat()) or another WorldGenerator");
    }

    static Holder<DimensionType> dimensionType(MinecraftServer server, ResourceKey<DimensionType> type) {
        return holder(server, Registries.DIMENSION_TYPE, type, "dimension type");
    }

    static ChunkGenerator build(MinecraftServer server, WorldGenerator generator) {
        return switch (generator) {
            case WorldGenerator.Copy copy -> copy(server, copy.dimension());
            case WorldGenerator.FlatPreset preset -> new FlatLevelSource(
                    holder(server, Registries.FLAT_LEVEL_GENERATOR_PRESET, preset.preset(), "flat preset").value()
                            .settings());
            case WorldGenerator.FlatLayers flat -> {
                HolderSet<net.minecraft.world.level.levelgen.structure.StructureSet> none = HolderSet.direct(List.of());
                Holder<Biome> biome = holder(server, Registries.BIOME, flat.biome(), "biome");
                yield new FlatLevelSource(new FlatLevelGeneratorSettings(Optional.of(none), biome, List.of())
                        .withBiomeAndLayers(flat.layers(), Optional.of(none), biome));
            }
            case WorldGenerator.VoidWorld empty -> new FlatLevelSource(new FlatLevelGeneratorSettings(
                    Optional.of(HolderSet.direct(List.of())), holder(server, Registries.BIOME, empty.biome(), "biome"),
                    List.of()));
            case WorldGenerator.Noise noise -> new NoiseBasedChunkGenerator(biomes(server, noise.biomes()),
                    holder(server, Registries.NOISE_SETTINGS, noise.settings(), "noise settings"));
        };
    }

    private static ChunkGenerator copy(MinecraftServer server, ResourceKey<Level> dimension) {
        // The registered stem first: a dimension's generator as it was defined, even if a mod swapped it at runtime.
        LevelStem stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
                .get(ResourceKey.create(Registries.LEVEL_STEM, dimension.location()));
        if (stem != null) return stem.generator();
        ServerLevel level = server.getLevel(dimension);
        if (level != null) return level.getChunkSource().getGenerator();
        throw new IllegalArgumentException("WorldGenerator.copyOf(" + dimension.location() + "): no such dimension is "
                + "loaded; loaded ones are " + server.levelKeys().stream().map(k -> k.location().toString())
                .sorted().collect(Collectors.joining(", ")));
    }

    private static BiomeSource biomes(MinecraftServer server, NoiseBiomes biomes) {
        return switch (biomes) {
            case NoiseBiomes.MultiNoise multi -> MultiNoiseBiomeSource.createFromPreset(
                    holder(server, Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, multi.preset(), "biome layout"));
            case NoiseBiomes.Single single -> new FixedBiomeSource(
                    holder(server, Registries.BIOME, single.biome(), "biome"));
            case NoiseBiomes.End end -> TheEndBiomeSource.create(server.registryAccess().lookupOrThrow(Registries.BIOME));
        };
    }

    private static <T> Holder<T> holder(MinecraftServer server, ResourceKey<? extends Registry<T>> registryKey,
            ResourceKey<T> key, String what) {
        Registry<T> registry = server.registryAccess().registryOrThrow(registryKey);
        return registry.getHolder(key).orElseThrow(() -> {
            List<String> ids = registry.keySet().stream().map(ResourceLocation::toString).sorted().toList();
            String shown = ids.size() > 30 ? String.join(", ", ids.subList(0, 30)) + " and " + (ids.size() - 30)
                    + " more" : String.join(", ", ids);
            return new IllegalArgumentException("This server has no " + what + " " + key.location()
                    + " (a mod or datapack that added it may be missing); it has: " + shown);
        });
    }

    // ---- Hadron's saved form: plain ids, so a version port never migrates it ----

    static JsonObject toJson(WorldGenerator generator) {
        JsonObject json = new JsonObject();
        switch (generator) {
            case WorldGenerator.Copy copy -> {
                json.addProperty("kind", "copy");
                json.addProperty("dimension", copy.dimension().location().toString());
            }
            case WorldGenerator.FlatPreset preset -> {
                json.addProperty("kind", "flat_preset");
                json.addProperty("preset", preset.preset().location().toString());
            }
            case WorldGenerator.FlatLayers flat -> {
                json.addProperty("kind", "flat");
                JsonArray layers = new JsonArray();
                for (FlatLayerInfo layer : flat.layers()) {
                    JsonObject l = new JsonObject();
                    l.addProperty("block", BuiltInRegistries.BLOCK.getKey(layer.getBlockState().getBlock()).toString());
                    l.addProperty("height", layer.getHeight());
                    layers.add(l);
                }
                json.add("layers", layers);
                json.addProperty("biome", flat.biome().location().toString());
            }
            case WorldGenerator.VoidWorld empty -> {
                json.addProperty("kind", "void");
                json.addProperty("biome", empty.biome().location().toString());
            }
            case WorldGenerator.Noise noise -> {
                json.addProperty("kind", "noise");
                json.addProperty("settings", noise.settings().location().toString());
                JsonObject biomes = new JsonObject();
                switch (noise.biomes()) {
                    case NoiseBiomes.MultiNoise multi -> {
                        biomes.addProperty("kind", "multi_noise");
                        biomes.addProperty("preset", multi.preset().location().toString());
                    }
                    case NoiseBiomes.Single single -> {
                        biomes.addProperty("kind", "single");
                        biomes.addProperty("biome", single.biome().location().toString());
                    }
                    case NoiseBiomes.End end -> biomes.addProperty("kind", "end");
                }
                json.add("biomes", biomes);
            }
        }
        return json;
    }

    static WorldGenerator fromJson(JsonObject json) {
        String kind = json.get("kind").getAsString();
        return switch (kind) {
            case "copy" -> WorldGenerator.copyOf(key(Registries.DIMENSION, json, "dimension"));
            case "flat_preset" -> WorldGenerator.flat(key(Registries.FLAT_LEVEL_GENERATOR_PRESET, json, "preset"));
            case "flat" -> {
                List<FlatLayerInfo> layers = new ArrayList<>();
                for (var element : json.getAsJsonArray("layers")) {
                    JsonObject l = element.getAsJsonObject();
                    ResourceLocation id = ResourceLocation.parse(l.get("block").getAsString());
                    Block block = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(() ->
                            new IllegalArgumentException("Its flat layers use block " + id + ", which no mod adds"));
                    layers.add(new FlatLayerInfo(l.get("height").getAsInt(), block));
                }
                yield WorldGenerator.flat(layers, key(Registries.BIOME, json, "biome"));
            }
            case "void" -> new WorldGenerator.VoidWorld(key(Registries.BIOME, json, "biome"));
            case "noise" -> {
                JsonObject b = json.getAsJsonObject("biomes");
                NoiseBiomes biomes = switch (b.get("kind").getAsString()) {
                    case "multi_noise" -> NoiseBiomes.multiNoise(
                            key(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST, b, "preset"));
                    case "single" -> NoiseBiomes.single(key(Registries.BIOME, b, "biome"));
                    case "end" -> NoiseBiomes.end();
                    default -> throw new IllegalArgumentException("Unknown biomes kind " + b.get("kind"));
                };
                yield WorldGenerator.noise(key(Registries.NOISE_SETTINGS, json, "settings"), biomes);
            }
            default -> throw new IllegalArgumentException("Unknown generator kind " + kind);
        };
    }

    private static <T> ResourceKey<T> key(ResourceKey<? extends Registry<T>> registry, JsonObject json, String field) {
        return ResourceKey.create(registry, ResourceLocation.parse(json.get(field).getAsString()));
    }
}
