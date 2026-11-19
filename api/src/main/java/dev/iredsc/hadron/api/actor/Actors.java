package dev.iredsc.hadron.api.actor;

import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The actor stack. When code starts acting for someone it pushes an actor, and pops it when done; every event fired
 * in between carries the actor on top. Per thread, so world generation on a worker thread never sees the server
 * thread's actor.
 *
 * <pre>{@code
 * try (Actors.Scope scope = Actors.push(new Actor.EntityActor(turret, owner))) {
 *     shot.land();   // the damage this causes is the turret's owner's
 * }
 * }</pre>
 *
 * Scopes should close in the reverse order they opened; try-with-resources does that.
 *
 * <p>Attribution also survives delays: whatever spawns a projectile, summon or queued task should
 * {@link #remember(Entity, Attribution)} who caused it, so a shell landing ten seconds later still knows.
 */
public final class Actors {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    @StaticState(reason = "each thread's open actor scopes; every scope pops itself when it closes, so a thread's stack is empty between operations, and Hadron is never reloaded")
    private static final ThreadLocal<Stack> STACK = ThreadLocal.withInitial(Stack::new);
    @StaticState(reason = "the port installs its world-saved memory when the mod loads; Hadron itself is never reloaded")
    private static volatile Memory memory = new Memory.Transient();

    private Actors() {}

    /**
     * One thread's actors. Frames are reused, so a push allocates at most its {@link Scope}, and the port's own hot
     * paths ({@link Port#enter}) allocate nothing.
     */
    private static final long TOKEN_MASK = (1L << 40) - 1;

    private static final class Stack {
        /** Each frame's actor; null for a lazy frame until something reads it. */
        @Nullable Actor[] actors = new Actor[16];
        /** Each lazy frame's actor, worked out the first time the frame is read; null for an ordinary one. */
        Port.@Nullable Lazy[] lazies = new Port.Lazy[16];
        /** Lazy frames being worked out right now: a read while one is, made by its own lookup, sees what is under it. */
        boolean[] resolving = new boolean[16];
        Operation[] operations = new Operation[16];
        /** Which push holds each frame; a closed scope's token no longer matches, so closing it again does nothing. */
        long[] tokens = new long[16];
        int depth;
        long nextToken;

        long push(Actor actor, @Nullable Operation operation) {
            return push(actor, null, operation);
        }

        long push(@Nullable Actor actor, Port.@Nullable Lazy lazy, @Nullable Operation operation) {
            if (depth == actors.length) {
                actors = Arrays.copyOf(actors, depth * 2);
                lazies = Arrays.copyOf(lazies, depth * 2);
                resolving = Arrays.copyOf(resolving, depth * 2);
                operations = Arrays.copyOf(operations, depth * 2);
                tokens = Arrays.copyOf(tokens, depth * 2);
            }
            // Wraps within 40 bits, never 0, so it always fits beside the index in a Port handle.
            long token = nextToken = (nextToken + 1) & TOKEN_MASK;
            if (token == 0) token = nextToken = 1;
            actors[depth] = actor;
            lazies[depth] = lazy;
            operations[depth] = operation;
            tokens[depth] = token;
            depth++;
            return token;
        }

        /**
         * The actor at a frame, working out a lazy one the first time and keeping it for the frame's life. While a lazy
         * frame is being worked out, its own lookup reading the stack sees the frame under it, so it never recurses.
         */
        @Nullable Actor actorAt(int index) {
            for (int i = index; i >= 0; i--) {
                Actor actor = actors[i];
                if (actor != null) return actor;
                if (resolving[i]) continue;
                Port.Lazy lazy = Objects.requireNonNull(lazies[i], "a frame with neither actor nor lazy");
                resolving[i] = true;
                try {
                    actor = checked(lazy.resolve());
                } finally {
                    resolving[i] = false;
                }
                actors[i] = actor;
                return actor;
            }
            return null;
        }

        /** Closes the push at that index if it is still the one holding the token, and any left open above it. */
        void close(int index, long token) {
            if (depth <= index || tokens[index] != token) return;
            if (depth > index + 1) {
                LOG.warn("{} actor push(es) were left open inside {} and have been closed with it. Close every "
                        + "Actors.push and Operation.begin with try-with-resources.", depth - index - 1,
                        actorAt(index), new IllegalStateException("leaked actor scope"));
            }
            for (int i = index; i < depth; i++) {
                actors[i] = null;
                lazies[i] = null;
                operations[i] = null;
                tokens[i] = 0;
            }
            depth = index;
        }
    }

    /**
     * Closes the push that returned it. Closing twice does nothing. If pushes made after it were left open, closing it
     * closes those too and logs which code leaked them, rather than throwing in the middle of a tick or a packet.
     */
    public static final class Scope implements AutoCloseable {
        private final Stack stack;
        private final int index;
        private final long token;

        private Scope(Stack stack, int index, long token) {
            this.stack = stack;
            this.index = index;
            this.token = token;
        }

        @Override
        public void close() {
            if (stack != STACK.get()) {
                throw new IllegalStateException("An actor scope was closed on a different thread from the one that "
                        + "opened it; open and close it on the same thread, with try-with-resources");
            }
            stack.close(index, token);
        }
    }

    /**
     * Puts an actor on top until the scope closes. Inside an open operation, the operation stays the same. Use it with
     * try-with-resources.
     */
    public static Scope push(Actor actor) {
        Stack stack = STACK.get();
        return scope(stack, checked(actor), stack.depth == 0 ? null : stack.operations[stack.depth - 1]);
    }

    private static Scope scope(Stack stack, Actor actor, @Nullable Operation operation) {
        int index = stack.depth;
        return new Scope(stack, index, stack.push(actor, operation));
    }

    /** Runs the work with that actor on top. */
    public static <T> T acting(Actor actor, Supplier<T> work) {
        try (Scope scope = push(actor)) {
            return work.get();
        }
    }

    /** Runs the work with that actor on top. */
    public static void acting(Actor actor, Runnable work) {
        try (Scope scope = push(actor)) {
            work.run();
        }
    }

    /** The actor on top on this thread, or empty when nothing has pushed one. */
    public static Optional<Actor> current() {
        return Optional.ofNullable(top());
    }

    /** The actor on top on this thread, or the fallback when nothing has pushed one. Allocates nothing. */
    public static Actor currentOr(Actor fallback) {
        Actor top = top();
        return top != null ? top : fallback;
    }

    /** Whether nothing has pushed an actor on this thread. */
    public static boolean isEmpty() {
        return STACK.get().depth == 0;
    }

    private static @Nullable Actor top() {
        Stack stack = STACK.get();
        return stack.depth == 0 ? null : stack.actorAt(stack.depth - 1);
    }

    static Scope enter(Actor actor, Operation operation) {
        return scope(STACK.get(), checked(actor), operation);
    }

    static @Nullable Operation currentOperation() {
        Stack stack = STACK.get();
        return stack.depth == 0 ? null : stack.operations[stack.depth - 1];
    }

    private static Actor checked(Actor actor) {
        return Objects.requireNonNull(actor, "actor; use Actor.Unknown.INSTANCE when nobody is known");
    }

    /** Records who is behind an entity, such as a projectile's shooter or a summon's summoner. Saved with it. */
    public static void remember(Entity entity, Attribution attribution) {
        memory.remember(Objects.requireNonNull(entity, "entity"), Objects.requireNonNull(attribution, "attribution"));
    }

    /** Records who is behind a block entity, such as a machine's owner. Saved with it. */
    public static void remember(BlockEntity blockEntity, Attribution attribution) {
        memory.remember(Objects.requireNonNull(blockEntity, "blockEntity"),
                Objects.requireNonNull(attribution, "attribution"));
    }

    /** Who was recorded behind an entity; {@link Attribution#none()} if nobody was. */
    public static Attribution remembered(Entity entity) {
        return memory.remembered(entity);
    }

    /** Who was recorded behind a block entity; {@link Attribution#none()} if nobody was. */
    public static Attribution remembered(BlockEntity blockEntity) {
        return memory.remembered(blockEntity);
    }

    /** Where remembered attributions live. The loader port installs one that saves with the world. */
    public interface Memory {
        void remember(Entity entity, Attribution attribution);

        void remember(BlockEntity blockEntity, Attribution attribution);

        Attribution remembered(Entity entity);

        Attribution remembered(BlockEntity blockEntity);

        /** Until a port installs its own: kept while loaded, lost on restart. */
        final class Transient implements Memory {
            private final java.util.Map<Object, Attribution> map =
                    java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

            @Override
            public void remember(Entity entity, Attribution attribution) {
                map.put(entity, attribution);
            }

            @Override
            public void remember(BlockEntity blockEntity, Attribution attribution) {
                map.put(blockEntity, attribution);
            }

            @Override
            public Attribution remembered(Entity entity) {
                return map.getOrDefault(entity, Attribution.none());
            }

            @Override
            public Attribution remembered(BlockEntity blockEntity) {
                return map.getOrDefault(blockEntity, Attribution.none());
            }
        }
    }

    /** For the loader port only. */
    public static final class Port {
        private static final String PUSH = "push an actor with try (Actors.Scope scope = Actors.push(actor)) { ... }, "
                + "or Actors.acting(actor, work)";

        private Port() {}

        /** Takes the {@link HostKey}. */
        public static void installMemory(HostKey key, Memory installed) {
            HostKey.require(key, "Actors.Port.installMemory", "read who is behind an action with Actors.current() "
                    + "and Actors.remembered");
            memory = Objects.requireNonNull(installed, "memory");
        }

        /**
         * Pushes an actor without allocating, for paths that run every tick (entity ticks, machine ticks, packets).
         * Returns a handle for {@link #exit}, which must be called in a {@code finally} on the same thread. Takes the
         * {@link HostKey}: a plugin or bridge uses {@link Actors#push} or {@link Actors#acting}, which can't be left
         * open by mistake.
         */
        public static long enter(HostKey key, Actor actor) {
            HostKey.require(key, "Actors.Port.enter", PUSH);
            Stack stack = STACK.get();
            int index = stack.depth;
            long token = stack.push(checked(actor), index == 0 ? null : stack.operations[index - 1]);
            return ((long) index << 40) | token;
        }

        /**
         * As {@link #enter(HostKey, Actor)}, inside {@code operation}: for one operation held open across steps with
         * other code running between them, such as a build whose events are posted with nobody behind them.
         */
        public static long enter(HostKey key, Actor actor, Operation operation) {
            HostKey.require(key, "Actors.Port.enter", PUSH);
            Stack stack = STACK.get();
            int index = stack.depth;
            long token = stack.push(checked(actor), Objects.requireNonNull(operation, "operation"));
            return ((long) index << 40) | token;
        }

        /**
         * An actor worked out only if something reads it while its frame is open: the first {@link Actors#current()}
         * or event on top of it calls {@link #resolve()}, once, and the frame keeps what it returned until it closes.
         * For the port's per-tick pushes whose actor costs a lookup (who poisoned an entity), so a tick nobody reads
         * looks nothing up and keeps nothing.
         */
        public interface Lazy {
            /** The actor; never null ({@link Actor.Unknown#INSTANCE} when nobody can be found). */
            Actor resolve();
        }

        /**
         * As {@link #enter(HostKey, Actor)}, with an actor worked out only when read ({@link Lazy}). Allocates nothing.
         * Takes the {@link HostKey}.
         */
        public static long enterLazy(HostKey key, Lazy lazy) {
            HostKey.require(key, "Actors.Port.enterLazy", PUSH);
            Stack stack = STACK.get();
            int index = stack.depth;
            long token = stack.push(null, Objects.requireNonNull(lazy, "lazy"),
                    index == 0 ? null : stack.operations[index - 1]);
            return ((long) index << 40) | token;
        }

        /** Pops what {@link #enter} pushed, and anything left open above it. Takes the {@link HostKey}. */
        public static void exit(HostKey key, long handle) {
            HostKey.require(key, "Actors.Port.exit", PUSH);
            STACK.get().close((int) (handle >>> 40), handle & TOKEN_MASK);
        }
    }
}
