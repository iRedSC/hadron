package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.plugin.JavaPlugin;
import dev.iredsc.hadron.api.plugin.Task;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.joml.Vector3f;

/**
 * What a player sees, shown to them alone: a box's edges traced in particles, a refused action marked where it
 * happened with what refused it above the hotbar, and, for managers watching, every refusal near them.
 */
final class Feedback {
    /** The colours a box is traced in. */
    enum Look {
        /** Yellow: corners picked, not yet a region. */
        SELECTING(1f, 0.85f, 0.2f),
        /** Green: a region as it is saved. */
        REGION(0.3f, 1f, 0.4f),
        /** Red: the region that refuses, in {@code /region why}. */
        REFUSING(1f, 0.25f, 0.25f);

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
    /** One {@code [allow]} line a minute for each region and node, so testing an edge doesn't flood chat. */
    private static final int OFFER_EVERY = 20 * 60;
    /** How far {@code /region watch} shows refusals. */
    private static final int WATCH_RANGE = 32;

    private final JavaPlugin plugin;
    private final Map<UUID, Task> outlines = new HashMap<>();
    private final Map<UUID, Integer> lastRefusal = new HashMap<>();
    /** Who has {@code /region watch} on; off again when they leave. */
    private final Set<UUID> watchers = new HashSet<>();
    /** When each player, region and node last got its {@code [allow]} line. */
    private final Map<String, Integer> lastOffer = new HashMap<>();
    /** When each watcher last read a refusal at each spot. */
    private final Map<String, Integer> lastMark = new HashMap<>();

    Feedback(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Traces the box's edges for this player for five seconds, replacing any box shown to them before. Drawn only
     * while the player is in {@code world}, the box's own.
     */
    void outline(ServerPlayer player, ResourceLocation world, BoundingBox box, Look look) {
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
            if (player.serverLevel().dimension().location().equals(world)) trace(player, box, look);
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

    /**
     * Marks a refused action where it happened with smoke, and at most once a second says above the hotbar what
     * refused it: the region's name to a player, or, to someone who can change that region, the whole decision
     * ({@code spawn · everyone · entity.hurt.animal (build)}), with a chat line once a minute per region and node whose
     * {@code [allow]} fills in the command that would let it through.
     */
    void refused(ServerPlayer player, BlockPos where, Resolve.Decision no) {
        player.serverLevel().sendParticles(player, ParticleTypes.SMOKE, false, where.getX() + 0.5,
                where.getY() + 0.5, where.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.01);
        int now = now();
        Integer last = lastRefusal.get(player.getUUID());
        if (last != null && now - last < REFUSAL_EVERY) return;
        lastRefusal.put(player.getUUID(), now);
        if (!RegionsPlugin.canManage(player, no.region())) {
            player.displayClientMessage(Component.literal(no.region().id()).withStyle(ChatFormatting.RED), true);
            return;
        }
        player.displayClientMessage(Component.literal(no.line()).withStyle(ChatFormatting.RED), true);
        String key = player.getUUID() + " " + no.region().id() + " " + no.node().key();
        Integer offered = lastOffer.get(key);
        if (offered != null && now - offered < OFFER_EVERY) return;
        lastOffer.put(key, now);
        String allow = "/region allow " + no.region().id() + " " + no.subject().key() + " " + no.node().key();
        player.sendSystemMessage(Component.literal(no.line() + " ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[allow]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, allow))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(allow))))));
    }

    /** Turns {@code /region watch} on or off for this player; whether it is on now. */
    boolean toggleWatch(ServerPlayer player) {
        if (watchers.remove(player.getUUID())) return false;
        watchers.add(player.getUUID());
        return true;
    }

    /**
     * Shows a refusal to everyone watching who can change its region and is within {@value #WATCH_RANGE} blocks: a
     * dust mark coloured by subject (red a person, blue the world, purple unknown), and the decision above the hotbar,
     * at most once a second for each spot.
     */
    void watched(ServerLevel level, BlockPos at, Resolve.Decision no) {
        if (watchers.isEmpty()) return;
        int now = now();
        for (UUID id : watchers) {
            ServerPlayer p = plugin.getServer().getPlayer(id);
            if (p == null || p.serverLevel() != level || p.blockPosition().distSqr(at) > WATCH_RANGE * WATCH_RANGE
                    || !RegionsPlugin.canManage(p, no.region())) {
                continue;
            }
            level.sendParticles(p, mark(no.subject()), true, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 8,
                    0.3, 0.3, 0.3, 0);
            String key = id + " " + at.asLong();
            Integer last = lastMark.get(key);
            if (last != null && now - last < REFUSAL_EVERY) continue;
            lastMark.put(key, now);
            p.displayClientMessage(Component.literal(no.line()).withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }

    private static DustParticleOptions mark(Subject subject) {
        Vector3f colour = switch (subject) {
            case MEMBERS, EVERYONE -> new Vector3f(1f, 0.2f, 0.2f);
            case WORLD -> new Vector3f(0.3f, 0.5f, 1f);
            case UNKNOWN -> new Vector3f(0.7f, 0.3f, 1f);
        };
        return new DustParticleOptions(colour, 1.5f);
    }

    private int now() {
        return plugin.getServer().getMinecraftServer().getTickCount();
    }

    void forget(UUID player) {
        Task old = outlines.remove(player);
        if (old != null) old.cancel();
        lastRefusal.remove(player);
        watchers.remove(player);
        lastOffer.keySet().removeIf(k -> k.startsWith(player.toString()));
        lastMark.keySet().removeIf(k -> k.startsWith(player.toString()));
    }
}
