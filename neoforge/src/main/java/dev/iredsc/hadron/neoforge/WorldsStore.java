package dev.iredsc.hadron.neoforge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

/**
 * Every created world's definition, in {@code <world>/hadron/worlds.json}: plain ids and numbers, so the file reads
 * the same on every Minecraft version. Written whenever a world is created, loaded, unloaded, deleted or moves its
 * spawn.
 */
final class WorldsStore {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One created world. {@code spawn} is null until the world first loads and finds one. */
    record Definition(ResourceLocation key, ResourceKey<DimensionType> type, WorldGenerator generator, long seed,
            BlockPos spawn, float angle, boolean keepSpawnLoaded, boolean loaded) {
        Definition withSpawn(BlockPos pos, float newAngle) {
            return new Definition(key, type, generator, seed, pos, newAngle, keepSpawnLoaded, loaded);
        }

        Definition withLoaded(boolean value) {
            return new Definition(key, type, generator, seed, spawn, angle, keepSpawnLoaded, value);
        }

        WorldCreator toCreator() {
            WorldCreator creator = new WorldCreator(key).type(type).generator(generator).seed(seed)
                    .keepSpawnLoaded(keepSpawnLoaded);
            if (spawn != null) creator.spawn(spawn);
            return creator;
        }
    }

    private final Path file;
    private final Map<ResourceLocation, Definition> worlds = new LinkedHashMap<>();
    /** Entries this version couldn't read (a removed mod's block, a hand edit), written back untouched. */
    private final List<JsonElement> unreadable = new ArrayList<>();

    WorldsStore(Path file) {
        this.file = file;
        read();
    }

    Definition get(ResourceLocation key) {
        return worlds.get(key);
    }

    Collection<Definition> all() {
        return List.copyOf(worlds.values());
    }

    void put(Definition definition) {
        worlds.put(definition.key(), definition);
        write();
    }

    void remove(ResourceLocation key) {
        worlds.remove(key);
        write();
    }

    private void read() {
        if (!Files.exists(file)) return;
        JsonArray list;
        try {
            list = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonArray("worlds");
        } catch (Exception e) {
            Path aside = file.resolveSibling(file.getFileName() + ".unreadable");
            LOG.error("Hadron couldn't read {} ({}); moved it to {} and starts with no created worlds. Fix the file "
                    + "and move it back, then restart.", file, e.getMessage(), aside);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                LOG.error("Couldn't move it aside either", moveFailed);
            }
            return;
        }
        for (JsonElement element : list) {
            try {
                Definition d = fromJson(element.getAsJsonObject());
                worlds.put(d.key(), d);
            } catch (RuntimeException e) {
                unreadable.add(element);
                LOG.error("Hadron skips a created world it can't read in {}: {} ({}). It is kept in the file.", file,
                        element, e.getMessage());
            }
        }
    }

    private void write() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray list = new JsonArray();
        for (Definition d : worlds.values()) list.add(toJson(d));
        for (JsonElement e : unreadable) list.add(e);
        root.add("worlds", list);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.error("Hadron couldn't save created worlds to {}; they won't come back after a restart", file, e);
        }
    }

    private static JsonObject toJson(Definition d) {
        JsonObject json = new JsonObject();
        json.addProperty("key", d.key().toString());
        json.addProperty("type", d.type().location().toString());
        json.add("generator", WorldsGenerators.toJson(d.generator()));
        json.addProperty("seed", d.seed());
        if (d.spawn() != null) {
            JsonObject spawn = new JsonObject();
            spawn.addProperty("x", d.spawn().getX());
            spawn.addProperty("y", d.spawn().getY());
            spawn.addProperty("z", d.spawn().getZ());
            spawn.addProperty("angle", d.angle());
            json.add("spawn", spawn);
        }
        json.addProperty("keepSpawnLoaded", d.keepSpawnLoaded());
        json.addProperty("loaded", d.loaded());
        return json;
    }

    private static Definition fromJson(JsonObject json) {
        BlockPos spawn = null;
        float angle = 0;
        if (json.has("spawn")) {
            JsonObject s = json.getAsJsonObject("spawn");
            spawn = new BlockPos(s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt());
            angle = s.has("angle") ? s.get("angle").getAsFloat() : 0;
        }
        return new Definition(
                ResourceLocation.parse(json.get("key").getAsString()),
                ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.parse(json.get("type").getAsString())),
                WorldsGenerators.fromJson(json.getAsJsonObject("generator")),
                json.get("seed").getAsLong(),
                spawn, angle,
                json.has("keepSpawnLoaded") && json.get("keepSpawnLoaded").getAsBoolean(),
                !json.has("loaded") || json.get("loaded").getAsBoolean());
    }
}
