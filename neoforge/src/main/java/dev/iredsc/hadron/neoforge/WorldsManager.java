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

    /** Unloaded but not yet closed: closing waits for the end of the tick, outside every level's tick. */
    private static final class Closing {
        final ServerLevel level;
        final boolean save;
        boolean delete;

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

    /** At startup, before plugins enable: every world that was loaded when the server last stopped. */
    void restore() {
        for (Definition d : store.all()) {
            if (!d.loaded()) continue;
            try {
                WorldCreator.checkedKey(d.key());
                ResourceKey<Level> dimension = dimension(d.key());
                refuseTaken(dimension);
                open(d);
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
        WorldGenerator generator = creator.generator()
                .orElseGet(() -> WorldsGenerators.defaultFor(creator.type(), key));
        long seed = creator.seed().orElseGet(() -> ThreadLocalRandom.current().nextLong());
        return open(new Definition(key, creator.type(), generator, seed, creator.spawn().orElse(null), 0,
                creator.keepSpawnLoaded(), true));
    }

    @Override
    public ServerLevel load(ResourceLocation key) {
        onServerThread("load");
        Definition saved = store.get(key);
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
        return open(saved);
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
        // Players are asked to leave like any teleport, so listeners may send them somewhere else; anyone a listener
        // kept here is moved anyway, without events, since a world being unloaded can't keep anyone in it.
        ServerLevel target = to;
        Vec3 spot = at;
        for (ServerPlayer player : List.copyOf(level.players())) {
            player.teleportTo(target, spot.x, spot.y, spot.z, player.getYRot(), player.getXRot());
        }
        for (ServerPlayer player : List.copyOf(level.players())) {
            EntityTeleports.quietly(() -> player.teleportTo(target, spot.x, spot.y, spot.z, player.getYRot(),
                    player.getXRot()));
        }
        if (!level.players().isEmpty()) {
            throw new IllegalStateException("Couldn't move every player out of " + dimension.location() + ", so it "
                    + "stays loaded: " + level.players().stream().map(p -> p.getGameProfile().getName()).toList());
        }
        server.forgeGetWorldMap().remove(dimension);
        server.markWorldsDirty();
        server.overworld().getWorldBorder().removeListener(loaded.border());
        open.remove(dimension);
        store.put(store.get(dimension.location()).withLoaded(false));
        closing.put(dimension, new Closing(level, save, false));
        // While the server runs, a world closes at the end of the tick, never inside its own tick or a chunk task.
        if (!server.isRunning()) finishClosing();
    }

    @Override
    public void delete(ResourceLocation key) {
        onServerThread("delete");
        ResourceKey<Level> dimension = dimension(key);
        if (store.get(key) == null) throw notOurs(dimension, "delete");
        Open loaded = open.get(dimension);
        if (loaded != null) unload(loaded.level(), false, null, null);
        store.remove(key);
        Closing pending = closing.get(dimension);
        if (pending != null) {
            pending.delete = true;
            if (!server.isRunning()) finishClosing();
        } else {
            deleteFolder(dimension);
        }
    }

    // ---- Building and closing ----

    private ServerLevel open(Definition d) {
        ResourceKey<Level> dimension = dimension(d.key());
        Holder<DimensionType> type = WorldsGenerators.dimensionType(server, d.type());
        ChunkGenerator generator = WorldsGenerators.build(server, d.generator());
        MinecraftServerAccessor access = (MinecraftServerAccessor) server;
        BlockPos firstSpawn = d.spawn() != null ? d.spawn() : new BlockPos(0, 64, 0);
        WorldsLevelData data = new WorldsLevelData(server.getWorldData(), server.getWorldData().overworldData(),
                firstSpawn, d.angle(), (pos, angle) -> {
                    Definition current = store.get(d.key());
                    if (current != null) store.put(current.withSpawn(pos, angle));
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
        server.forgeGetWorldMap().put(dimension, level);
        server.markWorldsDirty();
        // As vanilla's nether and end: the border follows the overworld's.
        ServerLevel overworld = server.overworld();
        BorderChangeListener border = new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder());
        overworld.getWorldBorder().addListener(border);
        level.getWorldBorder().applySettings(overworld.getWorldBorder().createSettings());
        open.put(dimension, new Open(level, border));
        store.put(d.withLoaded(true));
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
        return level;
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
            ServerLevel level = c.level;
            try {
                if (c.save) {
                    level.save(null, true, false);
                } else {
                    level.noSave = true;
                }
                NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
                WorldsHooks.setDiscarding(!c.save);
                try {
                    level.close();
                } finally {
                    WorldsHooks.setDiscarding(false);
                }
            } catch (IOException | RuntimeException e) {
                LOG.error("Hadron hit an error closing world {}", dimension.location(), e);
            } finally {
                WorldsHooks.removeSeed(dimension);
            }
            if (c.delete) deleteFolder(dimension);
            closing.remove(dimension, c);
        }
    }

    private void deleteFolder(ResourceKey<Level> dimension) {
        LevelStorageSource.LevelStorageAccess storage = ((MinecraftServerAccessor) server).hadron$storageSource();
        Path root = storage.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path folder = storage.getDimensionPath(dimension).toAbsolutePath().normalize();
        // getDimensionPath is the save's root for the overworld; only ever remove something under dimensions/.
        if (!folder.startsWith(root.resolve("dimensions")) || folder.equals(root.resolve("dimensions"))) {
            LOG.error("Hadron refused to delete {}: it isn't a created world's folder", folder);
            return;
        }
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            LOG.error("Hadron deleted world {} but couldn't remove all of {}; delete what's left by hand",
                    dimension.location(), folder, e);
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
        boolean registered = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
                .containsKey(dimension.location());
        if (registered || (server.getLevel(dimension) != null && !open.containsKey(dimension))) {
            throw new IllegalArgumentException(dimension.location() + " is already a dimension of a mod or datapack; "
                    + "give the new world another id, or reach that dimension with Worlds.get(" + quoted(dimension.location()) + ")");
        }
    }

    private void refuseClosing(ResourceKey<Level> dimension) {
        if (closing.containsKey(dimension)) {
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
