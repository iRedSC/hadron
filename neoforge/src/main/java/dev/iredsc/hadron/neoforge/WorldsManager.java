package dev.iredsc.hadron.neoforge;

import com.mojang.logging.LogUtils;
import dev.iredsc.hadron.api.world.WorldCreator;
import dev.iredsc.hadron.api.world.WorldGenerator;
import dev.iredsc.hadron.api.world.Worlds;
import dev.iredsc.hadron.neoforge.WorldsStore.Definition;
import dev.iredsc.hadron.neoforge.mixin.worlds.MinecraftServerAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The running server's created worlds. Built the way {@code MinecraftServer.createLevels} builds the nether and end:
 * a ServerLevel over the world save's {@code dimensions/<namespace>/<path>} folder, added to the server's levels, its
 * border following the overworld's, announced with NeoForge's {@code LevelEvent.Load}. Server thread only.
 */
final class WorldsManager implements Worlds.Backend {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ChunkProgressListener NO_PROGRESS = new ChunkProgressListener() {
        @Override
        public void updateSpawnPos(ChunkPos center) {}

        @Override
        public void onStatusChange(ChunkPos chunkPos, @Nullable ChunkStatus chunkStatus) {}

        @Override
        public void start() {}

        @Override
        public void stop() {}
    };

    private record Open(ServerLevel level, BorderChangeListener border) {}

    /**
     * Unloaded but not yet closed: closing waits for the end of the tick, outside every level's tick. A world whose
     * close failed stays here, {@code failed} set, so nothing opens or deletes its files while they may be open.
     */
    private static final class Closing {
        final ServerLevel level;
        final boolean save;
        boolean delete;
        @Nullable Throwable failed;

        Closing(ServerLevel level, boolean save, boolean delete) {
            this.level = level;
            this.save = save;
            this.delete = delete;
        }
    }

    private final MinecraftServer server;
    private final WorldsStore store;
    private final Map<ResourceKey<Level>, Open> open = new HashMap<>();
    private final Map<ResourceKey<Level>, Closing> closing = new HashMap<>();

    WorldsManager(MinecraftServer server) {
        this.server = server;
        this.store = new WorldsStore(server.getWorldPath(LevelResource.ROOT).resolve("hadron").resolve("worlds.json")
                .toAbsolutePath().normalize());
    }

    /**
     * At startup, before plugins enable: finishes deleting what was deleted before a crash or a failed removal, then
     * loads every world that was loaded when the server last stopped.
     */
    void restore() {
        for (ResourceLocation key : store.deleting()) {
            try {
                WorldCreator.checkedKey(key);
                ResourceKey<Level> dimension = dimension(key);
                if (heldByModOrDatapack(dimension)) {
                    LOG.error("Hadron didn't finish deleting world {}: a mod or datapack now has a dimension with that "
                            + "id, using the same folder, so it was kept. Remove \"{}\" from \"deleting\" in {} once "
                            + "you're sure the folder is the mod's", key, key, store.file());
                    continue;
                }
                deleteFolder(dimension);
                store.deleted(key);
                LOG.info("Hadron finished deleting world {}", key);
            } catch (IOException | RuntimeException e) {
                LOG.error("Hadron still couldn't delete world {}'s folder: {}. Remove it by hand; until then the id "
                        + "can't be used for a new world", key, e.getMessage(), e);
            }
        }
        for (Definition d : store.all()) {
            if (!d.loaded()) continue;
            try {
                WorldCreator.checkedKey(d.key());
                ResourceKey<Level> dimension = dimension(d.key());
                refuseTaken(dimension);
                open(d, false);
                LOG.info("Hadron loaded created world {}", d.key());
            } catch (RuntimeException e) {
                LOG.error("Hadron couldn't load created world {}: {}. It stays saved; fix the cause and restart, or "
                        + "delete it with Worlds.delete.", d.key(), e.getMessage(), e);
            }
        }
    }

    // ---- Worlds.Backend ----

    @Override
    public ServerLevel create(WorldCreator creator) {
        onServerThread("create");
        ResourceLocation key = creator.key();
        Definition saved = store.get(key);
        if (saved != null) {
            requireSame(saved, creator);
            return loadSaved(saved);
        }
        WorldCreator.checkedKey(key);
        ResourceKey<Level> dimension = dimension(key);
        refuseTaken(dimension);
        refuseClosing(dimension);
        if (store.isDeleting(key)) finishDeleting(dimension);
        WorldGenerator generator = creator.generator()
                .orElseGet(() -> WorldsGenerators.defaultFor(creator.type(), key));
        long seed = creator.seed().orElseGet(() -> ThreadLocalRandom.current().nextLong());
        return open(new Definition(key, creator.type(), generator, seed, creator.spawn().orElse(null), 0,
                creator.keepSpawnLoaded(), true), true);
    }

    @Override
    public ServerLevel load(ResourceLocation key) {
        onServerThread("load");
        Definition saved = store.get(key);
        if (saved == null && store.isDeleting(key)) {
            throw new IllegalArgumentException("World " + key + " was deleted; create a new one with "
                    + "Worlds.create(new WorldCreator(" + quoted(key) + "))");
        }
        if (saved == null) {
            throw new IllegalArgumentException("Hadron never created a world " + key + "; create it with "
                    + "Worlds.create(new WorldCreator(" + quoted(key) + "))");
        }
        return loadSaved(saved);
    }

    private ServerLevel loadSaved(Definition saved) {
        WorldCreator.checkedKey(saved.key());
        ResourceKey<Level> dimension = dimension(saved.key());
        Open loaded = open.get(dimension);
        if (loaded != null) return loaded.level();
        refuseTaken(dimension);
        refuseClosing(dimension);
        return open(saved, false);
    }

    @Override
    public Optional<ServerLevel> get(ResourceLocation key) {
        return Optional.ofNullable(server.getLevel(dimension(key)));
    }

    @Override
    public List<ServerLevel> list() {
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        return List.copyOf(levels);
    }

    @Override
    public List<WorldCreator> created() {
        return store.all().stream().map(Definition::toCreator).toList();
    }

    @Override
    public boolean isCreated(ResourceLocation key) {
        return store.get(key) != null;
    }

    @Override
    public void unload(ServerLevel level, boolean save, @Nullable ServerLevel to, @Nullable Vec3 at) {
        onServerThread("unload");
        ResourceKey<Level> dimension = level.dimension();
        Open loaded = open.get(dimension);
        if (loaded == null || loaded.level() != level) throw notOurs(dimension, "unload");
        if (to == null) {
            to = server.overworld();
            BlockPos spawn = to.getSharedSpawnPos();
            at = Vec3.atBottomCenterOf(spawn);
        } else if (to == level || server.getLevel(to.dimension()) != to) {
            throw new IllegalArgumentException("Can't move players out of " + dimension.location() + " into "
                    + to.dimension().location() + ": pick another loaded world");
        }
        // Saved first: if the file can't be written, nothing has happened yet.
        Definition definition = store.get(dimension.location());
        store.put(definition.withLoaded(false));
        // Players are asked to leave like any teleport, so listeners may send them somewhere else; anyone a listener
        // kept here is moved anyway, without events, since a world being unloaded can't keep anyone in it.
        ServerLevel target = to;
        Vec3 spot = at;
        for (ServerPlayer player : List.copyOf(level.players())) {
            player.teleportTo(target, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            EntityTeleports.quietly(player, () -> player.teleportTo(target, spot.x, spot.y, spot.z, player.getYRot(),
                    player.getXRot()));
        }
        if (!level.players().isEmpty()) {
            try {
                store.put(definition);
            } catch (IllegalStateException e) {
                LOG.error("Hadron couldn't mark world {} loaded again; after a restart it stays unloaded until "
                        + "Worlds.load", dimension.location(), e);
            }
            throw new IllegalStateException("Couldn't move every player out of " + dimension.location() + ", so it "
                    + "stays loaded: " + level.players().stream().map(p -> p.getGameProfile().getName()).toList());
        }
        unpublish(dimension, loaded);
        closing.put(dimension, new Closing(level, save, false));
        // While the server runs, a world closes at the end of the tick, never inside its own tick or a chunk task.
        if (!server.isRunning()) finishClosing();
    }

    @Override
    public void delete(ResourceLocation key) {
        onServerThread("delete");
        ResourceKey<Level> dimension = dimension(key);
        if (store.get(key) == null && !store.isDeleting(key)) throw notOurs(dimension, "delete");
        // The folder is the dimension's, whoever loads it: never erase a mod's or datapack's world.
        if (heldByModOrDatapack(dimension)) {
            throw new IllegalStateException("World " + key + " can't be deleted: a mod or datapack now has a dimension "
                    + "with this id, which uses the same folder. Remove that dimension from the mod or datapack first, "
                    + "or keep the world (Worlds.created() still lists it)");
        }
        Closing failed = closing.get(dimension);
        if (failed != null && failed.failed != null) throw failedToClose(dimension, failed);
        if (store.get(key) == null) {
            // Deleted before, and its folder stayed: try again, unless it is still closing (it goes then).
            if (failed == null) finishDeleting(dimension);
            return;
        }
        Open loaded = open.get(dimension);
        if (loaded != null) unload(loaded.level(), false, null, null);
        // Remembered as deleting before anything is removed, so a crash or an error resumes instead of leaving an
        // untracked folder that a new world of this id would open with another seed.
        store.markDeleting(key);
        Closing pending = closing.get(dimension);
        if (pending != null) {
            pending.delete = true;
            if (!server.isRunning()) finishClosing();
        } else {
            finishDeleting(dimension);
        }
    }

    /** Removes a deleted world's folder and stops remembering it. @throws IllegalStateException if it can't. */
    private void finishDeleting(ResourceKey<Level> dimension) {
        try {
            deleteFolder(dimension);
        } catch (IOException e) {
            throw new IllegalStateException("World " + dimension.location() + " was deleted, but Hadron couldn't remove "
                    + "its folder " + folderOf(dimension) + " (" + e + "). Remove it by hand, or make it writable; "
                    + "until then the id can't be used for a new world. Hadron tries again on every start and on "
                    + "Worlds.create or Worlds.delete of this id", e);
        }
        store.deleted(dimension.location());
    }

    // ---- Building and closing ----

    /**
     * Builds, saves and announces a world, all or nothing: if any step throws, the world is taken out again, closed at
     * the end of the tick, and for a new one ({@code fresh}) forgotten and its folder removed.
     */
    private ServerLevel open(Definition d, boolean fresh) {
        ResourceKey<Level> dimension = dimension(d.key());
        Holder<DimensionType> type = WorldsGenerators.dimensionType(server, d.type());
        ChunkGenerator generator = WorldsGenerators.build(server, d.generator());
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        BlockPos firstSpawn = d.spawn() != null ? d.spawn() : new BlockPos(0, 64, 0);
        WorldsLevelData data = new WorldsLevelData(server.getWorldData(), server.getWorldData().overworldData(),
                firstSpawn, d.angle(), (pos, angle) -> {
                    Definition current = store.get(d.key());
                    if (current == null) return;
                    try {
                        store.put(current.withSpawn(pos, angle));
                    } catch (IllegalStateException e) {
                        // The world itself is safe: only where players spawn after a restart is lost.
                        LOG.error("Hadron couldn't save world {}'s new spawn; after a restart it is back at the last "
                                + "saved one", d.key(), e);
                    }
                });
        // Before the constructor: ChunkMap builds the generator's random state from getSeed while it runs.
        WorldsHooks.putSeed(dimension, d.seed());
        ServerLevel level;
        try {
            level = new ServerLevel(server, access.hadron$executor(), access.hadron$storageSource(), data, dimension,
                    new LevelStem(type, generator), NO_PROGRESS, false, BiomeManager.obfuscateSeed(d.seed()),
                    List.of(), false, null);
        } catch (RuntimeException e) {
            WorldsHooks.removeSeed(dimension);
            throw new IllegalStateException("Couldn't build world " + d.key() + ": " + e, e);
        }
        try {
            store.put(d.withLoaded(true));
        } catch (IllegalStateException e) {
            // Never announced or ticked: closed at once, and a new world's empty folder goes with it.
            closeUnannounced(dimension, level, fresh);
            throw e;
        }
        server.forgeGetWorldMap().put(dimension, level);
        server.markWorldsDirty();
        // As vanilla's nether and end: the border follows the overworld's.
        ServerLevel overworld = server.overworld();
        BorderChangeListener border = new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
        overworld.getWorldBorder().addListener(border);
        level.getWorldBorder().applySettings(overworld.getWorldBorder().createSettings());
        Open opened = new Open(level, border);
        open.put(dimension, opened);
        try {
            announce(level, data, d);
        } catch (RuntimeException e) {
            undoOpen(dimension, opened, d, fresh);
            throw new IllegalStateException("World " + d.key() + " couldn't finish loading (" + e + "), so it was "
                    + (fresh ? "removed again" : "unloaded again, without saving") + ". A mod's or plugin's world "
                    + "load listener is the usual cause; fix it, then call Worlds." + (fresh ? "create" : "load")
                    + " on a later tick", e);
        }
        return level;
    }

    /** The steps after a world is in the server's map: the load event, forced chunks, spawn. */
    private void announce(ServerLevel level, WorldsLevelData data, Definition d) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
        // As vanilla does for every level at startup: chunks forced before the unload or restart are forced again.
        net.minecraft.world.level.ForcedChunksSavedData forced = level.getDataStorage()
                .get(net.minecraft.world.level.ForcedChunksSavedData.factory(), "chunks");
        if (forced != null) {
            for (long chunk : forced.getChunks()) {
                level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(chunk), true);
            }
            net.neoforged.neoforge.common.world.chunk.ForcedChunkManager.reinstatePersistentChunks(level, forced);
        }

        BlockPos spawn = d.spawn();
        if (spawn == null) {
            spawn = findSpawn(level);
            data.setSpawn(spawn, 0);
        }
        if (d.keepSpawnLoaded()) {
            int radius = server.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) + 1;
            if (radius > 1) level.getChunkSource().addRegionTicket(TicketType.START, new ChunkPos(spawn), radius, Unit.INSTANCE);
        }
    }

    /** Takes a loaded world out of the server; closing is up to the caller. */
    private void unpublish(ResourceKey<Level> dimension, Open loaded) {
        server.forgeGetWorldMap().remove(dimension);
        server.markWorldsDirty();
        server.overworld().getWorldBorder().removeListener(loaded.border());
        open.remove(dimension);
    }

    /** A world that failed after it was published: out of the server, its definition put back, closed at tick end. */
    private void undoOpen(ResourceKey<Level> dimension, Open opened, Definition d, boolean fresh) {
        ServerLevel level = opened.level();
        // A listener may have sent someone in already.
        ServerLevel overworld = server.overworld();
        Vec3 spawn = Vec3.atBottomCenterOf(overworld.getSharedSpawnPos());
        for (ServerPlayer player : List.copyOf(level.players())) {
            EntityTeleports.quietly(player, () -> player.teleportTo(overworld, spawn.x, spawn.y, spawn.z, player.getYRot(),
                    player.getXRot()));
        }
        unpublish(dimension, opened);
        boolean delete = false;
        try {
            if (fresh) {
                store.markDeleting(d.key());
                delete = true;
            } else {
                store.put(d.withLoaded(false));
            }
        } catch (IllegalStateException e) {
            LOG.error("Hadron couldn't save that world {} failed to load", d.key(), e);
        }
        // Mods that heard it load hear it unload, as for any world.
        closing.put(dimension, new Closing(level, false, delete));
        if (!server.isRunning()) finishClosing();
    }

    private void closeUnannounced(ResourceKey<Level> dimension, ServerLevel level, boolean fresh) {
        level.noSave = true;
        WorldsHooks.setDiscarding(true);
        try {
            level.close();
        } catch (IOException | RuntimeException e) {
            LOG.error("Hadron couldn't close world {} after failing to save it", dimension.location(), e);
            return; // its files may be open: leave the folder alone
        } finally {
            WorldsHooks.setDiscarding(false);
            WorldsHooks.removeSeed(dimension);
        }
        if (!fresh) return;
        try {
            deleteFolder(dimension);
        } catch (IOException e) {
            LOG.error("Hadron couldn't remove the empty folder of world {}, which was never saved; remove {} by hand",
                    dimension.location(), folderOf(dimension), e);
        }
    }

    /** A spot to stand: the surface, or for a world with a ceiling (the nether) the first open floor below it. */
    private static BlockPos findSpawn(ServerLevel level) {
        int x = 0, z = 0;
        if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator) {
            BlockPos target = level.getChunkSource().randomState().sampler().findSpawnPosition();
            x = target.getX();
            z = target.getZ();
        }
        int min = level.getMinBuildHeight();
        DimensionType type = level.dimensionType();
        if (!type.hasCeiling()) {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            return new BlockPos(x, y > min ? y : 64, z);
        }
        level.getChunk(x >> 4, z >> 4);
        for (int y = min + type.logicalHeight() - 2; y > min; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                    && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                return feet;
            }
        }
        return new BlockPos(x, min + type.logicalHeight() / 2, z);
    }

    /** Closes every unloaded world: at the end of each tick, and at once while the server stops. */
    void finishClosing() {
        if (closing.isEmpty()) return;
        // Each world stays marked closing until its storage is closed (and its folder deleted), so a listener on the
        // unload event can't open a second copy over the same files or delete them from under the first.
        List<Map.Entry<ResourceKey<Level>, Closing>> pending = new ArrayList<>(closing.entrySet());
        for (var entry : pending) {
            ResourceKey<Level> dimension = entry.getKey();
            Closing c = entry.getValue();
            if (c.failed != null) continue;
            ServerLevel level = c.level;
            // Each step on its own: a mod's broken listener or a failed save must never skip the close, or the
            // world's files stay open under the next copy.
            if (c.save) {
                try {
                    level.save(null, true, false);
                } catch (RuntimeException e) {
                    LOG.error("Hadron couldn't save world {} while unloading it; it closes with what was saved before",
                            dimension.location(), e);
                }
            } else {
                level.noSave = true;
            }
            try {
                NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
            } catch (RuntimeException e) {
                LOG.error("A listener for the unload of world {} threw; Hadron closes the world anyway",
                        dimension.location(), e);
            }
            WorldsHooks.setDiscarding(!c.save);
            try {
                level.close();
            } catch (IOException | RuntimeException e) {
                c.failed = e;
                LOG.error("Hadron couldn't close world {}, so its files may still be open; it can't be loaded or "
                        + "deleted again until the server restarts", dimension.location(), e);
                continue;
            } finally {
                WorldsHooks.setDiscarding(false);
            }
            WorldsHooks.removeSeed(dimension);
            closing.remove(dimension, c);
            if (c.delete) {
                try {
                    finishDeleting(dimension);
                } catch (IllegalStateException e) {
                    LOG.error(e.getMessage(), e);
                }
            }
        }
    }

    private Path folderOf(ResourceKey<Level> dimension) {
        return ((MinecraftServerAccessor) server).hadron$storageSource().getDimensionPath(dimension).toAbsolutePath()
                .normalize();
    }

    /** @throws IOException if any of it is left */
    private void deleteFolder(ResourceKey<Level> dimension) throws IOException {
        LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).hadron$storageSource();
        Path root = storage.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path folder = folderOf(dimension);
        // getDimensionPath is the save's root for the overworld; only ever remove something under dimensions/.
        if (!folder.startsWith(root.resolve("dimensions")) || folder.equals(root.resolve("dimensions"))) {
            throw new IOException("Hadron refused to delete " + folder + ": it isn't a created world's folder");
        }
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    // ---- Checks, each saying the fix ----

    private void onServerThread(String method) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Worlds." + method + " must run on the server thread; from another "
                    + "thread use Hadron.getScheduler().runTask(plugin, () -> ...)");
        }
    }

    private void refuseTaken(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD)) {
            throw new IllegalArgumentException("minecraft:overworld is the server's main world; give the new world "
                    + "your own id, like myplugin:" + "world");
        }
        if (heldByModOrDatapack(dimension)) {
            throw new IllegalArgumentException(dimension.location() + " is already a dimension of a mod or datapack; "
                    + "give the new world another id, or reach that dimension with Worlds.get(" + quoted(dimension.location()) + ")");
        }
    }

    /** A dimension of a mod or datapack (registered, or loaded but not by Hadron) has this id, and its folder. */
    private boolean heldByModOrDatapack(ResourceKey<Level> dimension) {
        boolean registered = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
                .containsKey(dimension.location());
        return registered || (server.getLevel(dimension) != null && !open.containsKey(dimension));
    }

    private static IllegalStateException failedToClose(ResourceKey<Level> dimension, Closing c) {
        return new IllegalStateException("World " + dimension.location() + " couldn't be closed when it was unloaded ("
                + c.failed + "), so its files may still be open; it can't be loaded or deleted until the server "
                + "restarts");
    }

    private void refuseClosing(ResourceKey<Level> dimension) {
        Closing c = closing.get(dimension);
        if (c != null && c.failed != null) throw failedToClose(dimension, c);
        if (c != null) {
            throw new IllegalStateException("World " + dimension.location() + " was unloaded this tick and closes at "
                    + "the end of it; load it again from a task on the next tick: "
                    + "Hadron.getScheduler().runTask(plugin, () -> ...)");
        }
    }

    private IllegalArgumentException notOurs(ResourceKey<Level> dimension, String verb) {
        if (dimension.equals(Level.OVERWORLD)) {
            return new IllegalArgumentException("The overworld can't be " + (verb.equals("delete") ? "deleted" : "unloaded")
                    + "; only worlds made with Worlds.create can");
        }
        if (store.get(dimension.location()) != null) {
            return new IllegalArgumentException("World " + dimension.location() + " is already unloaded; Worlds.delete "
                    + "removes it for good, Worlds.load brings it back");
        }
        return new IllegalArgumentException("Hadron can only " + verb + " worlds it created with Worlds.create; "
                + dimension.location() + (server.getLevel(dimension) != null ? " belongs to a mod or datapack"
                        : " doesn't exist") + " (Worlds.created() lists Hadron's)");
    }

    private static void requireSame(Definition saved, WorldCreator creator) {
        List<String> different = new ArrayList<>();
        if (creator.hasType() && !creator.type().equals(saved.type())) {
            different.add("type " + saved.type().location() + " (asked for " + creator.type().location() + ")");
        }
        if (creator.generator().isPresent() && !WorldsGenerators.toJson(creator.generator().get())
                .equals(WorldsGenerators.toJson(saved.generator()))) {
            different.add("generator " + WorldsGenerators.toJson(saved.generator()) + " (asked for "
                    + WorldsGenerators.toJson(creator.generator().get()) + ")");
        }
        if (creator.seed().isPresent() && creator.seed().getAsLong() != saved.seed()) {
            different.add("seed " + saved.seed() + " (asked for " + creator.seed().getAsLong() + ")");
        }
        if (!different.isEmpty()) {
            throw new IllegalArgumentException("World " + saved.key() + " already exists with " + String.join(", ", different)
                    + ". Use it as it is with Worlds.load(" + quoted(saved.key()) + "), pick a new id, or remove it first "
                    + "with Worlds.delete(" + quoted(saved.key()) + ") (that erases its blocks)");
        }
    }

    private static ResourceKey<Level> dimension(ResourceLocation key) {
        return ResourceKey.create(Registries.DIMENSION, key);
    }

    private static String quoted(ResourceLocation key) {
        return "ResourceLocation.parse(\"" + key + "\")";
    }
}
