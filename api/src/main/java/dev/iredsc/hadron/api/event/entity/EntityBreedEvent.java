package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * Two animals are about to have a baby. {@link #getEntity()} is the baby, not yet in the world. Cancel it and no baby
 * is born, and both parents wait the usual time before breeding again. The baby's arrival then also fires
 * {@link EntitySpawnEvent}. Villagers are not animals and only fire the spawn event.
 */
@FiredBy(core = FULL)
public class EntityBreedEvent extends EntityEvent implements Cancellable {
    private final Mob mother;
    private final Mob father;
    private final ServerPlayer breeder;
    private boolean cancelled;

    public EntityBreedEvent(AgeableMob child, Mob mother, Mob father, ServerPlayer breeder, Actor actor) {
        super(child, actor);
        this.mother = Objects.requireNonNull(mother, "mother");
        this.father = Objects.requireNonNull(father, "father");
        this.breeder = breeder;
    }

    @Override
    public AgeableMob getEntity() {
        return (AgeableMob) super.getEntity();
    }

    public Mob getMother() {
        return mother;
    }

    public Mob getFather() {
        return father;
    }

    /** The player who fed the parents, if one did. */
    public Optional<ServerPlayer> getBreeder() {
        return Optional.ofNullable(breeder);
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
