package dev.iredsc.hadron.api.actor;

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
 * Scopes should close in the reverse order they opened; try-with-resources does that. A scope is reused once
 * closed, so never keep one after closing it.
 *
 * <p>Attribution also survives delays: whatever spawns a projectile, summon or queued task should
 * {@link #remember(Entity, Attribution)} who caused it, so a shell landing ten seconds later still knows.
 */
public final class Actors {
    private static final Logger LOG = LoggerFactory.getLogger("Hadron");
    private static final ThreadLocal<Stack> STACK = ThreadLocal.withInitial(Stack::new);
    private static volatile Memory memory = new Memory.Transient();

    private Actors() {}

    /**
     * One thread's actors. Frames and their scopes are reused, so a push on a hot path (every entity tick, every
     * packet) allocates nothing once the stack has grown to its usual depth.
     */
    private static final class Stack {
        Actor[] actors = new Actor[16];
        Operation[] operations = new Operation[16];
        Scope[] scopes = new Scope[16];
        int depth;
        long nextToken;

        Scope push(Actor actor, Operation operation) {
            if (depth == actors.length) {
                actors = Arrays.copyOf(actors, depth * 2);
                operations = Arrays.copyOf(operations, depth * 2);
                scopes = Arrays.copyOf(scopes, depth * 2);
            }
            Scope scope = scopes[depth];
            if (scope == null) scopes[depth] = scope = new Scope(this, depth);
            scope.token = ++nextToken;
            actors[depth] = actor;
            operations[depth] = operation;
            depth++;
            return scope;
        }

        void popTo(int newDepth) {
            for (int i = newDepth; i < depth; i++) {
                actors[i] = null;
                operations[i] = null;
                scopes[i].token = 0;
            }
            depth = newDepth;
        }
    }

    /**
     * Closes the push that returned it. Closing twice does nothing. If pushes made after it were left open, closing it
     * closes those too and logs which code leaked them, rather than throwing in the middle of a tick or a packet.
     */
    public static final class Scope implements AutoCloseable {
        private final Stack stack;
        private final int index;
        /** Which push this scope stands for right now; 0 once closed. Scopes are reused, so a stale one is ignored. */
        private long token;

        private Scope(Stack stack, int index) {
            this.stack = stack;
            this.index = index;
        }

        @Override
        public void close() {
            long mine = token;
            if (mine == 0 || stack.depth <= index || stack.scopes[index] != this) return;
            if (stack != STACK.get()) {
                throw new IllegalStateException("An actor scope was closed on a different thread from the one that "
                        + "opened it; open and close it on the same thread, with try-with-resources");
            }
            if (stack.depth > index + 1) {
                LOG.warn("{} actor push(es) were left open inside {} and have been closed with it. Close every "
                        + "Actors.push and Operation.begin with try-with-resources.", stack.depth - index - 1,
                        stack.actors[index], new IllegalStateException("leaked actor scope"));
            }
            stack.popTo(index);
        }
    }

    /**
     * Puts an actor on top until the scope closes. Inside an open operation, the operation stays the same. Use it with
     * try-with-resources.
     */
    public static Scope push(Actor actor) {
        Stack stack = STACK.get();
        return stack.push(checked(actor), stack.depth == 0 ? null : stack.operations[stack.depth - 1]);
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

    private static Actor top() {
        Stack stack = STACK.get();
        return stack.depth == 0 ? null : stack.actors[stack.depth - 1];
    }

    static Scope enter(Actor actor, Operation operation) {
        return STACK.get().push(checked(actor), operation);
    }

    static Operation currentOperation() {
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
        private Port() {}

        public static void installMemory(Memory installed) {
            memory = Objects.requireNonNull(installed, "memory");
        }
    }
}
