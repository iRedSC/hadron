package dev.iredsc.hadron.api.world;

import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.checks.StaticState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Places where a block change matters to a plugin even when nobody is behind it. A regions plugin watches each region
 * it protects, a logging plugin each area it logs.
 *
 * <p>Hadron fires block events only for changes someone made, so it doesn't slow every {@code setBlock} on the
 * server. A mod without a bridge that changes blocks from its own queued work has nobody on the actor stack, so
 * outside a watched area that change fires nothing. Inside one it fires as {@link Actor.Unknown}, which protection
 * treats as an outsider and denies. A missing bridge then shows up as a machine refused in its owner's region, never
 * as a grief nobody saw.
 *
 * <pre>{@code
 * Area spawn = Areas.watch(this, level, BoundingBox.fromCorners(new Vec3i(-50, -64, -50), new Vec3i(50, 320, 50)));
 * ...
 * spawn.close(); // when the region is deleted
 * }</pre>
 *
 * <p>An area lasts until it is closed, its plugin disables, or its world unloads, so a plugin watches its regions
 * again in {@code onEnable}. Call on the server thread.
 */
public final class Areas {
    @StaticState(reason = "the port installs the running server's areas when it starts and clears them when it stops")
    private static volatile @Nullable Backend backend;

    private Areas() {}

    /**
     * Watches the box, both corners included, so changes in it with nobody behind them fire as
     * {@link Actor.Unknown}. Boxes may overlap, within one plugin or across plugins.
     *
     * @throws IllegalStateException if called off the server thread, or the plugin isn't enabled.
     * @throws IllegalArgumentException if the world isn't loaded.
     */
    public static Area watch(JavaPlugin plugin, ServerLevel level, BoundingBox box) {
        return backend().watch(plugin, level, box);
    }

    /**
     * Whether any plugin watches this position: a change there with nobody behind it fires as Unknown, unless it is
     * vanilla working on its own (a furnace, a piston, water flowing).
     *
     * @throws IllegalStateException if called off the server thread.
     */
    public static boolean isWatched(ServerLevel level, BlockPos pos) {
        return backend().isWatched(level, pos);
    }

    private static Backend backend() {
        Backend b = backend;
        if (b == null) {
            throw new IllegalStateException("No server is running, so no area can be watched yet; call Areas from "
                    + "onEnable or later");
        }
        return b;
    }

    /** For the loader port only. */
    public interface Backend {
        Area watch(JavaPlugin plugin, ServerLevel level, BoundingBox box);

        boolean isWatched(ServerLevel level, BlockPos pos);
    }

    /** For the loader port only. */
    public static final class Port {
        private Port() {}

        /** Null between servers. Takes the {@link HostKey}. */
        public static void install(HostKey key, @Nullable Backend installed) {
            HostKey.require(key, "Areas.Port.install", "watch an area with Areas.watch");
            backend = installed;
        }
    }
}
