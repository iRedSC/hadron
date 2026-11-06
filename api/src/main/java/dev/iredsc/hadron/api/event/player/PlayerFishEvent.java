package dev.iredsc.hadron.api.event.player;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;

/**
 * A player fishing, at each step: casting, a fish biting, a fish getting away, and reeling in. Fired for vanilla's
 * fishing rod and any mod's rod whose bobber is a {@link FishingHook}, by players and fake players alike (the actor
 * says which). {@link #getEntity()} is the bobber.
 *
 * <p>What cancelling does depends on {@link #getState()}:
 *
 * <ul>
 *   <li>{@link State#FISHING}: the bobber is never cast.
 *   <li>{@link State#BITE}: the fish swims off and the wait starts over.
 *   <li>{@link State#CAUGHT_FISH}, {@link State#CAUGHT_ENTITY}, {@link State#IN_GROUND}, {@link State#REEL_IN}: the
 *       reel-in doesn't happen. The bobber stays out, nothing is caught or pulled, and the rod takes no damage.
 *   <li>{@link State#FAILED_ATTEMPT}: can't be cancelled; the fish has already gone.
 * </ul>
 *
 * <p>On {@link State#CAUGHT_FISH}, {@link #getItems()} is the catch, editable, and {@link #setExpToDrop} the
 * experience it gives.
 */
public class PlayerFishEvent extends EntityEvent implements Cancellable {
    /** The step of fishing this is. Bukkit's names. */
    public enum State {
        /** Casting: the bobber is about to be thrown. */
        FISHING,
        /** Reeling in with a fish on the line: {@link #getItems()} is the catch. */
        CAUGHT_FISH,
        /** Reeling in with an entity hooked: {@link #getCaught()} is it, about to be pulled. */
        CAUGHT_ENTITY,
        /** Reeling in a bobber lying on the ground. */
        IN_GROUND,
        /** A fish bit and got away before the player reeled in. Reported after the fact; not cancellable. */
        FAILED_ATTEMPT,
        /** Reeling in with nothing on the line. */
        REEL_IN,
        /** A fish is biting: reel in now to catch it. */
        BITE
    }

    private final ServerPlayer player;
    private final State state;
    private final Entity caught;
    private final List<ItemStack> items;
    private int exp;
    private boolean cancelled;

    /**
     * @param caught the hooked entity on {@link State#CAUGHT_ENTITY}, otherwise null
     * @param items the live catch on {@link State#CAUGHT_FISH}; ignored otherwise
     * @param exp the experience the catch gives on {@link State#CAUGHT_FISH}; ignored otherwise
     */
    public PlayerFishEvent(ServerPlayer player, FishingHook hook, State state, Entity caught, List<ItemStack> items,
            int exp, Actor actor) {
        super(hook, actor);
        this.player = Objects.requireNonNull(player, "player");
        this.state = Objects.requireNonNull(state, "state");
        this.caught = state == State.CAUGHT_ENTITY ? Objects.requireNonNull(caught, "caught") : null;
        this.items = state == State.CAUGHT_FISH ? Objects.requireNonNull(items, "items") : List.of();
        this.exp = state == State.CAUGHT_FISH ? Math.max(0, exp) : 0;
    }

    /** The player fishing (a fake player when a machine fishes). */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** The bobber. Same as {@link #getEntity()}. */
    public FishingHook getHook() {
        return (FishingHook) getEntity();
    }

    public State getState() {
        return state;
    }

    /** The entity hooked, on {@link State#CAUGHT_ENTITY}; empty in every other state. */
    public Optional<Entity> getCaught() {
        return Optional.ofNullable(caught);
    }

    /**
     * The catch on {@link State#CAUGHT_FISH}: live, so remove, replace or add stacks to change what the player
     * reels in. Empty and unmodifiable in every other state.
     */
    public List<ItemStack> getItems() {
        return items;
    }

    /** Experience points the catch gives, on {@link State#CAUGHT_FISH}; 0 otherwise. */
    public int getExpToDrop() {
        return exp;
    }

    /** Give this many experience points for the catch instead; only on {@link State#CAUGHT_FISH}. */
    public void setExpToDrop(int exp) {
        if (state != State.CAUGHT_FISH) {
            throw new IllegalStateException("Only a caught fish gives experience; this is " + state
                    + ". Check getState() == State.CAUGHT_FISH before setExpToDrop");
        }
        if (exp < 0) {
            throw new IllegalArgumentException("Experience can't be negative (got " + exp + "); use 0 for none");
        }
        this.exp = exp;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        if (cancel && state == State.FAILED_ATTEMPT) {
            throw new IllegalStateException("FAILED_ATTEMPT is reported after the fish got away, so there is "
                    + "nothing to cancel. To stop a catch, cancel CAUGHT_FISH; to stop bites, cancel BITE");
        }
        this.cancelled = cancel;
    }
}
