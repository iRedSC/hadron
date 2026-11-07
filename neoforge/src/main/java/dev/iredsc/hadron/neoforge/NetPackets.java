package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.net.PacketEvent;
import dev.iredsc.hadron.api.net.PacketHandler;
import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Plugins' packet handlers, indexed by concrete packet class so a packet nobody listens to costs one map lookup and
 * no allocation. Registration is rare and copies; dispatch reads without locking from any thread.
 */
final class NetPackets {
    private NetPackets() {}

    private static final Handler[] NONE = new Handler[0];
    private static final Object LOCK = new Object();
    @StaticState(reason = "a sequence ordering handlers registered at one priority; it only needs to grow")
    private static long registered;

    static final Side OUT = new Side(true);
    static final Side IN = new Side(false);

    static final class Handler implements PacketRegistration {
        final JavaPlugin plugin;
        final Class<?> type;
        final EventPriority priority;
        final PacketHandler<?> body;
        final long order;
        final Side side;
        /** False once unregistered: a dispatch that took its snapshot earlier skips it. */
        volatile boolean active = true;

        Handler(JavaPlugin plugin, Class<?> type, EventPriority priority, PacketHandler<?> body, long order, Side side) {
            this.plugin = plugin;
            this.type = type;
            this.priority = priority;
            this.body = body;
            this.order = order;
            this.side = side;
        }

        @Override
        public void unregister() {
            synchronized (LOCK) {
                active = false;
                side.all.remove(this);
                side.changed();
            }
        }
    }

    static final class Side {
        final boolean outgoing;
        /** Guarded by LOCK. */
        final List<Handler> all = new ArrayList<Handler>();
        /**
         * Concrete packet class to its handlers in run order; filled on first sight, replaced whole on every change.
         * Resolving happens outside the map's own locking, so a registration racing a first-seen packet can't
         * deadlock: a resolve that raced a change lands in the map being thrown away.
         */
        volatile ConcurrentHashMap<Class<?>, Handler[]> byClass = new ConcurrentHashMap<Class<?>, Handler[]>();
        volatile boolean any;

        Side(boolean outgoing) {
            this.outgoing = outgoing;
        }

        /** Under LOCK. */
        void changed() {
            byClass = new ConcurrentHashMap<Class<?>, Handler[]>();
            any = !all.isEmpty();
        }

        /** Null when nobody listens to this class. */
        Handler @Nullable [] handlers(Class<?> type) {
            if (!any) return null;
            ConcurrentHashMap<Class<?>, Handler[]> map = byClass;
            Handler[] h = map.get(type);
            if (h == null) {
                h = resolve(type);
                map.putIfAbsent(type, h);
            }
            return h.length == 0 ? null : h;
        }

        private Handler[] resolve(Class<?> type) {
            synchronized (LOCK) {
                List<Handler> out = new ArrayList<Handler>();
                for (Handler h : all) if (h.type.isAssignableFrom(type)) out.add(h);
                if (out.isEmpty()) return NONE;
                out.sort(Comparator.comparingInt((Handler h) -> h.priority.ordinal()).thenComparingLong(h -> h.order));
                return out.toArray(NONE);
            }
        }
    }

    static PacketRegistration listen(JavaPlugin plugin, Class<?> type, EventPriority priority, PacketHandler<?> body,
            boolean outgoing) {
        if (plugin == null || type == null || priority == null || body == null) {
            throw new IllegalArgumentException("Packets.on" + (outgoing ? "Send" : "Receive")
                    + " needs a plugin, a packet class, a priority and a handler; one was null");
        }
        if (ClientboundBundlePacket.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Bundles are delivered as their parts: listen for the packets inside "
                    + "it instead, such as ClientboundAddEntityPacket or ClientboundSetEntityDataPacket");
        }
        if (ClientboundDisconnectPacket.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException("Disconnect packets can't be intercepted; listen for PlayerKickEvent");
        }
        Side side = outgoing ? OUT : IN;
        synchronized (LOCK) {
            if (!plugin.isEnabled()) {
                throw new IllegalStateException(plugin.getName() + " can't register packet handlers while it isn't "
                        + "enabled; register them in onEnable");
            }
            RUNNING.computeIfAbsent(plugin, p -> new int[1]);
            Handler h = new Handler(plugin, type, priority, body, registered++, side);
            side.all.add(h);
            side.changed();
            return h;
        }
    }

    /** Each plugin's handler calls in progress, so disabling can wait for them; a count is its own monitor. */
    @StaticState(reason = "each plugin's in-flight handler count, removed in its disable hook")
    private static final ConcurrentHashMap<JavaPlugin, int[]> RUNNING = new ConcurrentHashMap<>();
    /** The plugin whose handler this thread is inside, so a handler that disables its own plugin doesn't wait on itself. */
    @StaticState(reason = "the plugin whose handler this thread is running, restored in a finally when the handler returns")
    private static final ThreadLocal<JavaPlugin> INSIDE = new ThreadLocal<>();

    /**
     * Before-disable hook: waits up to {@code millis} for the plugin's handler calls already running, typically on a
     * network thread, so none overlaps its onDisable. New calls are skipped already, since the plugin no longer counts
     * as enabled.
     */
    static void awaitRunning(JavaPlugin plugin, long millis) {
        int[] count = RUNNING.get(plugin);
        if (count == null) return;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (count) {
            int own = INSIDE.get() == plugin ? 1 : 0;
            while (count[0] > own) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    plugin.getLogger().warn(count[0] - own + " packet handler call(s) still running after the disable "
                            + "grace period; disabling anyway");
                    return;
                }
                try {
                    count.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Disable hook: drops every handler the plugin registered, so none of its classes stay reachable. */
    static void remove(JavaPlugin plugin) {
        synchronized (LOCK) {
            RUNNING.remove(plugin);
            for (Side side : new Side[] {OUT, IN}) {
                boolean changed = side.all.removeIf(h -> {
                    if (h.plugin != plugin) return false;
                    h.active = false;
                    return true;
                });
                if (changed) side.changed();
            }
        }
    }

    // ---- Dispatch ----

    /** Whether the send hook has anything to do: plugins' handlers, hidden entities or name tags. */
    static boolean sendActive() {
        return OUT.any || NetVisibility.active() || NetNames.tagsActive();
    }

    /**
     * A packet on its way to a player in game. Returns what to send instead, the same packet if nothing changed, or
     * null to send nothing.
     */
    static @Nullable Packet<?> outgoing(ServerPlayer player, Packet<?> packet) {
        if (packet.isTerminal()) return packet;
        if (packet instanceof ClientboundBundlePacket bundle) return bundle(player, bundle);
        return single(player, packet);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Packet<?> bundle(ServerPlayer player, ClientboundBundlePacket bundle) {
        List<Packet<? super ClientGamePacketListener>> parts = null;
        int index = 0;
        for (Packet<? super ClientGamePacketListener> part : bundle.subPackets()) {
            Packet<?> out = single(player, part);
            if (out != part && parts == null) {
                // First change: copy the parts before it, then build a new bundle from here on.
                parts = new ArrayList<Packet<? super ClientGamePacketListener>>();
                int i = 0;
                for (Packet<? super ClientGamePacketListener> earlier : bundle.subPackets()) {
                    if (i++ == index) break;
                    parts.add(earlier);
                }
            }
            if (parts != null && out != null) {
                if (out instanceof ClientboundBundlePacket nested) {
                    for (Packet<? super ClientGamePacketListener> p : nested.subPackets()) parts.add(p);
                } else {
                    parts.add((Packet<? super ClientGamePacketListener>) out);
                }
            }
            index++;
        }
        if (parts == null) return bundle;
        return parts.isEmpty() ? null : new ClientboundBundlePacket(parts);
    }

    private static @Nullable Packet<?> single(ServerPlayer player, Packet<?> packet) {
        Handler[] handlers = OUT.handlers(packet.getClass());
        if (handlers != null) {
            packet = run(handlers, player, packet, true);
            if (packet == null) return null;
        }
        // Hadron's own rules last, so no handler can put back what visibility removed.
        packet = NetVisibility.filter(player, packet);
        if (packet == null) return null;
        return NetNames.rewrite(player, packet);
    }

    /** A packet from a player in game, before the server handles it. Null to drop it. */
    static @Nullable Packet<?> incoming(ServerPlayer player, Packet<?> packet) {
        Handler[] handlers = IN.handlers(packet.getClass());
        return handlers == null ? packet : run(handlers, player, packet, false);
    }

    static boolean receiveActive() {
        return IN.any;
    }

    private static @Nullable Packet<?> run(Handler[] handlers, ServerPlayer player, Packet<?> packet, boolean outgoing) {
        Event event = new Event(player, packet, outgoing);
        for (Handler h : handlers) {
            // A dispatch may hold a snapshot from before an unregister or a disable. The plugin stops counting as
            // enabled before its onDisable runs, so no handler runs against what onDisable closed.
            // Counted before the enabled check, so a disable that sees no call running can't miss one starting.
            // Made at registration and dropped at disable, so a stale snapshot never brings a disabled plugin back.
            int[] count = RUNNING.get(h.plugin);
            if (count == null) continue;
            synchronized (count) {
                count[0]++;
            }
            JavaPlugin outer = INSIDE.get();
            try {
                if (!h.active || !h.plugin.isEnabled()) continue;
                event.current = h;
                INSIDE.set(h.plugin);
                @SuppressWarnings("unchecked")
                PacketHandler<Packet<?>> body = (PacketHandler<Packet<?>>) h.body;
                body.handle(event);
            } catch (Throwable t) {
                h.plugin.getLogger().error("A packet handler for " + packet.getClass().getSimpleName()
                        + (outgoing ? " sent to " : " from ") + player.getGameProfile().getName() + " threw", t);
            } finally {
                INSIDE.set(outer);
                synchronized (count) {
                    if (--count[0] == 0) count.notifyAll();
                }
            }
        }
        return event.cancelled ? null : event.packet;
    }

    private static final class Event implements PacketEvent<Packet<?>> {
        private final ServerPlayer player;
        private final boolean outgoing;
        Packet<?> packet;
        boolean cancelled;
        @Nullable Handler current;

        Event(ServerPlayer player, Packet<?> packet, boolean outgoing) {
            this.player = player;
            this.packet = packet;
            this.outgoing = outgoing;
        }

        @Override
        public ServerPlayer getPlayer() {
            return player;
        }

        @Override
        public Packet<?> getPacket() {
            return packet;
        }

        @Override
        public void setPacket(Packet<?> replacement) {
            looking("replace");
            if (replacement == null) {
                throw new IllegalArgumentException("setPacket(null): to drop the packet, use setCancelled(true)");
            }
            PacketFlow wanted = outgoing ? PacketFlow.CLIENTBOUND : PacketFlow.SERVERBOUND;
            if (replacement.type().flow() != wanted) {
                throw new IllegalArgumentException(replacement.getClass().getSimpleName() + " is "
                        + replacement.type().flow().id() + "; a packet " + (outgoing ? "sent to" : "received from")
                        + " a player can only be replaced with a " + wanted.id() + " one");
            }
            if (!outgoing && replacement instanceof ClientboundBundlePacket) {
                throw new IllegalArgumentException("A received packet can't be replaced with a bundle");
            }
            // The handlers still to run were chosen for this packet's class, and each casts to it.
            if (replacement.getClass() != packet.getClass()) {
                throw new IllegalArgumentException("setPacket needs another " + packet.getClass().getSimpleName()
                        + ", not a " + replacement.getClass().getSimpleName() + ": later handlers expect the kind "
                        + "they registered for. To " + (outgoing ? "send" : "handle") + " a different kind of packet, "
                        + "cancel this one with setCancelled(true) and " + (outgoing
                                ? "send yours with player.connection.send"
                                : "have the server handle yours from runOnServerThread with yours.handle(player.connection)"));
            }
            packet = replacement;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean cancel) {
            looking("cancel");
            cancelled = cancel;
        }

        @Override
        public boolean isOutgoing() {
            return outgoing;
        }

        @Override
        public void runOnServerThread(Runnable task) {
            if (task == null) throw new IllegalArgumentException("runOnServerThread(null): pass the code to run");
            // A plugin disabling, or a server stopping, mid-packet: the task is skipped, as documented.
            Handler handler = handler();
            if (!handler.plugin.isEnabled() || Hadron.Lifecycle.running() == null) return;
            try {
                Hadron.getScheduler().runTask(handler.plugin, task);
            } catch (IllegalStateException disabledMeanwhile) {
                // Lost the race with the plugin's disable; skipped as well.
            }
        }

        /** The handler being called now; a packet event is only ever seen from inside one. */
        private Handler handler() {
            Handler handler = current;
            if (handler == null) {
                throw new IllegalStateException("This packet event was kept past its handler; read and change it only "
                        + "inside the handler that received it");
            }
            return handler;
        }

        private void looking(String what) {
            if (handler().priority == EventPriority.MONITOR) {
                throw new IllegalStateException("A MONITOR packet handler can't " + what + " the packet; register it "
                        + "at HIGHEST to change packets");
            }
        }
    }
}
