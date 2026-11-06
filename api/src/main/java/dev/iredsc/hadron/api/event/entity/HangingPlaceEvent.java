package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;

/**
 * An item frame or painting is being hung. Cancel it and nothing is hung, and a player's item is not spent.
 */
public class HangingPlaceEvent extends EntitySpawnEvent {
    public HangingPlaceEvent(HangingEntity hanging, Actor actor) {
        super(hanging, null, actor);
    }

    @Override
    public HangingEntity getEntity() {
        return (HangingEntity) super.getEntity();
    }

    /** The block it hangs on. */
    public BlockPos getBlock() {
        return getEntity().getPos().relative(getEntity().getDirection().getOpposite());
    }

    /** The face of {@link #getBlock()} it hangs on. */
    public Direction getBlockFace() {
        return getEntity().getDirection();
    }
}
