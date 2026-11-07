package dev.iredsc.hadron.api.world;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Describes a world to create while the server runs, like Bukkit's {@code WorldCreator}. Settings left unset take
 * the defaults below.
 *
 * <pre>{@code
 * ServerLevel mining = new WorldCreator(ResourceLocation.fromNamespaceAndPath("myplugin", "mining"))
 *         .generator(WorldGenerator.copyOf(Level.OVERWORLD))
 *         .seed(1234L)
 *         .createWorld();
 * player.teleportTo(mining, 0.5, 80, 0.5, 0, 0);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #type}: the dimension type (sky, height, ceiling, bed and portal rules), from the server's registry.
 *       Defaults to {@code minecraft:overworld}.</li>
 *   <li>{@link #generator}: how terrain is made. Defaults to a copy of the vanilla dimension with the same type
 *       (overworld, nether or end); any other type needs one set.</li>
 *   <li>{@link #seed}: defaults to a random seed, chosen once and kept.</li>
 *   <li>{@link #spawn}: defaults to a safe spot found the first time the world is created, and kept.</li>
 *   <li>{@link #keepSpawnLoaded}: defaults to false, so an empty world costs nothing.</li>
 * </ul>
 */
public final class WorldCreator {
    private final ResourceLocation key;
    private ResourceKey<DimensionType> type;
    private WorldGenerator generator;
    private Long seed;
    private BlockPos spawn;
    private boolean keepSpawnLoaded;

    /** A world whose dimension id is {@code key}; use your plugin's name as the namespace, e.g. {@code myplugin:mining}. */
    public WorldCreator(ResourceLocation key) {
        this.key = checkKey(Objects.requireNonNull(key, "key"));
    }

    /**
     * A world's id is also its folder name ({@code dimensions/<namespace>/<path>}), so the path must be one plain
     * name: no {@code /}, which would nest one world's folder inside another's, and no {@code .} or {@code ..}.
     */
    static ResourceLocation checkKey(ResourceLocation key) {
        String path = key.getPath();
        if (path.isEmpty() || path.contains("/") || path.equals(".") || path.equals("..")
                || key.getNamespace().equals(".") || key.getNamespace().equals("..")) {
            throw new IllegalArgumentException("World id " + key + " must be one plain name after the colon, since it "
                    + "is also the world's folder: use letters, digits, _ and -, like "
                    + key.getNamespace().replace("..", "myplugin").replace(".", "myplugin") + ":"
                    + path.replace("/", "_").replace("..", "world").replace(".", "world"));
        }
        return key;
    }

    /** For Hadron's port: the same check for ids read back from saved definitions. */
    public static ResourceLocation checkedKey(ResourceLocation key) {
        return checkKey(key);
    }

    /** Bukkit's {@code WorldCreator.name}. */
    public static WorldCreator name(ResourceLocation key) {
        return new WorldCreator(key);
    }

    public WorldCreator type(ResourceKey<DimensionType> type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    public WorldCreator generator(WorldGenerator generator) {
        this.generator = Objects.requireNonNull(generator, "generator");
        return this;
    }

    public WorldCreator seed(long seed) {
        this.seed = seed;
        return this;
    }

    public WorldCreator spawn(BlockPos spawn) {
        this.spawn = spawn.immutable();
        return this;
    }

    public WorldCreator keepSpawnLoaded(boolean keep) {
        this.keepSpawnLoaded = keep;
        return this;
    }

    /** Same as {@link Worlds#create}. */
    public ServerLevel createWorld() {
        return Worlds.create(this);
    }

    public ResourceLocation key() {
        return key;
    }

    /** The type set, or {@code minecraft:overworld}. */
    public ResourceKey<DimensionType> type() {
        return type != null ? type : BuiltinDimensionTypes.OVERWORLD;
    }

    /** Whether {@link #type(ResourceKey)} was called. */
    public boolean hasType() {
        return type != null;
    }

    /** The generator set; empty means the default for the type. */
    public Optional<WorldGenerator> generator() {
        return Optional.ofNullable(generator);
    }

    /** The seed set; empty means a random one. */
    public OptionalLong seed() {
        return seed != null ? OptionalLong.of(seed) : OptionalLong.empty();
    }

    /** The spawn set; empty means one is found. */
    public Optional<BlockPos> spawn() {
        return Optional.ofNullable(spawn);
    }

    public boolean keepSpawnLoaded() {
        return keepSpawnLoaded;
    }

    @Override
    public String toString() {
        return "WorldCreator[" + key + ", type=" + type() + ", generator=" + generator + ", seed=" + seed
                + ", spawn=" + spawn + ", keepSpawnLoaded=" + keepSpawnLoaded + "]";
    }
}
