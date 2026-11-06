package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * An empty bucket is about to take fluid (or powder snow) from the world. {@link #getPos()} is where it comes from.
 * Cancel it and the fluid stays and the bucket stays empty.
 */
public class BucketFillEvent extends BucketEvent {
    public BucketFillEvent(ServerLevel level, BlockChange change, ItemStack bucket, Direction face, Actor actor) {
        super(level, change, bucket, face, actor);
    }
}
