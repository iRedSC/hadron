package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * A bucket is about to pour its fluid into the world. {@link #getPos()} is where it goes. Cancel it and no fluid is
 * placed and the bucket stays full.
 */
public class BucketEmptyEvent extends BucketEvent {
    public BucketEmptyEvent(ServerLevel level, BlockChange change, ItemStack bucket, Direction face, Actor actor) {
        super(level, change, bucket, face, actor);
    }
}
