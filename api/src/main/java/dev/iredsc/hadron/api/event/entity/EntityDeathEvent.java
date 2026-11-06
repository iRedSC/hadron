package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * A living entity is dying and its loot is being decided. Not cancellable: the death has happened. What drops is
 * editable: remove a stack from {@link #getDrops()} to destroy it, add one to drop it, and change
 * {@link #setDroppedExp the experience}. The actor is the killer, or {@link Actor.Nature} for a fall or the void.
 */
public class EntityDeathEvent extends EntityEvent {
    private final DamageSource source;
    private final List<ItemStack> drops;
    private int droppedExp;

    public EntityDeathEvent(LivingEntity entity, DamageSource source, List<ItemStack> drops, int droppedExp,
            Actor actor) {
        super(entity, actor);
        this.source = Objects.requireNonNull(source, "source");
        this.drops = Objects.requireNonNull(drops, "drops");
        this.droppedExp = droppedExp;
    }

    @Override
    public LivingEntity getEntity() {
        return (LivingEntity) super.getEntity();
    }

    /** What killed it; {@code getDamageSource().getEntity()} is the killer, if any. */
    public DamageSource getDamageSource() {
        return source;
    }

    /** The items that will drop where it died. Editable: remove to destroy, add to drop more. */
    public List<ItemStack> getDrops() {
        return drops;
    }

    /** Experience points that will drop as orbs. */
    public int getDroppedExp() {
        return droppedExp;
    }

    public void setDroppedExp(int exp) {
        if (exp < 0) throw new IllegalArgumentException("Dropped experience can't be negative, got " + exp + "; use 0 for none");
        this.droppedExp = exp;
    }
}
