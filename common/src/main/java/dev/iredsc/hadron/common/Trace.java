package dev.iredsc.hadron.common;

import org.jspecify.annotations.Nullable;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Operation;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.player.PlayerEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.api.event.world.WorldEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /hadron trace}: shows the sender each event as it fires, after every plugin handled it, so an owner or agent
 * sees on a live server who did what and what came of it. One trace per sender; starting another replaces it. A
 * trace is an {@link EventBus.Observer}, so only the events it hears are built while it runs, and nothing at all
 * changes once the last one stops.
 */
final class Trace {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron trace");
    private static final String PACKAGE = "dev.iredsc.hadron.api.event";
    /** The families an owner can trace whole, by their package under {@link #PACKAGE}. */
    static final List<String> FAMILIES =
            List.of("block", "entity", "inventory", "item", "player", "resource", "server", "world");
    static final List<String> KINDS =
            List.of("player", "fakeplayer", "machine", "entity", "server", "nature", "unknown");
    /** Lines each sender gets per second; the rest are counted and summarised once a second. */
    static final int PER_SECOND = 10;
    static final int WINDOW_TICKS = 20;
    /** A trace stops by itself after this long, so one left running never floods a forgotten console. */
    static final int LIMIT_TICKS = 20 * 60 * 5;
    static final String LIMIT_TEXT = "5 min";
    /** Lines kept for a console or RCON sender to read back with {@code /hadron trace}. */
    private static final int CONSOLE_BACKLOG = 50;
    private static final Object CONSOLE = new Object();

    private final EventBus bus;
    private final MinecraftServer minecraft;
    /** By the sender's UUID, or {@link #CONSOLE} for every sender that isn't a player. Server thread only. */
    private final Map<Object, Session> sessions = new LinkedHashMap<>();

    Trace(EventBus bus, MinecraftServer minecraft) {
        this.bus = bus;
        this.minecraft = minecraft;
    }

    // ---- What to trace ----

    /** The events a trace hears: one class and its subclasses, a family, or all. */
    record Target(String label, Predicate<Class<?>> hears) {}

    /** Resolves {@code BlockBreakEvent}, {@code block}, {@code all}, or a plugin event's full class name. */
    static Target target(String what, Set<Class<?>> seen, java.util.function.Function<String, Class<?>> plugins)
            throws CommandSyntaxException {
        String lower = what.toLowerCase(Locale.ROOT);
        if (lower.equals("all")) return new Target("all events", Event.class::isAssignableFrom);
        if (FAMILIES.contains(lower)) {
            String pkg = PACKAGE + "." + lower;
            return new Target(lower + " events", c -> Event.class.isAssignableFrom(c) && inPackage(c, pkg));
        }
        Class<?> type = null;
        for (Class<?> c : seen) {
            if (c.getSimpleName().equalsIgnoreCase(what) || c.getName().equals(what)) type = c;
        }
        if (type == null) type = load(what.contains(".") ? what : null);
        for (String family : FAMILIES) {
            if (type != null) break;
            type = load(PACKAGE + "." + family + "." + what);
            if (type == null) type = load(PACKAGE + "." + family + "." + capitalized(what));
        }
        if (type == null && what.contains(".")) type = plugins.apply(what);
        if (type == null || !Event.class.isAssignableFrom(type)) {
            throw fail("No event named " + what + ". Give a class such as BlockBreakEvent (a parent such as "
                    + "BlockChangeEvent traces its subclasses too), a plugin event's full class name, a family ("
                    + String.join(", ", FAMILIES) + "), or all");
        }
        if (type.getClassLoader() == Event.class.getClassLoader()) {
            Class<?> found = type;
            return new Target(found.getSimpleName(), found::isAssignableFrom);
        }
        // A plugin's own event: matched by name, so the trace follows the plugin across reloads (each version has its
        // own class) and never holds an unloaded version's class loader.
        String name = type.getName();
        return new Target(type.getSimpleName(), c -> named(c, name));
    }

    /** Whether the class, a superclass or an interface it implements has this name. */
    private static boolean named(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(name)) return true;
            for (Class<?> i : k.getInterfaces()) if (named(i, name)) return true;
        }
        return false;
    }

    private static boolean inPackage(Class<?> c, String pkg) {
        String name = c.getPackageName();
        return name.equals(pkg) || name.startsWith(pkg + ".");
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static @Nullable Class<?> load(@Nullable String name) {
        if (name == null) return null;
        try {
            return Class.forName(name, false, Event.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** Narrows a trace to one actor, a world, or a radius around where it was started. All must match. */
    record Filters(@Nullable String byKind, @Nullable UUID byPlayer, @Nullable String byName,
            @Nullable ResourceKey<Level> world, @Nullable ServerLevel nearLevel, @Nullable Vec3 nearPos, double radius) {
        static final Filters NONE = new Filters(null, null, null, null, null, null, 0);

        boolean none() {
            return byKind == null && byPlayer == null && world == null && nearPos == null;
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            if (byKind != null) out.append(" by ").append(byKind);
            if (byName != null) out.append(" by ").append(byName);
            if (world != null) out.append(" in ").append(shortId(world.location()));
            if (nearPos != null) out.append(" within ").append(trim(radius)).append(" blocks");
            return out.toString();
        }
    }

    /** Parses {@code by <player|kind>}, {@code in <world>} and {@code near <blocks>}, in any order. */
    static Filters filters(String text, CommandSourceStack source) throws CommandSyntaxException {
        if (text == null || text.isBlank()) return Filters.NONE;
        String[] words = text.trim().split("\\s+");
        String kind = null, name = null;
        UUID player = null;
        ResourceKey<Level> world = null;
        Vec3 near = null;
        ServerLevel nearLevel = null;
        double radius = 0;
        for (int i = 0; i < words.length; i += 2) {
            String key = words[i].toLowerCase(Locale.ROOT);
            if (i + 1 >= words.length) throw fail(usage(key + " needs a value"));
            String value = words[i + 1];
            switch (key) {
                case "by" -> {
                    String v = value.toLowerCase(Locale.ROOT);
                    if (KINDS.contains(v)) {
                        kind = v;
                    } else {
                        ServerPlayer p = source.getServer().getPlayerList().getPlayerByName(value);
                        if (p == null) {
                            throw fail("No player named " + value + " is online. by takes an online player or an "
                                    + "actor kind: " + String.join(", ", KINDS));
                        }
                        player = p.getUUID();
                        name = p.getGameProfile().getName();
                    }
                }
                case "in" -> {
                    ResourceLocation id = ResourceLocation.tryParse(value);
                    ResourceKey<Level> key2 = id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
                    if (key2 == null || source.getServer().getLevel(key2) == null) {
                        throw fail("No world named " + value + ". Worlds: " + worlds(source.getServer()));
                    }
                    world = key2;
                }
                case "near" -> {
                    try {
                        radius = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        radius = -1;
                    }
                    if (!(radius > 0)) throw fail("near takes a radius in blocks, such as near 16");
                    near = source.getPosition();
                    nearLevel = source.getLevel();
                }
                default -> throw fail(usage("Unknown filter " + words[i]));
            }
        }
        return new Filters(kind, player, name, world, nearLevel, near, radius);
    }

    private static String usage(String why) {
        return why + ". Filters: by <player or " + String.join("|", KINDS) + ">, in <world>, near <blocks>";
    }

    static List<String> worlds(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (ResourceKey<Level> key : server.levelKeys()) out.add(shortId(key.location()));
        return out;
    }

    private static CommandSyntaxException fail(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    // ---- Starting and stopping ----

    /** Starts (or replaces) the sender's trace and tells them what it covers and when it ends. */
    void start(CommandSourceStack source, String what, Target target, Filters filters) {
        Object key = key(source);
        Session old = current(source);
        if (old != null) {
            sessions.remove(key);
            old.close();
        }
        Session s = new Session(key, source.getPlayer(), what, target, filters, minecraft.getTickCount());
        sessions.put(key, s);
        bus.observe(s);
        MutableComponent line = Component.literal("Tracing " + target.label() + filters.describe() + " for "
                + LIMIT_TEXT + ", up to " + PER_SECOND + " lines a second. ").withStyle(ChatFormatting.AQUA);
        source.sendSystemMessage(line.append(offButton()));
    }

    /** Stops the sender's trace; false if there was none. */
    boolean stop(CommandSourceStack source) {
        Session s = current(source);
        if (s == null) return false;
        sessions.remove(s.key);
        s.close();
        source.sendSystemMessage(Component.literal("Trace of " + s.target.label() + " off, " + s.shown
                + " shown" + (s.summarised > 0 ? ", " + s.summarised + " summarised" : "")).withStyle(
                ChatFormatting.GRAY));
        return true;
    }

    /** What the sender is tracing; a console sender also reads back the lines it was sent since it last asked. */
    void status(CommandSourceStack source) {
        Session s = current(source);
        if (s != null && s.key == CONSOLE) {
            List<Component> backlog = s.drainBacklog();
            for (Component c : backlog) source.sendSystemMessage(c);
        }
        if (s == null) {
            source.sendSystemMessage(Component.literal("Not tracing. ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("/hadron trace <event|" + String.join("|", FAMILIES) + "|all> "
                            + "[by <who>] [in <world>] [near <blocks>]").withStyle(Style.EMPTY
                            .withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/hadron trace ")))));
            return;
        }
        int left = Math.max(0, s.startTick + LIMIT_TICKS - minecraft.getTickCount());
        source.sendSystemMessage(Component.literal("Tracing " + s.target.label() + s.filters.describe() + ", "
                + minutes(left) + " left, " + s.shown + " shown"
                + (s.summarised > 0 ? ", " + s.summarised + " summarised" : "") + ". ")
                .withStyle(ChatFormatting.AQUA).append(offButton()));
    }

    /** End of every server tick: delivers lines fired off the server thread, summarises, and ends traces. */
    void tick() {
        if (sessions.isEmpty()) return;
        int now = minecraft.getTickCount();
        for (var it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            if (s.player != null && minecraft.getPlayerList().getPlayer(s.player.getUUID()) != s.player) {
                it.remove();
                s.close();
                continue;
            }
            s.flush();
            if (now - s.windowStart >= WINDOW_TICKS) s.nextWindow(now);
            if (now - s.startTick >= LIMIT_TICKS) {
                it.remove();
                s.close();
                s.send(Component.literal("Trace of " + s.target.label() + " ended after " + LIMIT_TEXT + ". ")
                        .withStyle(ChatFormatting.GRAY).append(Component.literal("[Again]").withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                        "/hadron trace " + s.command)))));
            }
        }
    }

    /** At server stop. */
    void stopAll() {
        for (Session s : sessions.values()) s.close();
        sessions.clear();
    }

    /** The sender's live trace; one left by an earlier login of the same player is ended, not reused. */
    private @Nullable Session current(CommandSourceStack source) {
        Object key = key(source);
        Session s = sessions.get(key);
        if (s != null && s.player != source.getPlayer()) {
            sessions.remove(key);
            s.close();
            return null;
        }
        return s;
    }

    private static Object key(CommandSourceStack source) {
        ServerPlayer p = source.getPlayer();
        return p == null ? CONSOLE : p.getUUID();
    }

    private static MutableComponent offButton() {
        return Component.literal("[Stop]").withStyle(Style.EMPTY.withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/hadron trace off"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("/hadron trace off"))));
    }

    private static String minutes(int ticks) {
        int seconds = ticks / 20;
        return seconds >= 60 ? (seconds + 59) / 60 + " min" : seconds + " s";
    }

    // ---- One sender's trace ----

    /** What {@link Session#before} keeps for {@link Session#after}. */
    private record Seen(@Nullable Where where, Object[] values, boolean wasCancelled) {}

    private final class Session implements EventBus.Observer {
        final Object key;
        /** Null for the console; the exact player object, so a rejoin under the same name starts clean. */
        final ServerPlayer player;
        final String command;
        final Target target;
        final Filters filters;
        final int startTick;
        volatile boolean closed;
        volatile int windowStart;
        /** Lines given out this window, across threads. */
        final AtomicInteger used = new AtomicInteger();
        final Map<String, AtomicInteger> dropped = new ConcurrentHashMap<>();
        /** Lines built off the server thread, sent at the next tick. */
        final ConcurrentLinkedQueue<Component> pending = new ConcurrentLinkedQueue<>();
        private final Deque<Component> backlog = new ArrayDeque<>();
        int shown;
        int summarised;

        Session(Object key, ServerPlayer player, String command, Target target, Filters filters, int now) {
            this.key = key;
            this.player = player;
            this.command = command;
            this.target = target;
            this.filters = filters;
            this.startTick = now;
            this.windowStart = now;
        }

        void close() {
            closed = true;
            bus.unobserve(this);
        }

        @Override
        public boolean hears(Class<?> type) {
            return target.hears().test(type);
        }

        @Override
        public @Nullable Object before(Event event) {
            if (closed) return null;
            Actor actor = actor(event);
            Where where = where(event, actor);
            if (!matches(actor, where)) return null;
            if (used.incrementAndGet() > PER_SECOND) {
                dropped.computeIfAbsent(event.getEventName(), k -> new AtomicInteger()).incrementAndGet();
                return null;
            }
            return new Seen(where, snapshot(event), event instanceof Cancellable c && c.isCancelled());
        }

        @Override
        public void after(Event event, Object state, @Nullable String canceller) {
            if (closed) return;
            Component line = line(event, (Seen) state, canceller);
            if (minecraft.isSameThread()) deliver(line);
            else pending.add(line);
        }

        private boolean matches(@Nullable Actor actor, @Nullable Where where) {
            Filters f = filters;
            if (f.none()) return true;
            if (f.byKind != null && (actor == null || !kind(actor).equalsIgnoreCase(f.byKind))) return false;
            if (f.byPlayer != null) {
                boolean direct = actor instanceof Actor.Player p && p.player().getUUID().equals(f.byPlayer);
                if (!direct && (actor == null || !actor.actingFor().equals(Optional.of(f.byPlayer)))) return false;
            }
            if (f.world != null && (where == null || where.level == null || where.level.dimension() != f.world)) {
                return false;
            }
            if (f.nearPos != null) {
                if (where == null || where.pos == null || where.level != f.nearLevel) return false;
                if (where.pos.distanceToSqr(f.nearPos) > f.radius * f.radius) return false;
            }
            return true;
        }

        void flush() {
            for (Component c; (c = pending.poll()) != null; ) deliver(c);
        }

        void nextWindow(int now) {
            windowStart = now;
            used.set(0);
            if (dropped.isEmpty()) return;
            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (var it = dropped.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                it.remove();
                int n = e.getValue().get();
                counts.put(e.getKey(), n);
                total += n;
            }
            summarised += total;
            List<String> parts = new ArrayList<>();
            counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                    .forEach(e -> parts.add(e.getKey() + " " + e.getValue()));
            if (counts.size() > 3) parts.add("…");
            send(Component.literal("+" + total + " more in the last second (" + String.join(", ", parts) + ")")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withHoverEvent(new HoverEvent(
                            HoverEvent.Action.SHOW_TEXT, Component.literal("Over " + PER_SECOND + " a second; "
                            + "narrow it with by, in or near")))));
        }

        /** An event line: counts as shown. */
        void deliver(Component line) {
            shown++;
            send(line);
        }

        private void send(Component line) {
            if (player != null) {
                player.sendSystemMessage(line);
                return;
            }
            LOG.info(line.getString());
            synchronized (backlog) {
                backlog.addLast(line);
                while (backlog.size() > CONSOLE_BACKLOG) backlog.removeFirst();
            }
        }

        List<Component> drainBacklog() {
            synchronized (backlog) {
                List<Component> out = new ArrayList<>(backlog);
                backlog.clear();
                return out;
            }
        }
    }

    // ---- Reading an event ----

    /** Where an event happened: a world, and a point in it when there is one. */
    private record Where(ServerLevel level, @Nullable Vec3 pos, String text) {}

    private static @Nullable Actor actor(Event event) {
        if (event instanceof ActionEvent a) return a.getActor();
        if (event instanceof PlayerEvent p) return new Actor.Player(p.getPlayer());
        return null;
    }

    private static @Nullable Where where(Event event, @Nullable Actor actor) {
        if (event instanceof BlockEvent b) return at(b.getLevel(), Vec3.atLowerCornerOf(b.getPos()));
        if (event instanceof EntityEvent e) return at(e.getLevel(), e.getPosition());
        if (event instanceof ExplosionEvent x) return at(x.getLevel(), x.getLocation());
        if (event instanceof ExplosionPrimeEvent x) return at(x.getLevel(), x.getLocation());
        if (event instanceof WorldEvent w) {
            Optional<BoundingBox> area = w.getArea();
            if (area.isPresent()) return at(w.getLevel(), Vec3.atLowerCornerOf(area.get().getCenter()));
            return new Where(w.getLevel(), null, shortId(w.getLevel().dimension().location()));
        }
        if (event instanceof PlayerEvent p) return at(p.getPlayer().serverLevel(), p.getPlayer().position());
        if (actor != null) return actorWhere(actor);
        return null;
    }

    private static @Nullable Where actorWhere(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> at(p.player().serverLevel(), p.player().position());
            case Actor.FakePlayer f -> at(f.fake().serverLevel(), f.fake().position());
            case Actor.Machine m -> at(m.level(), Vec3.atLowerCornerOf(m.pos()));
            case Actor.EntityActor e -> e.entity().level() instanceof ServerLevel l ? at(l, e.entity().position()) : null;
            case Actor.Server s -> null;
            case Actor.Nature n -> null;
            case Actor.Unknown u -> null;
        };
    }

    private static Where at(ServerLevel level, Vec3 pos) {
        BlockPos b = BlockPos.containing(pos);
        return new Where(level, pos, shortId(level.dimension().location()) + " " + b.getX() + " " + b.getY() + " "
                + b.getZ());
    }

    static String kind(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> "player";
            case Actor.FakePlayer f -> "fakeplayer";
            case Actor.Machine m -> "machine";
            case Actor.EntityActor e -> "entity";
            case Actor.Server s -> "server";
            case Actor.Nature n -> "nature";
            case Actor.Unknown u -> "unknown";
        };
    }

    private String who(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> "Player " + p.player().getGameProfile().getName();
            case Actor.FakePlayer f -> "FakePlayer " + f.fake().getGameProfile().getName() + owner(actor);
            case Actor.Machine m -> "Machine " + machine(m) + owner(actor);
            case Actor.EntityActor e -> "Entity " + BuiltInRegistries.ENTITY_TYPE.getKey(e.entity().getType())
                    .getPath() + owner(actor);
            case Actor.Server s -> "Server " + s.name();
            case Actor.Nature n -> "Nature";
            case Actor.Unknown u -> "Unknown";
        };
    }

    /** The block a machine is, read only where that's safe: on the server thread, in a loaded chunk. */
    private String machine(Actor.Machine m) {
        if (minecraft.isSameThread() && m.level().isLoaded(m.pos())) {
            return BuiltInRegistries.BLOCK.getKey(m.level().getBlockState(m.pos()).getBlock()).getPath();
        }
        return "at " + m.pos().toShortString();
    }

    private String owner(Actor actor) {
        return actor.actingFor().map(id -> " for " + name(id)).orElse(", no owner");
    }

    private String name(UUID id) {
        ServerPlayer p = minecraft.getPlayerList().getPlayer(id);
        return p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
    }

    private static String shortId(ResourceLocation id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }

    // ---- What plugins changed ----

    /** A property a plugin can set: {@code setDamage} with its {@code getDamage}. */
    private record Prop(String name, Method getter) {}

    private static final ClassValue<Prop[]> PROPS = new ClassValue<>() {
        @Override
        protected Prop[] computeValue(Class<?> type) {
            Map<String, Prop> out = new LinkedHashMap<>();
            for (Method set : type.getMethods()) {
                String n = set.getName();
                if (!n.startsWith("set") || n.length() < 4 || n.equals("setCancelled") || set.getParameterCount() != 1
                        || Modifier.isStatic(set.getModifiers())) continue;
                String prop = n.substring(3);
                Method get = getter(type, "get" + prop);
                if (get == null) get = getter(type, "is" + prop);
                if (get != null) out.putIfAbsent(prop, new Prop(Character.toLowerCase(prop.charAt(0)) + prop.substring(1), get));
            }
            return out.values().toArray(new Prop[0]);
        }
    };

    private static @Nullable Method getter(Class<?> type, String name) {
        try {
            Method m = type.getMethod(name);
            return m.getReturnType() == void.class || Modifier.isStatic(m.getModifiers()) ? null : m;
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    private static Object[] snapshot(Event event) {
        Prop[] props = PROPS.get(event.getClass());
        Object[] out = new Object[props.length];
        for (int i = 0; i < props.length; i++) out[i] = copy(read(props[i], event));
        return out;
    }

    private static @Nullable Object read(Prop prop, Event event) {
        try {
            return prop.getter.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Object copy(@Nullable Object value) {
        if (value instanceof ItemStack s) return s.copy();
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(copy(o));
            return out;
        }
        return value;
    }

    private static boolean same(Object a, @Nullable Object b) {
        if (a instanceof ItemStack x && b instanceof ItemStack y) return ItemStack.matches(x, y);
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static List<String> changes(Event event, Object[] before) {
        Prop[] props = PROPS.get(event.getClass());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < props.length; i++) {
            Object now = read(props[i], event);
            if (!same(before[i], now)) out.add(props[i].name + " " + show(before[i]) + "→" + show(now));
        }
        return out;
    }

    private static String show(@Nullable Object value) {
        String s;
        if (value == null) s = "none";
        else if (value instanceof Optional<?> o) return o.map(Trace::show).orElse("none");
        else if (value instanceof Float || value instanceof Double) s = trim(((Number) value).doubleValue());
        else if (value instanceof Component c) s = "\"" + c.getString() + "\"";
        else if (value instanceof ItemStack i) s = i.isEmpty() ? "nothing" : i.getCount() + " "
                + BuiltInRegistries.ITEM.getKey(i.getItem()).getPath();
        else if (value instanceof Vec3 v) s = trim(v.x) + "," + trim(v.y) + "," + trim(v.z);
        else if (value instanceof List<?> l) s = l.size() + " entries";
        else s = String.valueOf(value);
        return s.length() > 40 ? s.substring(0, 39) + "…" : s;
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // ---- The line ----

    private Component line(Event event, Seen seen, @Nullable String canceller) {
        Actor actor = actor(event);
        Operation op = event instanceof ActionEvent a ? a.getOperation() : null;
        List<String> changed = changes(event, seen.values);
        boolean cancellable = event instanceof Cancellable;
        boolean cancelled = cancellable && ((Cancellable) event).isCancelled();

        String outcome;
        ChatFormatting color;
        if (cancelled) {
            outcome = canceller != null ? "cancelled by " + plugin(canceller)
                    : seen.wasCancelled ? "cancelled before plugins" : "cancelled";
            color = ChatFormatting.RED;
        } else if (!changed.isEmpty()) {
            outcome = (cancellable ? "allowed, " : "") + String.join(", ", changed);
            color = ChatFormatting.YELLOW;
        } else {
            outcome = cancellable ? (seen.wasCancelled ? "uncancelled by " + plugin(canceller) : "allowed") : "done";
            color = cancellable ? ChatFormatting.GREEN : ChatFormatting.GRAY;
        }

        List<String> detail = new ArrayList<>();
        detail.add(event.getClass().getName());
        if (actor != null) detail.add("Actor: " + actor);
        if (op != null) detail.add("Operation #" + op.id() + ", begun by " + op.actor());
        if (seen.where != null) detail.add("Where: " + seen.where.text);
        if (cancelled && canceller != null) detail.add("Cancelled by " + canceller);
        if (cancelled && !changed.isEmpty()) detail.add("Also changed: " + String.join(", ", changed));
        detail.add("Click to copy this line");

        String plain = event.getEventName() + " · " + (actor == null ? "no actor" : who(actor))
                + (op != null ? " · op#" + op.id() : "") + (seen.where != null ? " · " + seen.where.text : "")
                + " · " + outcome;
        MutableComponent out = Component.empty().withStyle(Style.EMPTY
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(String.join("\n", detail))))
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, plain)));
        out.append(Component.literal(event.getEventName()).withStyle(ChatFormatting.WHITE));
        out.append(Component.literal(" · " + (actor == null ? "no actor" : who(actor))).withStyle(ChatFormatting.GRAY));
        if (op != null) out.append(Component.literal(" · op#" + op.id()).withStyle(ChatFormatting.DARK_GRAY));
        if (seen.where != null) {
            MutableComponent where = Component.literal(" · " + seen.where.text).withStyle(ChatFormatting.GRAY);
            if (seen.where.pos != null) {
                Vec3 p = seen.where.pos;
                where = where.withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                        "/execute in " + seen.where.level.dimension().location() + " run tp @s " + trim(p.x) + " "
                                + trim(p.y) + " " + trim(p.z))));
            }
            out.append(where);
        }
        out.append(Component.literal(" · " + outcome).withStyle(color));
        return out;
    }

    /** "myplugin: Listener.on" to "myplugin". */
    private static String plugin(@Nullable String where) {
        if (where == null) return "a plugin";
        int colon = where.indexOf(':');
        return colon < 0 ? where : where.substring(0, colon);
    }

    /** Names {@code /hadron trace} suggests: families, all, and every event class the server has asked about. */
    static Set<String> suggestions(Set<Class<?>> seen) {
        Set<String> out = new TreeSet<>(FAMILIES);
        out.add("all");
        out.add("off");
        for (Class<?> c : seen) if (Event.class.isAssignableFrom(c)) out.add(c.getSimpleName());
        return out;
    }
}
