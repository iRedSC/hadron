package dev.iredsc.hadron.api.event.resource;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.List;
import java.util.Objects;

/**
 * An amount of a {@link Resource} is about to change hands, appear, vanish or turn into another. Listen for this to
 * hear all of them: every one says which resource, how much, and which holders it touches, so an anti-dupe or
 * protection plugin can decide without knowing the leaf. Cancel it and no holder changes, and any cost is refunded.
 *
 * <p><b>Bridge events.</b> Energy, fluids in tanks, mana and money live in each mod's own code, so these are fired by
 * the mod's bridge (or an economy plugin) before the change, never for a simulated transfer, and the change is dropped
 * if cancelled. Hadron core does not fire them yet.
 */
public abstract class ResourceEvent extends ActionEvent implements Cancellable {
    private final Resource resource;
    private final long amount;
    private boolean cancelled;

    protected ResourceEvent(Actor actor, Resource resource, long amount) {
        super(actor);
        this.resource = Objects.requireNonNull(resource, "resource");
        if (amount < 0) {
            throw new IllegalArgumentException("Resource amounts are never negative, got " + amount
                    + "; the event's kind (produce, consume, transfer) says which way it goes");
        }
        this.amount = amount;
    }

    public Resource getResource() {
        return resource;
    }

    /** How much changes, moves or is converted, in the resource's {@link Resource#unit() unit}. */
    public long getAmount() {
        return amount;
    }

    /** Every holder whose amount changes. */
    public abstract List<ResourceHolder> getHolders();

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    static ResourceHolder checkHolder(Resource resource, ResourceHolder holder, String role) {
        Objects.requireNonNull(holder, role);
        if (resource.isCurrency() && !(holder instanceof ResourceHolder.Account)) {
            throw new IllegalArgumentException(resource.id() + " is money, which only accounts hold, but the " + role
                    + " is " + holder + "; use ResourceHolder.Account, such as ResourceHolder.Account.player(uuid)");
        }
        return holder;
    }
}
