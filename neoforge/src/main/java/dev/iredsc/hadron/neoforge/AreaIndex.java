package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.Area;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * One level's watched areas, kept on the level itself ({@link AreaLevel}) so the setBlock hook finds them with a field
 * read. A level nobody watches has none, and its writes pay only that read. Server thread only.
 *
 * <p>Each area is listed under every chunk it touches, so a lookup is one hash probe and a few box checks. An area
 * wider than {@link #WIDE_CHUNKS} chunks would make that list huge, so it goes in a short list checked on every lookup
 * instead.
 */
public final class AreaIndex {
    static final int WIDE_CHUNKS = 1024;
    private static final Watched[] NONE = new Watched[0];

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<Watched[]> byChunk = new Long2ObjectOpenHashMap<>();
    private Watched[] wide = NONE;
    private final List<Watched> all = new ArrayList<>();
    /** How deep vanilla's own work runs in this level now ({@link #enterVanillaWork}). */
    private int vanillaWork;

    private AreaIndex(ServerLevel level) {
        this.level = level;
    }

    /** The hot path: whether any open area covers {@code pos}. */
    static boolean covers(ServerLevel level, BlockPos pos) {
        AreaIndex index = ((AreaLevel) level).hadron$areas();
        return index != null && index.contains(pos);
    }

    /** Whether a change at {@code pos} with nobody behind it is an outsider's: watched, and not vanilla working. */
    static boolean outsiderAt(ServerLevel level, BlockPos pos) {
        AreaIndex index = ((AreaLevel) level).hadron$areas();
        return index != null && index.vanillaWork == 0 && index.contains(pos);
    }

    /**
     * Around vanilla working on its own with nobody on the stack: a vanilla block entity's tick or block event (a
     * furnace lighting, a piston pushing), a fluid's tick (water drying up), players waking in the morning, a new
     * chunk settling, the dragon fight. That stays silent even in a watched area: it is vanilla, already reported by
     * its own events where it matters, not a mod with a missing bridge. A mod's code that vanilla calls from inside it
     * is silent too; its writes would need their own bridge.
     *
     * <p>Kept on the level's index, so a level nobody watches pays one field read and marks nothing. Returns what to
     * hand {@link #exitVanillaWork}.
     */
    public static @Nullable AreaIndex enterVanillaWork(Level level) {
        AreaIndex index = level instanceof AreaLevel areas ? areas.hadron$areas() : null;
        if (index != null) index.vanillaWork++;
        return index;
    }

    public static void exitVanillaWork(@Nullable AreaIndex index) {
        if (index != null) index.vanillaWork--;
    }

    private boolean contains(BlockPos pos) {
        for (Watched w : wide) {
            if (w.box.isInside(pos)) return true;
        }
        Watched[] here = byChunk.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (here == null) return false;
        for (Watched w : here) {
            if (w.box.isInside(pos)) return true;
        }
        return false;
    }

    static Area watch(JavaPlugin plugin, ServerLevel level, BoundingBox box) {
        AreaLevel holder = (AreaLevel) level;
        AreaIndex index = holder.hadron$areas();
        if (index == null) {
            index = new AreaIndex(level);
            holder.hadron$setAreas(index);
        }
        Watched w = new Watched(index, plugin, copy(box));
        index.add(w);
        return w;
    }

    /** Closes every area {@code plugin} watches in this level. */
    static void closeAll(ServerLevel level, JavaPlugin plugin) {
        AreaIndex index = ((AreaLevel) level).hadron$areas();
        if (index == null) return;
        for (Watched w : List.copyOf(index.all)) {
            if (w.plugin == plugin) w.close();
        }
    }

    /** The level is unloading: every area in it closes. */
    static void closeAll(ServerLevel level) {
        AreaIndex index = ((AreaLevel) level).hadron$areas();
        if (index == null) return;
        for (Watched w : List.copyOf(index.all)) w.close();
    }

    /** BoundingBox can be moved in place; the index keeps its own, so a plugin moving the one it passed changes nothing. */
    private static BoundingBox copy(BoundingBox b) {
        return new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    private static long chunkSpan(BoundingBox box) {
        long xs = (box.maxX() >> 4) - (box.minX() >> 4) + 1L;
        long zs = (box.maxZ() >> 4) - (box.minZ() >> 4) + 1L;
        return xs * zs;
    }

    private void add(Watched w) {
        all.add(w);
        if (chunkSpan(w.box) > WIDE_CHUNKS) {
            wide = append(wide, w);
            return;
        }
        BoundingBox b = w.box;
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                Watched[] here = byChunk.get(key);
                byChunk.put(key, here == null ? new Watched[] {w} : append(here, w));
            }
        }
    }

    private void remove(Watched w) {
        all.remove(w);
        if (chunkSpan(w.box) > WIDE_CHUNKS) {
            wide = without(wide, w);
        } else {
            BoundingBox b = w.box;
            for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
                for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    Watched[] here = byChunk.get(key);
                    if (here == null) continue;
                    Watched[] left = without(here, w);
                    if (left.length == 0) byChunk.remove(key);
                    else byChunk.put(key, left);
                }
            }
        }
        // Nothing left: the level goes back to costing one field read per write.
        if (all.isEmpty()) ((AreaLevel) level).hadron$setAreas(null);
    }

    private static Watched[] append(Watched[] list, Watched w) {
        Watched[] out = Arrays.copyOf(list, list.length + 1);
        out[list.length] = w;
        return out;
    }

    private static Watched[] without(Watched[] list, Watched w) {
        int at = -1;
        for (int i = 0; i < list.length; i++) {
            if (list[i] == w) at = i;
        }
        if (at < 0) return list;
        Watched[] out = new Watched[list.length - 1];
        System.arraycopy(list, 0, out, 0, at);
        System.arraycopy(list, at + 1, out, at, list.length - at - 1);
        return out;
    }

    private static final class Watched implements Area {
        private final AreaIndex index;
        private final JavaPlugin plugin;
        private final BoundingBox box;
        private boolean open = true;

        Watched(AreaIndex index, JavaPlugin plugin, BoundingBox box) {
            this.index = index;
            this.plugin = plugin;
            this.box = box;
        }

        @Override
        public ServerLevel level() {
            return index.level;
        }

        @Override
        public BoundingBox box() {
            return copy(box);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            if (!open) return;
            AreasPort.requireServerThread(index.level, "close an area");
            open = false;
            index.remove(this);
        }

        @Override
        public String toString() {
            return "Area[" + plugin.getName() + " in " + index.level.dimension().location() + " " + box + "]";
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
