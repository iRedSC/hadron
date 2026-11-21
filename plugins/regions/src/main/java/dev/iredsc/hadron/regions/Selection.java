package dev.iredsc.hadron.regions;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * The two corners a player picked with the wand, in one world. Picking a corner in another world starts over, so a
 * selection can never span two worlds.
 */
record Selection(ResourceLocation world, @Nullable BlockPos first, @Nullable BlockPos second) {
    static Selection empty(ResourceLocation world) {
        return new Selection(world, null, null);
    }

    Selection withFirst(ResourceLocation world, BlockPos pos) {
        return world.equals(this.world) ? new Selection(world, pos.immutable(), second)
                : new Selection(world, pos.immutable(), null);
    }

    Selection withSecond(ResourceLocation world, BlockPos pos) {
        return world.equals(this.world) ? new Selection(world, first, pos.immutable())
                : new Selection(world, null, pos.immutable());
    }

    /** The box between both corners, once both are picked. */
    Optional<BoundingBox> box() {
        if (first == null || second == null) return Optional.empty();
        return Optional.of(BoundingBox.fromCorners(first, second));
    }

    /** Whichever box there is so far: both corners, or the one block picked. */
    Optional<BoundingBox> partial() {
        if (first != null && second != null) return box();
        BlockPos one = first != null ? first : second;
        return one == null ? Optional.empty() : Optional.of(new BoundingBox(one));
    }

    static String size(BoundingBox b) {
        return b.getXSpan() + "×" + b.getYSpan() + "×" + b.getZSpan();
    }
}
