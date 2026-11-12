package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.config.InvalidConfigurationException;
import dev.iredsc.hadron.api.config.YamlConfiguration;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.Area;
import dev.iredsc.hadron.api.world.Areas;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Every region, saved to {@code regions.yml} on each change, and watched ({@link Areas}) while its world is loaded so
 * a change there with nobody behind it is seen and refused. Server thread only.
 */
final class RegionStore {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Region> byId = new LinkedHashMap<>();
    private final Map<String, Area> watched = new HashMap<>();
    /** Regions removed since the plugin enabled, newest per id, so a removal can be taken back. */
    private final Map<String, Region> removed = new HashMap<>();
    private Map<ResourceLocation, List<Region>> byWorld = Map.of();

    private RegionStore(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    /** Reads regions.yml, or starts empty if there is none. */
    static RegionStore load(JavaPlugin plugin) throws RegionsFile.Problem {
        File file = new File(plugin.getDataFolder(), "regions.yml");
        RegionStore store = new RegionStore(plugin, file);
        if (file.exists()) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (IOException e) {
                throw new RegionsFile.Problem("Couldn't read " + file + ": " + e.getMessage());
            } catch (InvalidConfigurationException e) {
                throw new RegionsFile.Problem("regions.yml isn't valid YAML: " + e.getMessage());
            }
            store.byId.putAll(RegionsFile.read(yaml));
        }
        store.reindex();
        return store;
    }

    Collection<Region> all() {
        return byId.values();
    }

    Optional<Region> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Whether any region is in this world: false lets every event there through at once. */
    boolean hasAny(ServerLevel level) {
        return byWorld.containsKey(level.dimension().location());
    }

    /** The regions covering this position, in no order. */
    List<Region> at(ServerLevel level, BlockPos pos) {
        List<Region> world = byWorld.get(level.dimension().location());
        if (world == null) return List.of();
        List<Region> out = null;
        for (Region r : world) {
            if (!r.box().isInside(pos)) continue;
            if (out == null) out = new ArrayList<>(2);
            out.add(r);
        }
        return out == null ? List.of() : out;
    }

    /** The regions sharing at least one block with {@code box} in this world, in no order. */
    List<Region> overlapping(ServerLevel level, BoundingBox box) {
        List<Region> world = byWorld.get(level.dimension().location());
        if (world == null) return List.of();
        List<Region> out = new ArrayList<>(2);
        for (Region r : world) {
            if (r.box().intersects(box)) out.add(r);
        }
        return out;
    }

    /** Every region in this world. */
    List<Region> in(ServerLevel level) {
        return byWorld.getOrDefault(level.dimension().location(), List.of());
    }

    /**
     * Adds or replaces a region and saves.
     *
     * @throws UncheckedIOException if regions.yml can't be written; the change still holds until the server stops.
     */
    void put(Region region) {
        byId.put(region.id(), region);
        removed.remove(region.id());
        changed(region.id());
    }

    /** Removes a region and saves. It can be put back with {@link #restore} until the plugin disables. */
    Optional<Region> remove(String id) {
        Region gone = byId.remove(id);
        if (gone == null) return Optional.empty();
        removed.put(id, gone);
        changed(id);
        return Optional.of(gone);
    }

    /** Puts back the region last removed under this id, unless another has taken the id since. */
    Optional<Region> restore(String id) {
        Region back = removed.get(id);
        if (back == null || byId.containsKey(id)) return Optional.empty();
        put(back);
        return Optional.of(back);
    }

    Collection<String> restorable() {
        return removed.keySet();
    }

    /** Watches every region in this world; call when it loads. */
    void watch(ServerLevel level) {
        for (Region r : byId.values()) {
            if (r.world().equals(level.dimension().location())) rewatch(r.id());
        }
    }

    private void changed(String id) {
        reindex();
        rewatch(id);
        save();
    }

    private void rewatch(String id) {
        Area old = watched.remove(id);
        if (old != null) old.close();
        Region r = byId.get(id);
        if (r == null) return;
        ServerLevel level = plugin.getServer().getMinecraftServer()
                .getLevel(ResourceKey.create(Registries.DIMENSION, r.world()));
        if (level != null) watched.put(id, Areas.watch(plugin, level, r.box()));
    }

    private void reindex() {
        Map<ResourceLocation, List<Region>> next = new HashMap<>();
        for (Region r : byId.values()) next.computeIfAbsent(r.world(), w -> new ArrayList<>()).add(r);
        byWorld = next;
    }

    private void save() {
        try {
            RegionsFile.write(byId.values()).save(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save " + file + ", so this change lasts only until the server "
                    + "stops: " + e.getMessage(), e);
        }
    }
}
