package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
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
import java.util.LinkedHashSet;
import java.util.Set;
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
 * spawn. Every change is written before it counts: if the file can't be written, the change throws and nothing in
 * memory changes either, so what runs is never something a restart would forget.
 *
 * <p>A deleted world stays listed under {@code deleting} until its folder is gone, so a crash or a folder that can't
 * be removed never leaves an untracked folder that a new world of the same id would open with another seed.
 */
final class WorldsStore {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One created world. {@code spawn} is null until the world first loads and finds one. */
    record Definition(ResourceLocation key, ResourceKey<DimensionType> type, WorldGenerator generator, long seed,
            @Nullable BlockPos spawn, float angle, boolean keepSpawnLoaded, boolean loaded) {
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
    private Map<ResourceLocation, Definition> worlds = new LinkedHashMap<>();
    /** Deleted worlds whose folder isn't removed yet. */
    private Set<ResourceLocation> deleting = new LinkedHashSet<>();
    /** Entries this version couldn't read (a removed mod's block, a hand edit), written back untouched. */
    private final List<JsonElement> unreadable = new ArrayList<>();

    WorldsStore(Path file) {
        this.file = file;
        read();
    }

    Path file() {
        return file;
    }

    @Nullable Definition get(ResourceLocation key) {
        return worlds.get(key);
    }

    Collection<Definition> all() {
        return List.copyOf(worlds.values());
    }

    boolean isDeleting(ResourceLocation key) {
        return deleting.contains(key);
    }

    Collection<ResourceLocation> deleting() {
        return List.copyOf(deleting);
    }

    /** Saves the definition. @throws IllegalStateException if it couldn't be written; then nothing changed. */
    void put(Definition definition) {
        Map<ResourceLocation, Definition> next = new LinkedHashMap<>(worlds);
        next.put(definition.key(), definition);
        commit(next, deleting);
    }

    /** Forgets the world, remembering that its folder is still to remove. @throws IllegalStateException as put. */
    void markDeleting(ResourceLocation key) {
        Map<ResourceLocation, Definition> next = new LinkedHashMap<>(worlds);
        next.remove(key);
        Set<ResourceLocation> gone = new LinkedHashSet<>(deleting);
        gone.add(key);
        commit(next, gone);
    }

    /** The deleted world's folder is gone. @throws IllegalStateException as put. */
    void deleted(ResourceLocation key) {
        if (!deleting.contains(key)) return;
        Set<ResourceLocation> gone = new LinkedHashSet<>(deleting);
        gone.remove(key);
        commit(worlds, gone);
    }

    private void commit(Map<ResourceLocation, Definition> nextWorlds, Set<ResourceLocation> nextDeleting) {
        write(nextWorlds, nextDeleting);
        worlds = nextWorlds;
        deleting = nextDeleting;
    }

    private void read() {
        if (!Files.exists(file)) return;
        JsonArray list;
        JsonArray gone;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            list = root.getAsJsonArray("worlds");
            gone = root.has("deleting") ? root.getAsJsonArray("deleting") : new JsonArray();
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
        for (JsonElement element : gone) {
            try {
                deleting.add(ResourceLocation.parse(element.getAsString()));
            } catch (RuntimeException e) {
                LOG.error("Hadron skips a deleted world it can't read in {}: {} ({}); remove its folder by hand",
                        file, element, e.getMessage());
            }
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

    private void write(Map<ResourceLocation, Definition> nextWorlds, Set<ResourceLocation> nextDeleting) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray list = new JsonArray();
        for (Definition d : nextWorlds.values()) list.add(toJson(d));
        for (JsonElement e : unreadable) list.add(e);
        root.add("worlds", list);
        JsonArray gone = new JsonArray();
        for (ResourceLocation key : nextDeleting) gone.add(key.toString());
        root.add("deleting", gone);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Hadron couldn't save created worlds to " + file + " (" + e + "), so "
                    + "nothing was changed. Make that folder writable and free some disk space, then try again", e);
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
