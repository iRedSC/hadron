package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.Task;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.joml.Vector3f;

/**
 * What a player sees, shown to them alone: a box's edges traced in particles, and a refused action marked where it
 * happened with the region's name above the hotbar.
 */
final class Feedback {
    /** The colours a box is traced in. */
    enum Look {
        /** Yellow: corners picked, not yet a region. */
        SELECTING(1f, 0.85f, 0.2f),
        /** Green: a region as it is saved. */
        REGION(0.3f, 1f, 0.4f);

        private final float r;
        private final float g;
        private final float b;

        Look(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        DustParticleOptions dust() {
            return new DustParticleOptions(new Vector3f(r, g, b), 1.2f);
        }
    }

    private static final int OUTLINE_TICKS = 100;
    private static final int OUTLINE_EVERY = 10;
    /** Most particles one trace sends, so a huge region is traced sparsely rather than flooding the client. */
    private static final int MAX_POINTS = 600;
    /** One refusal message a second per player, however many blocks a drag or a machine is refused. */
    private static final int REFUSAL_EVERY = 20;

    private final JavaPlugin plugin;
    private final Map<UUID, Task> outlines = new HashMap<>();
    private final Map<UUID, Integer> lastRefusal = new HashMap<>();

    Feedback(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Traces the box's edges for this player for five seconds, replacing any box shown to them before. */
    void outline(ServerPlayer player, BoundingBox box, Look look) {
        Task old = outlines.remove(player.getUUID());
        if (old != null) old.cancel();
        int[] left = {OUTLINE_TICKS / OUTLINE_EVERY};
        Task[] self = new Task[1];
        self[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (player.hasDisconnected() || left[0]-- <= 0) {
                self[0].cancel();
                outlines.remove(player.getUUID(), self[0]);
                return;
            }
            trace(player, box, look);
        }, 0, OUTLINE_EVERY);
        outlines.put(player.getUUID(), self[0]);
    }

    private static void trace(ServerPlayer player, BoundingBox b, Look look) {
        ServerLevel level = player.serverLevel();
        double x0 = b.minX(), y0 = b.minY(), z0 = b.minZ();
        double x1 = b.maxX() + 1, y1 = b.maxY() + 1, z1 = b.maxZ() + 1;
        double edges = 4.0 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(0.5, edges / MAX_POINTS);
        DustParticleOptions dust = look.dust();
        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) line(level, player, dust, step, x0, y, z, x1, y, z);
            for (double x : new double[] {x0, x1}) line(level, player, dust, step, x, y, z0, x, y, z1);
        }
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) line(level, player, dust, step, x, y0, z, x, y1, z);
        }
    }

    private static void line(ServerLevel level, ServerPlayer player, DustParticleOptions dust, double step,
            double ax, double ay, double az, double bx, double by, double bz) {
        double length = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        int points = Math.max(1, (int) Math.ceil(length / step));
        for (int i = 0; i <= points; i++) {
            double f = (double) i / points;
            // force = true: the outline shows from far away, as a large region's edges are.
            level.sendParticles(player, dust, true, ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, 1,
                    0, 0, 0, 0);
        }
    }

    /** Marks a refused action where it happened, and names the region at most once a second. */
    void refused(ServerPlayer player, BlockPos where, Region region) {
        player.serverLevel().sendParticles(player, ParticleTypes.SMOKE, false, where.getX() + 0.5,
                where.getY() + 0.5, where.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.01);
        int now = plugin.getServer().getMinecraftServer().getTickCount();
        Integer last = lastRefusal.get(player.getUUID());
        if (last != null && now - last < REFUSAL_EVERY) return;
        lastRefusal.put(player.getUUID(), now);
        player.displayClientMessage(Component.literal(region.id()).withStyle(ChatFormatting.RED), true);
    }

    void forget(UUID player) {
        Task old = outlines.remove(player);
        if (old != null) old.cancel();
        lastRefusal.remove(player);
    }
}
