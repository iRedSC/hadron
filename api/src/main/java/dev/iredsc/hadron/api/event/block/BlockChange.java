package dev.iredsc.hadron.api.event.block;

import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block space in a {@link BlockEvent}: where it is, and its state and block entity data before and after. Every
 * block event lists one for each position it affects, in {@link BlockEvent#getBlocks()}.
 *
 * <p>Block entity data is read when first asked for, because copying a machine's data for every change would be
 * costly. Read it inside your handler: once the change has happened the old block entity is gone, and reading it then
 * throws, saying so.
 */
public final class BlockChange {
    private final BlockPos pos;
    private final BlockState before;
    private final BlockState after;
    private final HolderLookup.@Nullable Provider registries;
    private final @Nullable BlockEntity beforeEntity;
    private final @Nullable BlockEntity afterEntity;
    private @Nullable CompoundTag beforeData;
    private @Nullable CompoundTag afterData;

    private BlockChange(BlockPos pos, BlockState before, BlockState after, HolderLookup.@Nullable Provider registries,
            @Nullable BlockEntity beforeEntity, @Nullable CompoundTag beforeData, @Nullable BlockEntity afterEntity, @Nullable CompoundTag afterData) {
        this.pos = Objects.requireNonNull(pos, "pos").immutable();
        this.before = Objects.requireNonNull(before, "before");
        this.after = Objects.requireNonNull(after, "after");
        this.registries = registries;
        this.beforeEntity = beforeEntity;
        this.beforeData = beforeData;
        this.afterEntity = afterEntity;
        this.afterData = afterData;
    }

    /**
     * A change about to happen in a live level. The data before is read from the block entity there now, if any; the
     * data after is the same block entity's when the block stays the same block (only its state changes), and empty
     * when a new block replaces it, since the new one starts fresh.
     */
    public static BlockChange of(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        BlockEntity entity = before.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        BlockEntity kept = entity != null && before.getBlock() == after.getBlock() ? entity : null;
        return new BlockChange(pos, before, after, level.registryAccess(), entity, null, kept, null);
    }

    /**
     * A change with block entity data read from live block entities when asked for. Either entity may be null for
     * "no block entity". Use this when the data after is already in place, or moves with the block (a piston push).
     */
    public static BlockChange live(ServerLevel level, BlockPos pos, BlockState before, BlockState after,
            @Nullable BlockEntity beforeEntity, @Nullable BlockEntity afterEntity) {
        return new BlockChange(pos, before, after, level.registryAccess(), beforeEntity, null, afterEntity, null);
    }

    /** A change whose data was already copied. Either tag may be null for "no block entity". */
    public static BlockChange withData(BlockPos pos, BlockState before, BlockState after,
            @Nullable CompoundTag beforeData, @Nullable CompoundTag afterData) {
        return new BlockChange(pos, before, after, null, null, beforeData, null, afterData);
    }

    /**
     * Data already copied before, and the data after read from a live block entity when asked for (null for none).
     */
    public static BlockChange withData(ServerLevel level, BlockPos pos, BlockState before, BlockState after,
            @Nullable CompoundTag beforeData, @Nullable BlockEntity afterEntity) {
        return new BlockChange(pos, before, after, level.registryAccess(), null, beforeData, afterEntity, null);
    }

    /** A space that is involved but doesn't change: a block that is used, or a source fluid that flows. */
    public static BlockChange unchanged(ServerLevel level, BlockPos pos, BlockState state) {
        return of(level, pos, state, state);
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockState getBefore() {
        return before;
    }

    public BlockState getAfter() {
        return after;
    }

    /** Whether the state changes here. False for a space that is only involved, such as a used block. */
    public boolean isChanged() {
        return before != after;
    }

    /** A copy of the block entity's data before the change; empty when there was no block entity. */
    public Optional<CompoundTag> getBeforeData() {
        if (beforeData == null && beforeEntity != null) beforeData = read(beforeEntity, "getBeforeData()");
        return beforeData == null ? Optional.empty() : Optional.of(beforeData.copy());
    }

    /**
     * A copy of the block entity's data after the change; empty when there is no block entity after, or a new one
     * starts with its block's defaults.
     */
    public Optional<CompoundTag> getAfterData() {
        if (afterData == null && afterEntity != null) afterData = read(afterEntity, "getAfterData()");
        return afterData == null ? Optional.empty() : Optional.of(afterData.copy());
    }

    private CompoundTag read(BlockEntity entity, String method) {
        if (entity.isRemoved()) {
            throw new IllegalStateException("The block entity at " + pos.toShortString() + " is already gone: call "
                    + method + " inside your event handler, not after it returns, and copy the tag there if you "
                    + "need it later");
        }
        return entity.saveWithFullMetadata(registries);
    }

    @Override
    public String toString() {
        return "BlockChange[" + pos.toShortString() + ": " + before + " -> " + after + "]";
    }
}
