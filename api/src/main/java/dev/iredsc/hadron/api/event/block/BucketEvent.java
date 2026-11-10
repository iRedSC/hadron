package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A bucket, or any item working like one, is about to place fluid in the world or take it out, by a player, a
 * dispenser or anything else. A {@link BlockChangeEvent}, so a listener for block changes hears it. Cancel it and the
 * world and the bucket stay as they were.
 */
public abstract class BucketEvent extends BlockChangeEvent {
    private final EventStack.Copy item;
    private final @Nullable Direction face;

    protected BucketEvent(ServerLevel level, BlockChange change, ItemStack item, @Nullable Direction face, Actor actor) {
        super(level, change.getPos(), change.getAfter(), List.of(change), actor);
        this.item = EventStack.copyOf(item, "item");
        this.face = face;
    }

    /** A copy of the bucket used, as it was before. */
    public ItemStack getItemStack() {
        return item.get();
    }

    /** The bucket's item, such as {@code Items.WATER_BUCKET}. */
    public Item getBucket() {
        return item.get().getItem();
    }

    /** The face of the block clicked, when a player clicked one. */
    public Optional<Direction> getBlockFace() {
        return Optional.ofNullable(face);
    }
}
