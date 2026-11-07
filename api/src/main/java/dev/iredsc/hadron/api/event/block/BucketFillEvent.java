package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * An empty bucket is about to take fluid (or powder snow) from the world. {@link #getPos()} is where it comes from.
 * Cancel it and the fluid stays and the bucket stays empty.
 */
@FiredBy(core = FULL)
public class BucketFillEvent extends BucketEvent {
    public BucketFillEvent(ServerLevel level, BlockChange change, ItemStack bucket, @Nullable Direction face, Actor actor) {
        super(level, change, bucket, face, actor);
    }
}
