package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An animal is about to be tamed: a wolf, a cat, a parrot, a horse. Cancel it and it stays wild; the taming item is
 * still used, as a failed attempt's would be.
 */
@FiredBy(core = FULL)
public class EntityTameEvent extends EntityEvent implements Cancellable {
    private final Player owner;
    private boolean cancelled;

    public EntityTameEvent(Animal animal, Player owner, Actor actor) {
        super(animal, actor);
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public Animal getEntity() {
        return (Animal) super.getEntity();
    }

    /** Who will own it; a real player, or a fake player such as a mod's machine. */
    public Player getOwner() {
        return owner;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
