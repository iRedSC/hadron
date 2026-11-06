package dev.iredsc.hadron.api.world;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Worlds created, loaded, unloaded and deleted while the server runs, like Bukkit's {@code createWorld},
 * {@code getWorld}, {@code getWorlds} and {@code unloadWorld}. A created world is an ordinary dimension: send players
 * there with {@code player.teleportTo(level, x, y, z, yaw, pitch)}, and unmodded clients follow.
 *
 * <p>A created world belongs to the server, not the plugin that made it: it stays loaded when that plugin disables,
 * and comes back loaded after a restart, before any plugin enables, so {@code onEnable} can rely on it. Calling
 * {@link #create} again with the same settings (as {@code onEnable} does on every start) returns the same world.
 *
 * <p>Every method must be called on the server thread (in a listener, a command, {@code onEnable}, or a sync task);
 * from another thread, use {@code Hadron.getScheduler().runTask(plugin, ...)}.
 */
public final class Worlds {
    private static volatile Backend backend;

    private Worlds() {}

    /**
     * Creates the world, or returns it if it already exists with these settings. A created world that was unloaded
     * is loaded again with its saved settings.
     *
     * @throws IllegalArgumentException if the key is the overworld or a mod's or datapack's dimension, the type or a
     *     generator id isn't on this server, or the world exists with a different type, generator or seed.
     */
    public static ServerLevel create(WorldCreator creator) {
        return backend().create(creator);
    }

    /** Loads a world created earlier and since unloaded, with its saved settings. Loaded already: returns it. */
    public static ServerLevel load(ResourceLocation key) {
        return backend().load(key);
    }

    /** Any loaded dimension by id, created by Hadron or not: {@code minecraft:the_nether}, {@code myplugin:mining}. */
    public static Optional<ServerLevel> get(ResourceLocation key) {
        return backend().get(key);
    }

    /** Every loaded dimension, the overworld first. */
    public static List<ServerLevel> list() {
        return backend().list();
    }

    /** Every world Hadron created and hasn't deleted, loaded or not, with its saved seed and spawn. */
    public static List<WorldCreator> created() {
        return backend().created();
    }

    /** Whether Hadron created this world and it hasn't been deleted, loaded or not. */
    public static boolean isCreated(ResourceLocation key) {
        return backend().isCreated(key);
    }

    /**
     * Unloads a created world, moving everyone in it to the overworld's spawn first. It stays created: {@link #load}
     * brings it back, and it stays unloaded after a restart until then.
     *
     * @param save whether to write its chunks first; false throws away everything since its last save.
     * @throws IllegalArgumentException for the overworld, or a mod's or datapack's dimension.
     */
    public static void unload(ServerLevel level, boolean save) {
        backend().unload(level, save, null, null);
    }

    /** {@link #unload(ServerLevel, boolean)}, moving everyone in it to {@code at} in {@code to}. */
    public static void unload(ServerLevel level, boolean save, ServerLevel to, Vec3 at) {
        if (to == null || at == null) throw new IllegalArgumentException("Give both a target world and a position");
        backend().unload(level, save, to, at);
    }

    /**
     * Unloads a created world without saving if it is loaded, then deletes its folder and forgets it. Cannot be
     * undone.
     *
     * @throws IllegalArgumentException if Hadron didn't create it.
     */
    public static void delete(ResourceLocation key) {
        backend().delete(key);
    }

    private static Backend backend() {
        Backend b = backend;
        if (b == null) {
            throw new IllegalStateException("No server is running, so there are no worlds yet; call Worlds from "
                    + "onEnable or later");
        }
        return b;
    }

    /** What a loader port implements. */
    public interface Backend {
        ServerLevel create(WorldCreator creator);

        ServerLevel load(ResourceLocation key);

        Optional<ServerLevel> get(ResourceLocation key);

        List<ServerLevel> list();

        List<WorldCreator> created();

        boolean isCreated(ResourceLocation key);

        /** {@code to} and {@code at} are both null for the overworld's spawn. */
        void unload(ServerLevel level, boolean save, ServerLevel to, Vec3 at);

        void delete(ResourceLocation key);
    }

    /** For the loader port only. */
    public static final class Port {
        private Port() {}

        /** Null between servers. */
        public static void install(Backend installed) {
            backend = installed;
        }
    }
}
