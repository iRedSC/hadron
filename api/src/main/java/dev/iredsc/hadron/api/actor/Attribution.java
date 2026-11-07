package dev.iredsc.hadron.api.actor;

import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The people behind an actor, as UUIDs so they work while offline. Immutable; the {@code with} methods return a copy.
 *
 * <ul>
 *   <li><b>responsible</b>: who answers for it. A machine's owner, a summon's summoner, a gun's shooter.
 *   <li><b>controller</b>: who directs it right now, if someone other than the responsible player does. A vehicle's
 *       driver, a drone's remote operator, the player who charmed a mob.
 *   <li><b>authority</b>: whose permissions it runs with, for commands. A quest reward command may run as the server.
 * </ul>
 */
public final class Attribution {
    private static final Attribution NONE = new Attribution(null, null, null);

    private final @Nullable UUID responsible;
    private final @Nullable UUID controller;
    private final @Nullable UUID authority;

    private Attribution(@Nullable UUID responsible, @Nullable UUID controller, @Nullable UUID authority) {
        this.responsible = responsible;
        this.controller = controller;
        this.authority = authority;
    }

    /** Nobody known: an ownerless machine, a wild mob. */
    public static Attribution none() {
        return NONE;
    }

    /** Someone answers for it, and directs it too. */
    public static Attribution of(UUID responsible) {
        return new Attribution(Objects.requireNonNull(responsible, "responsible; use Attribution.none() for nobody"),
                null, null);
    }

    public Attribution withResponsible(@Nullable UUID responsible) {
        return new Attribution(responsible, controller, authority);
    }

    public Attribution withController(@Nullable UUID controller) {
        return new Attribution(responsible, controller, authority);
    }

    public Attribution withAuthority(@Nullable UUID authority) {
        return new Attribution(responsible, controller, authority);
    }

    public Optional<UUID> responsible() {
        return Optional.ofNullable(responsible);
    }

    public Optional<UUID> controller() {
        return Optional.ofNullable(controller);
    }

    public Optional<UUID> authority() {
        return Optional.ofNullable(authority);
    }

    /**
     * Who protection checks: the controller where there is one, otherwise the responsible player. A charmed mob acts
     * for its charmer, so stealing a creature never inherits its owner's claim rights.
     */
    public Optional<UUID> actingFor() {
        return controller != null ? Optional.of(controller) : Optional.ofNullable(responsible);
    }

    public boolean isEmpty() {
        return responsible == null && controller == null && authority == null;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof Attribution a && Objects.equals(responsible, a.responsible)
                && Objects.equals(controller, a.controller) && Objects.equals(authority, a.authority);
    }

    @Override
    public int hashCode() {
        return Objects.hash(responsible, controller, authority);
    }

    @Override
    public String toString() {
        return "Attribution[responsible=" + responsible + ", controller=" + controller + ", authority=" + authority + "]";
    }
}
