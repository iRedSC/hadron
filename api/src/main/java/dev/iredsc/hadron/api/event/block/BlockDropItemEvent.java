package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block was broken (or harvested in place) and its drops are about to appear: a player mining it, a machine, fake
 * player or mob breaking it, or a block popping off when its support goes. The block is already gone; this decides
 * only what comes out. Fired after {@link BlockBreakEvent}, whenever the break rolls for drops, even if the list
 * comes out empty (a block that drops nothing, or the {@code doTileDrops} rule off); a player in creative mode
 * never fires it.
 *
 * <p>{@link #getItems()} is the live list of item entities about to be added to the world: remove, change or add to
 * it ({@link #addItem} makes one at the block the way vanilla does). {@link #setExperience} changes the experience
 * dropped. Cancel it and nothing drops, items or experience; the block stays broken.
 *
 * <p>Core fires this for every drop that goes through vanilla's {@code Block.dropResources}, which is how vanilla and
 * mods break blocks with drops. An explosion's drops are not reported here; {@code ExplosionEvent} decides which
 * blocks an explosion breaks.
 */
public class BlockDropItemEvent extends BlockEvent implements Cancellable {
    private final BlockEntity blockEntity;
    private final List<ItemEntity> items;
    private final ItemStack tool;
    private int experience;
    private boolean cancelled;

    /**
     * @param state the block that was broken
     * @param blockEntity its block entity as it was, or null if it had none
     * @param items the live list of item entities about to be added to the world
     * @param tool what broke it; {@code ItemStack.EMPTY} when nothing was held
     */
    public BlockDropItemEvent(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity,
            List<ItemEntity> items, ItemStack tool, int experience, Actor actor) {
        super(level, pos, state, List.of(broken(level, pos, state, blockEntity)), actor);
        this.blockEntity = blockEntity;
        this.items = Objects.requireNonNull(items, "items");
        this.tool = Objects.requireNonNull(tool, "tool; use ItemStack.EMPTY").copy();
        this.experience = Math.max(0, experience);
    }

    /**
     * The break, from the block and block entity that were there to what is there now. The data before is copied
     * now: by the time drops roll, a player's break has already removed the block entity, so reading it later throws.
     */
    private static BlockChange broken(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        BlockState now = level.getBlockState(pos);
        CompoundTag before = blockEntity != null ? blockEntity.saveWithFullMetadata(level.registryAccess()) : null;
        return BlockChange.withData(level, pos, state, now, before,
                now.hasBlockEntity() ? level.getBlockEntity(pos) : null);
    }

    /** Bukkit's name for {@link #getState()}: the block that was broken. */
    public BlockState getBlockState() {
        return getState();
    }

    /** The broken block's block entity as it was (a chest's contents, a sign's text), if it had one. */
    public Optional<BlockEntity> getBlockEntity() {
        return Optional.ofNullable(blockEntity);
    }

    /**
     * The item entities about to be added to the world. Live: remove one and it doesn't drop, change its stack with
     * {@code setItem}, or add more. Empty stacks and nulls are skipped.
     */
    public List<ItemEntity> getItems() {
        return items;
    }

    /** Adds a drop at the block, scattered and with a pickup delay as vanilla drops are. Returns the new entity. */
    public ItemEntity addItem(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ServerLevel level = getLevel();
        BlockPos pos = getPos();
        // As Block.popResource places them: within a quarter block of the centre, lowered by half an item's height.
        double x = pos.getX() + 0.5 + level.random.nextDouble() * 0.5 - 0.25;
        double y = pos.getY() + 0.5 + level.random.nextDouble() * 0.5 - 0.25 - 0.125;
        double z = pos.getZ() + 0.5 + level.random.nextDouble() * 0.5 - 0.25;
        ItemEntity item = new ItemEntity(level, x, y, z, stack.copy());
        item.setDefaultPickUpDelay();
        items.add(item);
        return item;
    }

    /** A copy of what broke it: the tool held, or {@code ItemStack.EMPTY}. */
    public ItemStack getTool() {
        return tool.copy();
    }

    /** Experience points about to drop, after enchantments. */
    public int getExperience() {
        return experience;
    }

    /** Drop this many experience points instead; 0 drops none. */
    public void setExperience(int experience) {
        if (experience < 0) {
            throw new IllegalArgumentException("Experience to drop can't be negative (got " + experience
                    + "); use 0 to drop none");
        }
        this.experience = experience;
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
