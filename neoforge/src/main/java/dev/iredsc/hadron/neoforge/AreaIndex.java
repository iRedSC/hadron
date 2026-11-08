package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.world.Area;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

    private AreaIndex(ServerLevel level) {
        this.level = level;
    }

    /** The hot path: whether any open area covers {@code pos}. */
    static boolean covers(ServerLevel level, BlockPos pos) {
        AreaIndex index = ((AreaLevel) level).hadron$areas();
        return index != null && index.contains(pos);
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
        Watched w = new Watched(index, plugin, box);
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
            return box;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            AreasPort.requireServerThread(index.level, "close an area");
            if (!open) return;
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
