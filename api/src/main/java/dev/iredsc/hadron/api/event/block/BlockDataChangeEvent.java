package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Data at one or more block spaces is about to change while their block states stay the same. Either a block
 * entity's data (a spawner's mob, a drawer's lock, a terminal's binding, a custom name), or data a mod keeps for
 * those spaces in its own storage (a wire strung between two connectors, an oxygen or gravity field, a waystone's
 * name). Cancel it and the data stays as it was.
 *
 * <p>{@link #getKind()} says whose data it is: {@link #BLOCK_ENTITY} for a block entity's, otherwise an id in the
 * mod's namespace such as {@code immersiveengineering:wire}. {@link #getBlocks()} lists every space the data covers,
 * so a protection plugin listening to {@link BlockEvent} denies it wherever it would deny a block change, without
 * knowing the mod. The data before and after is in the mod's own format: log it, don't parse it.
 *
 * <p><b>Fired by bridges</b>: vanilla has no single point where this data changes, so core doesn't fire it. A bridge
 * fires it before its mod writes the data, with the data before and after both filled in, and skips the write if it
 * is cancelled.
 */
@FiredBy(core = NONE,
        bridges = "changes a block entity's data with the block state unchanged (a spawner's mob, a drawer lock, a "
                + "terminal binding, a name), or data it keeps for block spaces in its own storage (a wire, an "
                + "oxygen or gravity field, a waystone's name)",
        fillIn = "the kind of data, every space it covers, and the data before and after")
public class BlockDataChangeEvent extends BlockChangeEvent {
    /** The kind for a block entity's own data. */
    public static final ResourceLocation BLOCK_ENTITY = ResourceLocation.fromNamespaceAndPath("hadron",
            "block_entity");

    private final ResourceLocation kind;
    private final CompoundTag before;
    private final CompoundTag after;

    /** A block entity's data changes. */
    public BlockDataChangeEvent(ServerLevel level, BlockPos pos, BlockState state, CompoundTag before,
            CompoundTag after, Actor actor) {
        super(level, pos, state, List.of(BlockChange.withData(pos, state, state,
                Objects.requireNonNull(before, "before; pass the block entity's data as it is now").copy(),
                Objects.requireNonNull(after, "after; pass the data as it will be").copy())), actor);
        this.kind = BLOCK_ENTITY;
        this.before = primary().getBeforeData().orElseThrow();
        this.after = primary().getAfterData().orElseThrow();
    }

    /**
     * Data the mod keeps for these spaces in its own storage changes.
     *
     * @param pos where it starts: the connector a wire is strung from, the machine that projects a field; also in
     *     {@code covers}
     * @param kind whose data, in the mod's namespace: {@code immersiveengineering:wire}, {@code ad_astra:gravity}
     * @param covers every space the data covers: both connectors and each block the wire passes through, each cell
     *     of a field that changes
     * @param before the data as it is now, in the mod's format; an empty tag when there was none
     * @param after the data as it will be; an empty tag when it is being removed
     */
    public BlockDataChangeEvent(ServerLevel level, BlockPos pos, ResourceLocation kind, List<BlockPos> covers,
            CompoundTag before, CompoundTag after, Actor actor) {
        super(level, pos, level.getBlockState(pos), spaces(level, pos, covers, kind), actor);
        this.kind = kind;
        this.before = Objects.requireNonNull(before, "before; pass an empty CompoundTag when there was none").copy();
        this.after = Objects.requireNonNull(after, "after; pass an empty CompoundTag when it is being removed").copy();
    }

    private static List<BlockChange> spaces(ServerLevel level, BlockPos pos, List<BlockPos> covers,
            ResourceLocation kind) {
        Objects.requireNonNull(kind, "kind; use an id in your mod's namespace, such as immersiveengineering:wire");
        if (kind.equals(BLOCK_ENTITY)) {
            throw new IllegalArgumentException("hadron:block_entity is for a block entity's own data: use the "
                    + "constructor that takes the block state and the block entity's data");
        }
        if (kind.getNamespace().equals("hadron") || kind.getNamespace().equals("minecraft")) {
            throw new IllegalArgumentException(kind + " isn't a mod's id: the kind names whose data it is, in your "
                    + "mod's namespace, such as immersiveengineering:wire");
        }
        Objects.requireNonNull(covers, "covers; list every space the data covers");
        if (covers.isEmpty()) {
            throw new IllegalArgumentException("covers is empty: list every space the data covers (both ends of a "
                    + "wire and each block it passes through, each cell of a field), so protection can check them");
        }
        if (!covers.contains(pos)) {
            throw new IllegalArgumentException("covers doesn't include pos " + pos.toShortString() + ": the space "
                    + "the data starts from is one it covers, so list it too");
        }
        return covers.stream().map(p -> BlockChange.unchanged(level, p, level.getBlockState(p))).toList();
    }

    /** Whose data this is: {@link #BLOCK_ENTITY}, or an id in the mod's namespace. */
    public ResourceLocation getKind() {
        return kind;
    }

    /**
     * A copy of the data before, in its owner's format. For a mod's own data, {@link #getBlocks()} still holds each
     * space's block entity data, which this change leaves as it is.
     */
    public CompoundTag getBeforeData() {
        return before.copy();
    }

    /** A copy of the data after, in its owner's format. */
    public CompoundTag getAfterData() {
        return after.copy();
    }
}
