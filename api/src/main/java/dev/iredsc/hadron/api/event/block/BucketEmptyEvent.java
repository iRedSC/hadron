package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A bucket is about to pour its fluid into the world. {@link #getPos()} is where it goes. Cancel it and no fluid is
 * placed and the bucket stays full.
 */
@FiredBy(core = FULL)
public class BucketEmptyEvent extends BucketEvent {
    public BucketEmptyEvent(ServerLevel level, BlockChange change, ItemStack bucket, @Nullable Direction face, Actor actor) {
        super(level, change, bucket, face, actor);
    }
}
