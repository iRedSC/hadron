package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.FULL;

/**
 * A block is about to be removed by an actor: a player mining it, or a machine, fake player or mob breaking it, or a
 * fluid source being drained. Cancel it and the block stays, with nothing dropped. {@link #getState()} is the block
 * being broken; {@link #getAfter()} is what is left, usually air or the fluid that was in it. {@link #getTool()} is
 * what it is broken with, as far as Hadron can tell.
 */
@FiredBy(core = FULL)
public class BlockBreakEvent extends BlockChangeEvent {
    private final Tool tool;

    /** A break whose tool its producer doesn't state: {@link Tool.Unknown} with {@link Tool.Reason#NOT_STATED}. */
    public BlockBreakEvent(ServerLevel level, BlockPos pos, BlockState state, BlockState after, Actor actor) {
        this(level, BlockChange.of(level, pos, state, after), Tool.Unknown.NOT_STATED, actor);
    }

    public BlockBreakEvent(ServerLevel level, BlockPos pos, BlockState state, BlockState after, Tool tool,
            Actor actor) {
        this(level, BlockChange.of(level, pos, state, after), tool, actor);
    }

    /** A break whose tool its producer doesn't state: {@link Tool.Unknown} with {@link Tool.Reason#NOT_STATED}. */
    public BlockBreakEvent(ServerLevel level, BlockChange change, Actor actor) {
        this(level, change, Tool.Unknown.NOT_STATED, actor);
    }

    public BlockBreakEvent(ServerLevel level, BlockChange change, Tool tool, Actor actor) {
        super(level, change.getPos(), change.getBefore(), List.of(change), actor);
        this.tool = Objects.requireNonNull(tool, "tool; use BlockBreakEvent.Tool.Unknown.NOT_STATED when you can't say");
    }

    /**
     * What the block is broken with: the item a player mines with, the tool a machine harvests with (AE2's annihilation
     * plane builds one, Mekanism's digital miner uses an atomic disassembler), nothing (a bare hand, Create's drill),
     * or unknown. Sealed, so a {@code switch} must decide what an unknown tool means:
     *
     * <pre>{@code
     * switch (event.getTool()) {
     *     case BlockBreakEvent.Tool.Item i -> ...  // i.getItem().is(Items.IRON_PICKAXE)
     *     case BlockBreakEvent.Tool.None n -> ...  // nothing held, or a machine with no tool
     *     case BlockBreakEvent.Tool.Unknown u -> ... // u.reason() says why
     * }
     * }</pre>
     *
     * <p>Whether a tool can harvest the block is {@code item.isCorrectToolForDrops(event.getState())}. There is no tool
     * tier: what a tool harvests comes from its tool component and block tags, and mods' tools set their own.
     */
    public Tool getTool() {
        return tool;
    }

    /** What a block is broken with. */
    public sealed interface Tool {
        /** An item: a player's held tool, or a machine's own harvesting tool. */
        final class Item implements Tool {
            private final EventStack.Copy item;

            /** @param item not empty; use {@link None#INSTANCE} for nothing */
            public Item(ItemStack item) {
                if (Objects.requireNonNull(item, "item").isEmpty()) {
                    throw new IllegalArgumentException("A tool is never ItemStack.EMPTY; use "
                            + "BlockBreakEvent.Tool.None.INSTANCE for a break with nothing in hand");
                }
                this.item = EventStack.copyOf(item, "item");
            }

            /** A copy of the tool, new on every read: change the real item through the actor, not this. */
            public ItemStack getItem() {
                return item.get();
            }

            @Override
            public String toString() {
                return "Item[" + item.get() + "]";
            }
        }

        /** Nothing: a bare hand, a mob, or a machine that breaks without a tool (Create's drill). */
        record None() implements Tool {
            public static final None INSTANCE = new None();
        }

        /** Hadron can't tell what it is broken with, and why. A bridge for the mod doing it can. */
        record Unknown(Reason reason) implements Tool {
            /** Its producer didn't say: a bridge, or a block an item use removed. */
            public static final Unknown NOT_STATED = new Unknown(Reason.NOT_STATED);

            public Unknown {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** Why a tool is unknown. */
        enum Reason {
            /** Whoever fired the event didn't say. */
            NOT_STATED,
            /**
             * A mod asked whether the break is allowed before working out what it breaks with (Mekanism's digital
             * miner asks this way); the removal that follows may say.
             */
            PERMISSION_CHECK,
            /** A mod removed the block itself without working out its drops first. */
            REMOVED_DIRECTLY,
            /** TNT being lit: it is set off, not harvested. */
            IGNITED
        }
    }
}
