package dev.iredsc.hadron.api.actor;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

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
 * Scopes must close in the reverse order they opened; try-with-resources does that.
 *
 * <p>Attribution also survives delays: whatever spawns a projectile, summon or queued task should
 * {@link #remember(Entity, Attribution)} who caused it, so a shell landing ten seconds later still knows.
 */
public final class Actors {
    private static final ThreadLocal<ArrayDeque<Frame>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile Memory memory = new Memory.Transient();

    private Actors() {}

    private record Frame(Actor actor, Operation operation, Scope scope) {}

    /** Closes the push that returned it. */
    public static final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {}

        @Override
        public void close() {
            if (closed) return;
            ArrayDeque<Frame> stack = STACK.get();
            Frame top = stack.peek();
            if (top == null || top.scope != this) {
                throw new IllegalStateException("Actor scopes closed out of order: close each Actors.push or "
                        + "Operation.begin in the reverse order they were opened, with try-with-resources");
            }
            closed = true;
            stack.pop();
        }
    }

    /** Puts an actor on top until the scope closes. Inside an open operation, the operation stays the same. */
    public static Scope push(Actor actor) {
        return enter(actor, currentOperation());
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
        Frame top = STACK.get().peek();
        return top == null ? Optional.empty() : Optional.of(top.actor);
    }

    static Scope enter(Actor actor, Operation operation) {
        Objects.requireNonNull(actor, "actor; use Actor.Unknown.INSTANCE when nobody is known");
        Scope scope = new Scope();
        STACK.get().push(new Frame(actor, operation, scope));
        return scope;
    }

    static Operation currentOperation() {
        Frame top = STACK.get().peek();
        return top == null ? null : top.operation;
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
