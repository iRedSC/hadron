package dev.iredsc.hadron.api.actor;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One action with many parts: a spell hitting ten entities, a gadget building a hundred blocks, a waystone charging
 * a cost and then moving a player and their pets. Every event fired while an operation is open carries it, so a
 * logger can group them and a rollback can undo them together. An event fired outside any operation gets one of its
 * own.
 *
 * <p>Open one before the first change, so the event that opens it can be cancelled while nothing has happened yet:
 *
 * <pre>{@code
 * try (Actors.Scope scope = Operation.begin(new Actor.Machine(level, pos, owner))) {
 *     // fire the opening event; if cancelled, return before changing anything
 *     // ... the mod's work, whose events all carry Operation.current()
 * }
 * }</pre>
 */
public final class Operation {
    @StaticState(reason = "a sequence for operation ids, which only need to be unique in the process")
    private static final AtomicLong IDS = new AtomicLong();

    private final long id;
    private final Actor actor;

    private Operation(Actor actor) {
        this.id = IDS.incrementAndGet();
        this.actor = Objects.requireNonNull(actor, "actor");
    }

    /** A new operation with that actor on top, until the scope closes. */
    public static Actors.Scope begin(Actor actor) {
        return Actors.enter(actor, new Operation(actor));
    }

    /** A one-event operation, for an event fired outside any open one. */
    public static Operation single(Actor actor) {
        return new Operation(actor);
    }

    /** The innermost open operation on this thread, or null if none is open. */
    public static @Nullable Operation current() {
        return Actors.currentOperation();
    }

    /** Unique while the server runs; not saved across restarts. */
    public long id() {
        return id;
    }

    /** Who began it. Events inside may name a more specific actor, such as one summon of a spell. */
    public Actor actor() {
        return actor;
    }

    @Override
    public String toString() {
        return "Operation#" + id + "[" + actor + "]";
    }
}
