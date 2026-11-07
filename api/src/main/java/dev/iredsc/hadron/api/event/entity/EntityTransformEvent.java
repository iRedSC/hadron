package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A living entity is about to become another kind: a villager struck by lightning, a zombie drowning, a piglin in
 * the overworld, a mooshroom sheared, a mod's conversion. Cancel it and it stays what it is; one that converts over
 * time (a drowning zombie) tries again later.
 *
 * <p>A zombie killing a villager fires this before the game rolls whether the villager converts, so on normal
 * difficulty about half of these end with the villager simply dead.
 */
@FiredBy(core = FULL)
public class EntityTransformEvent extends EntityEvent implements Cancellable {
    private final EntityType<? extends LivingEntity> transformedType;
    private boolean cancelled;

    public EntityTransformEvent(LivingEntity entity, EntityType<? extends LivingEntity> transformedType, Actor actor) {
        super(entity, actor);
        this.transformedType = Objects.requireNonNull(transformedType, "transformedType");
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** What it will become. */
    public EntityType<? extends LivingEntity> getTransformedType() {
        return transformedType;
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
