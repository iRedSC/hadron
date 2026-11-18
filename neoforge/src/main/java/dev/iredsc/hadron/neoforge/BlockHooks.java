package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.checks.StaticState;
import org.jspecify.annotations.Nullable;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockFadeEvent;
import dev.iredsc.hadron.api.event.block.BlockFertilizeEvent;
import dev.iredsc.hadron.api.event.block.BlockFormEvent;
import dev.iredsc.hadron.api.event.block.BlockFromToEvent;
import dev.iredsc.hadron.api.event.block.BlockGrowEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockSpreadEvent;
import dev.iredsc.hadron.api.event.block.BlockTransformEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.block.BucketEmptyEvent;
import dev.iredsc.hadron.api.event.block.BucketFillEvent;
import dev.iredsc.hadron.api.event.block.LeavesDecayEvent;
import dev.iredsc.hadron.api.event.block.SignChangeEvent;
import dev.iredsc.hadron.api.event.block.StructureGrowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.ChangeOverTimeBlock;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FrogspawnBlock;
import net.minecraft.world.level.block.FrostedIceBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SnifferEggBlock;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;

/**
 * The block family's chokepoints, called from the block mixins and {@link BlockEvents}. Each method that can cancel
 * returns true when the change must not happen.
 *
 * <p>One effect fires one event: a specific producer (a player's break, a fluid's flow, a tree's growth) marks what it
 * covers as handled, and the generic {@code Level.setBlock} hook stays quiet for it. That is either the whole thread
 * ({@link #suppress()}) or one position ({@link #suppressAt}) while the effect is applied, or NeoForge's block
 * snapshot capture, whose own events report what was captured.
 *
 * <p>The generic hook fires only when an actor is on the stack, or a block's own tick, weather or fire is changing the
 * world. When nothing is, it returns after a thread-local read and a few field checks, allocating nothing.
 */
public final class BlockHooks {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long NO_POS = Long.MIN_VALUE;

    private BlockHooks() {}

    /**
     * A retracting piston's head, which {@code PistonStructureResolver} is shown as air while Hadron asks what the
     * piston will pull (vanilla resolves only after removing it). Server thread only; null otherwise.
     */
    @StaticState(reason = "set around one piston move's event on the server thread and cleared after it")
    public static @Nullable BlockPos pistonHiddenHead;

    /** What the current thread is doing to blocks. Reused, so entering and leaving a tick allocates nothing. */
    private static final class Context {
        int suppressed;
        /**
         * A change already decided for one position this game tick: TNT asked about before it is lit, so the removal
         * that follows is refused or let through without asking again. Consumed by that removal.
         */
        @Nullable LevelAccessor decidedLevel;
        long decidedPos = NO_POS;
        long decidedTime;
        boolean decidedCancelled;
        /** Positions whose change is being reported by a specific event, innermost last, per level. */
        LevelAccessor[] suppressLevels = new LevelAccessor[8];
        long[] suppressPositions = new long[8];
        int suppressDepth;
        /** Suppression set below these marks belongs to code outside a plugin callback, which can't see it. */
        int suppressedFloor;
        int suppressDepthFloor;

        boolean suppresses(LevelAccessor level, BlockPos pos) {
            if (suppressed > suppressedFloor) return true;
            int top = suppressDepth - 1;
            return top >= suppressDepthFloor && suppressLevels[top] == level && suppressPositions[top] == pos.asLong();
        }
        /** Block ticks being run, innermost last. */
        Block[] tickBlocks = new Block[8];
        long[] tickPositions = new long[8];
        Actor[] tickActors = new Actor[8];
        /** {@link Actors.Port#enter} handles, 0 where the tick pushed nothing. */
        long[] tickScopes = new long[8];
        int ticks;
        int forming;
        @Nullable Actor fertilizer;
        /**
         * A container's emptying a command put off until its replacement is decided (see {@link #deferClear}), and
         * where; null when none is waiting.
         */
        @Nullable Runnable deferredClear;
        @Nullable LevelAccessor deferredClearLevel;
        long deferredClearPos = NO_POS;
        /** Inside {@link #patternBuild}: true while one runs on this thread. */
        boolean building;
        /** The build's blocks as they were, once allowed; null before then or if it was refused. */
        @Nullable List<BlockSnapshot> built;
        @Nullable Entity builtCreature;
        /** The build's one operation, and the {@link Actors.Port#enter} handle holding it open (0 while not). */
        dev.iredsc.hadron.api.actor.@Nullable Operation buildOperation;
        long buildScope;
        /** Putting a refused build's blocks back, whose placing would otherwise find the pattern and build again. */
        boolean unbuilding;
        int lastUser = -1;
        long lastUsePos = NO_POS;
        long lastUseTime;
        /** The player mining through the game mode now, and where; null when none. */
        @Nullable Mining mining;
        /**
         * The last tool a block's drops were worked out with on this thread ({@code Block.getDrops}), for the removal
         * of exactly that block, by the same actor, in the same game tick. Consumed by that removal.
         */
        @Nullable LevelAccessor harvestLevel;
        long harvestPos = NO_POS;
        @Nullable BlockState harvestState;
        long harvestTime;
        @Nullable Actor harvestActor;
        ItemStack harvestTool = ItemStack.EMPTY;

        boolean ticking() {
            return ticks > 0;
        }

        Block tickBlock() {
            return tickBlocks[ticks - 1];
        }

        long tickPos() {
            return tickPositions[ticks - 1];
        }

        Actor tickActor() {
            return tickActors[ticks - 1];
        }
    }

    @StaticState(reason = "each thread's suppression and decided-change bookkeeping; every suppress is paired with an unsuppress, and a decision is consumed by its removal or expires with its game tick")
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);

    // ---------------------------------------------------------------------------------------------------------------
    // Suppression: one effect, one event

    /** Silences the generic hook on this thread until {@link #unsuppress()}. */
    public static void suppress() {
        CONTEXT.get().suppressed++;
    }

    public static void unsuppress() {
        CONTEXT.get().suppressed--;
    }

    /**
     * TNT is about to be lit, by a player's flint and steel, a dispenser, a flaming arrow or a mod: asked as a
     * {@link BlockBreakEvent} before the primed TNT exists, so a cancel leaves the block and spawns nothing. The block's
     * removal that follows obeys the same answer. True when cancelled.
     */
    public static boolean cancelIgnition(Level level, BlockPos pos, BlockState state, @Nullable Entity igniter) {
        if (!(level instanceof ServerLevel server)) return false;
        Context ctx = CONTEXT.get();
        if (ctx.decidedLevel == level && ctx.decidedPos == pos.asLong() && ctx.decidedTime == level.getGameTime()) {
            return ctx.decidedCancelled; // already asked by the item use that lit it
        }
        Actor actor = actorOrStack(igniter);
        if (actor == null) actor = outsider(ctx, server, pos);
        if (actor == null || !Hooks.listens(BlockBreakEvent.class)) return false;
        boolean cancelled = Hooks.post(new BlockBreakEvent(server, pos, state,
                level.getFluidState(pos).createLegacyBlock(), IGNITED, actor)).isCancelled();
        ctx.decidedLevel = level;
        ctx.decidedPos = pos.asLong();
        ctx.decidedTime = level.getGameTime();
        ctx.decidedCancelled = cancelled;
        return cancelled;
    }

    /** Drops a decision nothing will consume, such as an item use that was refused before anything happened. */
    public static void forgetIgnition() {
        Context ctx = CONTEXT.get();
        ctx.decidedLevel = null;
        ctx.decidedPos = NO_POS;
    }

    /** Silences the generic hook for one position in one level, until {@link #restoreSuppressAt()}. */
    public static void suppressAt(LevelAccessor level, BlockPos pos) {
        Context ctx = CONTEXT.get();
        int i = ctx.suppressDepth;
        if (i == ctx.suppressLevels.length) {
            ctx.suppressLevels = java.util.Arrays.copyOf(ctx.suppressLevels, i * 2);
            ctx.suppressPositions = java.util.Arrays.copyOf(ctx.suppressPositions, i * 2);
        }
        ctx.suppressLevels[i] = level;
        ctx.suppressPositions[i] = pos.asLong();
        ctx.suppressDepth = i + 1;
    }

    public static void restoreSuppressAt() {
        Context ctx = CONTEXT.get();
        ctx.suppressLevels[--ctx.suppressDepth] = null;
    }

    /**
     * Hides the current suppression from code a plugin's handler runs, so a handler's own block changes are checked
     * like any other. Returns what to hand {@link #resumeSuppression} after the handlers return.
     */
    public static long pauseSuppression() {
        Context ctx = CONTEXT.get();
        long saved = ((long) ctx.suppressedFloor << 32) | (ctx.suppressDepthFloor & 0xFFFFFFFFL);
        ctx.suppressedFloor = ctx.suppressed;
        ctx.suppressDepthFloor = ctx.suppressDepth;
        return saved;
    }

    public static void resumeSuppression(long saved) {
        Context ctx = CONTEXT.get();
        ctx.suppressedFloor = (int) (saved >>> 32);
        ctx.suppressDepthFloor = (int) saved;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Actors

    /** The actor for something an entity does: the player, a fake player for whatever is on the stack, or the entity. */
    static Actor actorFor(Entity entity) {
        Actor top = EntityActors.current();
        if (entity instanceof FakePlayer fake) {
            if (top instanceof Actor.FakePlayer f && f.fake() == fake) return top;
            return new Actor.FakePlayer(fake, top != null ? top.attribution() : Attribution.none());
        }
        if (entity instanceof ServerPlayer player) return new Actor.Player(player);
        if (top instanceof Actor.EntityActor e && e.entity() == entity) return top;
        return new Actor.EntityActor(entity, Actors.remembered(entity));
    }

    /** The actor for an entity, or whoever is on the stack when there is no entity; null when neither. */
    static @Nullable Actor actorOrStack(@Nullable Entity entity) {
        return entity != null ? actorFor(entity) : EntityActors.current();
    }

    private static Actor orElse(@Nullable Actor actor, Actor fallback) {
        return actor != null ? actor : fallback;
    }

    /** Who is acting when no entity is named: the stack, a block's tick, or nobody known. */
    private static Actor currentOr(Actor fallback) {
        Actor actor = current();
        return actor != null ? actor : fallback;
    }

    /** The pushed actor, else the block entity ticking now, else null. */
    private static @Nullable Actor current() {
        Actor top = EntityActors.current();
        if (top != null) return top;
        Context ctx = CONTEXT.get();
        return ctx.ticking() ? ctx.tickActor() : null;
    }

    /**
     * {@link Actor.Unknown} for a change at {@code pos} that nobody is behind, inside an area a plugin watches; null
     * elsewhere, or while vanilla works on its own ({@link AreaIndex#enterVanillaWork}) or a block ticks. The caller
     * has already found no actor.
     */
    private static @Nullable Actor outsider(Context ctx, ServerLevel level, BlockPos pos) {
        return !ctx.ticking() && AreaIndex.outsiderAt(level, pos) ? Actor.Unknown.INSTANCE : null;
    }

    /** Whether a registry id is outside the minecraft namespace; unregistered things count as modded. */
    static boolean modded(ResourceLocation id) {
        return id == null || !id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE);
    }

    /** Whether a block entity type is a mod's. Two field reads, no registry lookup. */
    public static boolean modded(BlockEntityType<?> type) {
        var holder = type.builtInRegistryHolder();
        return !holder.isBound() || modded(holder.key().location());
    }

    /** Whether a block is a mod's. Two field reads, no registry lookup, so it is cheap enough for every tick. */
    public static boolean modded(Block block) {
        var holder = block.builtInRegistryHolder();
        return !holder.isBound() || modded(holder.key().location());
    }

    /** Whether a fluid is a mod's, as {@link #modded(Block)}. */
    public static boolean modded(Fluid fluid) {
        var holder = fluid.builtInRegistryHolder();
        return !holder.isBound() || modded(holder.key().location());
    }

    /**
     * The machine actor for a ticking block entity, reusing {@code cached} while its attribution and place are the
     * same, so a machine ticking every tick doesn't build a new actor each time.
     */
    public static Actor.Machine machine(Actor.@Nullable Machine cached, ServerLevel level, BlockEntity blockEntity) {
        Attribution attribution = Actors.remembered(blockEntity);
        if (cached != null && cached.level() == level && cached.pos().equals(blockEntity.getBlockPos())
                && cached.attribution().equals(attribution)) {
            return cached;
        }
        return new Actor.Machine(level, blockEntity.getBlockPos(), attribution);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Block ticks

    /**
     * Enters a block's random or scheduled tick. A vanilla block acts as {@link Actor.Nature}; a modded block, with
     * nothing on the stack, is pushed as {@link Actor.Unknown}, so whatever it does carries that.
     */
    public static void enterTick(BlockState state, BlockPos pos) {
        Context ctx = CONTEXT.get();
        int i = ctx.ticks;
        if (i == ctx.tickBlocks.length) {
            ctx.tickBlocks = java.util.Arrays.copyOf(ctx.tickBlocks, i * 2);
            ctx.tickPositions = java.util.Arrays.copyOf(ctx.tickPositions, i * 2);
            ctx.tickActors = java.util.Arrays.copyOf(ctx.tickActors, i * 2);
            ctx.tickScopes = java.util.Arrays.copyOf(ctx.tickScopes, i * 2);
        }
        Block block = state.getBlock();
        Actor actor = Actor.Nature.INSTANCE;
        long scope = 0;
        if (modded(block)) {
            actor = Actor.Unknown.INSTANCE;
            if (Actors.isEmpty()) scope = Actors.Port.enter(PortKey.KEY, actor);
        }
        ctx.tickBlocks[i] = block;
        ctx.tickPositions[i] = pos.asLong();
        ctx.tickActors[i] = actor;
        ctx.tickScopes[i] = scope;
        ctx.ticks = i + 1;
    }

    public static void exitTick() {
        Context ctx = CONTEXT.get();
        int i = --ctx.ticks;
        long scope = ctx.tickScopes[i];
        ctx.tickBlocks[i] = null;
        ctx.tickActors[i] = null;
        ctx.tickScopes[i] = 0;
        if (scope != 0) Actors.Port.exit(PortKey.KEY, scope);
    }

    /** Weather forming snow and ice: every change while it runs is a {@link BlockFormEvent}. */
    public static void enterForming() {
        CONTEXT.get().forming++;
    }

    public static void exitForming() {
        CONTEXT.get().forming--;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Level.setBlock and Level.destroyBlock

    private enum Kind { CHANGE, BREAK, PLACE, TRANSFORM, IGNITE, FADE, GROW, SPREAD, FORM }

    private static Class<? extends BlockChangeEvent> type(Kind kind) {
        return switch (kind) {
            case CHANGE -> BlockChangeEvent.class;
            case BREAK -> BlockBreakEvent.class;
            case PLACE -> BlockPlaceEvent.class;
            case TRANSFORM -> BlockTransformEvent.class;
            case IGNITE -> BlockIgniteEvent.class;
            case FADE -> BlockFadeEvent.class;
            case GROW -> BlockGrowEvent.class;
            case SPREAD -> BlockSpreadEvent.class;
            case FORM -> BlockFormEvent.class;
        };
    }

    private static BlockChangeEvent build(Kind kind, ServerLevel level, BlockChange change, long source, Actor actor) {
        return build(kind, level, change, source, BlockBreakEvent.Tool.Unknown.NOT_STATED, actor);
    }

    /** {@code tool} is used only for a {@link Kind#BREAK}. */
    private static BlockChangeEvent build(Kind kind, ServerLevel level, BlockChange change, long source,
            BlockBreakEvent.Tool tool, Actor actor) {
        BlockPos pos = change.getPos();
        return switch (kind) {
            case CHANGE -> new BlockChangeEvent(level, change, actor);
            case BREAK -> new BlockBreakEvent(level, change, tool, actor);
            case PLACE -> new BlockPlaceEvent(level, change, actor);
            case TRANSFORM -> new BlockTransformEvent(level, change, actor);
            case IGNITE -> new BlockIgniteEvent(level, change, actor);
            case FADE -> new BlockFadeEvent(level, change, actor);
            case GROW -> new BlockGrowEvent(level, change, actor);
            case SPREAD -> new BlockSpreadEvent(level, pos, BlockPos.of(source), change.getBefore(), change.getAfter(),
                    actor);
            case FORM -> new BlockFormEvent(level, change, actor);
        };
    }

    private static boolean isFire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /** What an actor's change from {@code before} to {@code after} is. */
    private static @Nullable Kind actorKind(BlockState before, BlockState after) {
        if (before == after) return null;
        if (isFire(after) && !isFire(before)) return Kind.IGNITE;
        if (before.getBlock() == after.getBlock()) return Kind.CHANGE;
        if (isEmpty(after)) {
            if (!before.canBeReplaced() || after.isAir()) return Kind.BREAK;
            return Kind.PLACE;
        }
        return before.canBeReplaced() ? Kind.PLACE : Kind.TRANSFORM;
    }

    /**
     * What a block's own tick changing {@code pos} is, or null when it is none of the natural kinds. Hatching eggs,
     * falling blocks and bubble columns are left out: they are entity spawns or mechanics, and cancelling a hatch as
     * a fade would keep the egg and spawn the creature anyway.
     */
    private static @Nullable Kind naturalKind(Context ctx, BlockPos pos, BlockState before, BlockState after) {
        Block ticking = ctx.tickBlock();
        if (ticking instanceof Fallable || ticking instanceof TurtleEggBlock || ticking instanceof FrogspawnBlock
                || ticking instanceof SnifferEggBlock || ticking instanceof BubbleColumnBlock) {
            return null;
        }
        if (pos.asLong() == ctx.tickPos()) {
            if (before.getBlock() == after.getBlock()) {
                return grows(ticking) && !(ticking instanceof FrostedIceBlock) ? Kind.GROW : null;
            }
            if (ticking instanceof SpreadingSnowyDirtBlock) return Kind.FADE; // grass dying under a block
            if (ticking instanceof ChangeOverTimeBlock<?>) return Kind.FORM; // copper weathering
            return grows(ticking) ? Kind.GROW : Kind.FADE; // a bamboo shoot becoming bamboo; ice melting
        }
        boolean open = before.canBeReplaced() || ticking instanceof SpreadingSnowyDirtBlock && before.is(Blocks.DIRT);
        if (!open || before.getBlock() == after.getBlock()) return null;
        if (ticking instanceof SpreadingSnowyDirtBlock || ticking instanceof VineBlock
                || ticking instanceof MushroomBlock) {
            return Kind.SPREAD;
        }
        return grows(ticking) ? Kind.GROW : null; // sugar cane upward, a stem's fruit beside it
    }

    private static boolean grows(Block block) {
        return block instanceof BonemealableBlock || block instanceof CactusBlock || block instanceof SugarCaneBlock
                || block instanceof NetherWartBlock || block instanceof ChorusFlowerBlock;
    }

    /**
     * The generic hook at the top of {@code Level.setBlock}. Fires the change as the most specific event that fits,
     * when an actor is on the stack or a block's own tick, weather or fire is making it.
     */
    public static boolean cancelSetBlock(Level level, BlockPos pos, BlockState after) {
        if (level.isClientSide || level.captureBlockSnapshots || level.restoringBlockSnapshots
                || !(level instanceof ServerLevel server)) {
            return false;
        }
        Context ctx = CONTEXT.get();
        if (ctx.deferredClear == null) return decideSetBlock(ctx, level, server, pos, after);
        // A command emptying a container before replacing it: decided first, so a refusal keeps the contents and a
        // listener sees them. Taken off the context before deciding, so a handler's own changes can't run it.
        Runnable clear = null;
        if (ctx.deferredClearLevel == level && ctx.deferredClearPos == pos.asLong()) {
            clear = ctx.deferredClear;
            ctx.deferredClear = null;
            ctx.deferredClearLevel = null;
            ctx.deferredClearPos = NO_POS;
        }
        boolean cancelled = decideSetBlock(ctx, level, server, pos, after);
        if (clear != null && !cancelled) clear.run();
        return cancelled;
    }

    private static boolean decideSetBlock(Context ctx, Level level, ServerLevel server, BlockPos pos,
            BlockState after) {
        if (ctx.decidedPos == pos.asLong() && ctx.decidedLevel == level) {
            boolean refuse = ctx.decidedCancelled && ctx.decidedTime == level.getGameTime();
            ctx.decidedLevel = null;
            ctx.decidedPos = NO_POS;
            if (ctx.decidedTime == level.getGameTime()) return refuse;
        }
        if (ctx.suppresses(level, pos)) return false;
        Actor actor = EntityActors.current();
        boolean ticking = ctx.ticking() && (actor == null || actor == ctx.tickActor());
        boolean fire = isFire(after);
        if (actor == null && !ticking && ctx.forming == 0) {
            // Nobody is behind this write: inside an area a plugin watches, it is an outsider's. Elsewhere it matters
            // only if it starts a fire, which is nature's.
            actor = outsider(ctx, server, pos);
            if (actor == null && !fire) return false;
        }

        BlockState before = level.getBlockState(pos);
        if (before == after) return false;
        Kind kind;
        Actor who;
        long source = NO_POS;
        if (fire && !isFire(before)) {
            kind = Kind.IGNITE;
            who = actor != null ? actor : ticking ? ctx.tickActor() : Actor.Nature.INSTANCE;
        } else if (actor == null && ctx.forming > 0) {
            kind = Kind.FORM;
            who = Actor.Nature.INSTANCE;
        } else if (ticking) {
            if (ctx.tickBlock() instanceof LeavesBlock) return false; // decay fires LeavesDecayEvent
            who = ctx.tickActor();
            kind = naturalKind(ctx, pos, before, after);
            source = ctx.tickPos();
            // A modded block's tick doing something unnatural, such as breaking its neighbour, is still reported.
            if (kind == null && who instanceof Actor.Unknown) kind = actorKind(before, after);
        } else if (actor != null) {
            who = actor;
            kind = actorKind(before, after);
        } else {
            return false;
        }
        if (kind == null || !Hooks.listens(type(kind))) return false;
        BlockBreakEvent.Tool tool = kind == Kind.BREAK
                ? harvested(ctx, server, pos, before, REMOVED_DIRECTLY) : BlockBreakEvent.Tool.Unknown.NOT_STATED;
        BlockChangeEvent event = Hooks.post(build(kind, server, BlockChange.of(server, pos, before, after), source,
                tool, who));
        boolean cancelled = event.isCancelled();
        HeldEffects.changeDecided(cancelled);
        return cancelled;
    }

    /**
     * {@code Clearable.tryClear} where vanilla empties a container before replacing it ({@code /setblock},
     * {@code /fill}, {@code /clone}, structure placement), so its contents don't spill. The emptying waits for the
     * replacing {@code setBlock} at that position and happens only if that change is allowed: a refused replace keeps
     * the contents, and its event can read them. Anywhere but a live server level, it empties at once.
     */
    public static void deferClear(@Nullable LevelAccessor level, Object blockEntity, Runnable clear) {
        if (!(level instanceof ServerLevel server) || !(blockEntity instanceof BlockEntity entity)
                || server.captureBlockSnapshots || server.restoringBlockSnapshots) {
            clear.run();
            return;
        }
        Context ctx = CONTEXT.get();
        Runnable stale = ctx.deferredClear;
        ctx.deferredClear = null; // one whose setBlock never came: empty it as vanilla would have
        if (stale != null) stale.run();
        ctx.deferredClear = clear;
        ctx.deferredClearLevel = server;
        ctx.deferredClearPos = entity.getBlockPos().asLong();
    }

    /** As {@link #deferClear}, for a block entity in the level it belongs to. */
    public static void deferClear(Object blockEntity, Runnable clear) {
        deferClear(blockEntity instanceof BlockEntity entity ? entity.getLevel() : null, blockEntity, clear);
    }

    /**
     * A golem or the wither being built from a pattern of blocks: the blocks are consumed and the creature spawns,
     * and that is one operation. {@link #cancelPatternBuild} asks about every block before any is cleared; if the
     * creature's spawn is then refused, every block is put back. Wraps the whole build, so whatever the build did is
     * settled when it returns.
     */
    public static void patternBuild(Level level, Runnable work) {
        if (!(level instanceof ServerLevel)) {
            work.run();
            return;
        }
        Context ctx = CONTEXT.get();
        boolean outerBuilding = ctx.building;
        List<BlockSnapshot> outerBuilt = ctx.built;
        Entity outerCreature = ctx.builtCreature;
        dev.iredsc.hadron.api.actor.Operation outerOperation = ctx.buildOperation;
        long outerScope = ctx.buildScope;
        ctx.building = true;
        ctx.built = null;
        ctx.builtCreature = null;
        ctx.buildOperation = null;
        ctx.buildScope = 0;
        try {
            work.run();
            List<BlockSnapshot> built = ctx.built;
            if (built != null) {
                unsuppress();
                ctx.built = null;
                if (!java.util.Objects.requireNonNull(ctx.builtCreature, "built blocks without their creature")
                        .isAddedToLevel()) {
                    ctx.unbuilding = true;
                    try {
                        restore(level, built);
                    } finally {
                        ctx.unbuilding = false;
                    }
                }
            }
        } finally {
            if (ctx.built != null) unsuppress(); // the build threw after it was allowed
            if (ctx.buildScope != 0) Actors.Port.exit(PortKey.KEY, ctx.buildScope);
            ctx.building = outerBuilding;
            ctx.built = outerBuilt;
            ctx.builtCreature = outerCreature;
            ctx.buildOperation = outerOperation;
            ctx.buildScope = outerScope;
        }
    }

    /**
     * Opens an outsider's build operation for making {@code entity}'s spawn event, when {@code entity} is the creature
     * of an allowed build nobody is behind; 0 otherwise. The caller exits it before posting, so the spawn is in the
     * build's operation while the handlers see nobody behind it and act as themselves, as for the build's blocks.
     */
    public static long enterOutsidersBuild(Entity entity) {
        Context ctx = CONTEXT.get();
        dev.iredsc.hadron.api.actor.Operation operation = ctx.buildOperation;
        if (!ctx.building || ctx.builtCreature != entity || operation == null || ctx.buildScope != 0) return 0;
        return Actors.Port.enter(PortKey.KEY, operation.actor(), operation);
    }

    /**
     * A pattern build is about to clear its blocks to make {@code creature}. Each block's removal is asked about now,
     * before any is cleared, as the change the generic hook would report; true when one was refused, and then nothing
     * is cleared and nothing spawns. Allowed, the clearing that follows is not asked about again.
     */
    public static boolean cancelPatternBuild(Level level, BlockPattern.BlockPatternMatch match, Entity creature) {
        if (!(level instanceof ServerLevel server)) return false;
        Context ctx = CONTEXT.get();
        if (ctx.unbuilding) return true;
        List<BlockSnapshot> blocks = new ArrayList<>();
        for (int i = 0; i < match.getWidth(); i++) {
            for (int j = 0; j < match.getHeight(); j++) {
                BlockPos pos = match.getBlock(i, j, 0).getPos();
                if (!server.getBlockState(pos).isAir()) blocks.add(BlockSnapshot.create(server.dimension(), server, pos));
            }
        }
        Actor actor = current();
        if (ctx.suppressed <= ctx.suppressedFloor) {
            if (actor != null && ctx.building && ctx.buildOperation == null) {
                ctx.buildOperation = dev.iredsc.hadron.api.actor.Operation.single(actor);
                ctx.buildScope = Actors.Port.enter(PortKey.KEY, actor, ctx.buildOperation);
            }
            BlockState air = Blocks.AIR.defaultBlockState();
            for (BlockSnapshot block : blocks) {
                // Nobody behind the build: only the blocks inside a watched area are anyone's business.
                Actor who = actor != null ? actor : outsider(ctx, server, block.getPos());
                BlockState before = block.getState();
                Kind kind = actorKind(before, air);
                if (who == null || kind == null || !Hooks.listens(type(kind))) continue;
                // The first watched block makes it an outsider's build: its blocks and creature are one operation,
                // open only while each event is made, so the handlers see nobody behind it and act as themselves.
                if (ctx.building && ctx.buildOperation == null) {
                    ctx.buildOperation = dev.iredsc.hadron.api.actor.Operation.single(who);
                }
                long making = ctx.buildScope == 0 && ctx.buildOperation != null
                        ? Actors.Port.enter(PortKey.KEY, who, ctx.buildOperation) : 0;
                BlockChangeEvent event;
                try {
                    event = build(kind, server, BlockChange.of(server, block.getPos(), before, air), NO_POS,
                            BlockBreakEvent.Tool.None.INSTANCE, who);
                } finally {
                    if (making != 0) Actors.Port.exit(PortKey.KEY, making);
                }
                if (Hooks.post(event).isCancelled()) return true;
            }
            // Allowed: the creature's spawn joins the outsider's operation through enterOutsidersBuild.
        }
        if (ctx.building) {
            ctx.built = blocks;
            ctx.builtCreature = creature;
            suppress();
        }
        return false;
    }

    /** Puts blocks back as they were, data included, without reporting it as a new change. */
    private static void restore(Level level, List<BlockSnapshot> snapshots) {
        boolean restoring = level.restoringBlockSnapshots;
        level.restoringBlockSnapshots = true;
        try {
            for (BlockSnapshot snapshot : Lists.reverse(snapshots)) {
                snapshot.restoreToLocation(level, snapshot.getPos(), Block.UPDATE_ALL);
            }
        } finally {
            level.restoringBlockSnapshots = restoring;
        }
    }

    /**
     * The top of {@code Level.destroyBlock}, before anything drops. A break by an actor or entity, or a block's tick
     * breaking itself (a cactus or sugar cane losing its support) as a {@link BlockFadeEvent}.
     */
    public static boolean cancelDestroy(Level level, BlockPos pos, @Nullable Entity entity) {
        if (level.isClientSide || level.captureBlockSnapshots || level.restoringBlockSnapshots
                || !(level instanceof ServerLevel server)) {
            return false;
        }
        Context ctx = CONTEXT.get();
        if (ctx.suppresses(level, pos)) return false;
        Actor actor = actorOrStack(entity);
        boolean ticking = ctx.ticking() && entity == null && (actor == null || actor == ctx.tickActor());
        Kind kind;
        if (ticking) {
            if (ctx.tickBlock() instanceof LeavesBlock) return false;
            actor = ctx.tickActor();
            kind = actor instanceof Actor.Nature ? Kind.FADE : Kind.BREAK;
        } else if (actor != null) {
            kind = Kind.BREAK;
        } else if ((actor = outsider(ctx, server, pos)) != null) {
            kind = Kind.BREAK;
        } else {
            return false;
        }
        BlockState before = level.getBlockState(pos);
        if (before.isAir() || !Hooks.listens(type(kind))) return false;
        BlockState after = level.getFluidState(pos).createLegacyBlock();
        // Vanilla's destroyBlock harvests with nothing; a mod that worked out the drops first used its own tool.
        BlockBreakEvent.Tool tool = kind == Kind.BREAK
                ? harvested(ctx, server, pos, before, BlockBreakEvent.Tool.None.INSTANCE)
                : BlockBreakEvent.Tool.Unknown.NOT_STATED;
        return Hooks.post(build(kind, server, BlockChange.of(server, pos, before, after), NO_POS, tool, actor))
                .isCancelled();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What a block is broken with

    private static final BlockBreakEvent.Tool REMOVED_DIRECTLY =
            new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.REMOVED_DIRECTLY);
    private static final BlockBreakEvent.Tool IGNITED =
            new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.IGNITED);
    private static final BlockBreakEvent.Tool PERMISSION_CHECK =
            new BlockBreakEvent.Tool.Unknown(BlockBreakEvent.Tool.Reason.PERMISSION_CHECK);

    /** A player mining through the game mode, saved by {@link #enterMining} and put back by {@link #exitMining}. */
    public record Mining(ServerPlayer player, LevelAccessor level, long pos) {}

    /** The game mode is about to mine {@code pos} for {@code player}. Returns what to put back on exit. */
    public static @Nullable Mining enterMining(ServerPlayer player, LevelAccessor level, BlockPos pos) {
        Context ctx = CONTEXT.get();
        Mining previous = ctx.mining;
        ctx.mining = new Mining(player, level, pos.asLong());
        return previous;
    }

    public static void exitMining(@Nullable Mining previous) {
        CONTEXT.get().mining = previous;
    }

    /**
     * What a NeoForge break event's player breaks with: their main hand while the game mode mines that block for them,
     * as vanilla harvests with it; otherwise a mod asking whether it may break, before saying with what.
     */
    static BlockBreakEvent.Tool breakEventTool(LevelAccessor level, BlockPos pos, @Nullable Player player) {
        Mining mining = CONTEXT.get().mining;
        if (player == null || mining == null || mining.player() != player || mining.level() != level
                || mining.pos() != pos.asLong()) {
            return PERMISSION_CHECK;
        }
        return of(player.getMainHandItem());
    }

    private static BlockBreakEvent.Tool of(ItemStack item) {
        return item.isEmpty() ? BlockBreakEvent.Tool.None.INSTANCE : new BlockBreakEvent.Tool.Item(item);
    }

    /** {@code Block.getDrops} is working out {@code state}'s drops at {@code pos} with {@code tool}. */
    public static void harvesting(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        if (!Hooks.listens(BlockBreakEvent.class)) return;
        Context ctx = CONTEXT.get();
        ctx.harvestLevel = level;
        ctx.harvestPos = pos.asLong();
        ctx.harvestState = state;
        ctx.harvestTime = level.getGameTime();
        ctx.harvestActor = current();
        ctx.harvestTool = tool.copy();
    }

    /**
     * The tool {@code before}'s drops were just worked out with, when that was this very block, under the same actor
     * as now (a machine's tick, a pushed actor), this tick; {@code otherwise} when not. Consumed when it matches.
     */
    private static BlockBreakEvent.Tool harvested(Context ctx, ServerLevel level, BlockPos pos, BlockState before,
            BlockBreakEvent.Tool otherwise) {
        if (ctx.harvestLevel != level || ctx.harvestPos != pos.asLong() || ctx.harvestState != before
                || ctx.harvestTime != level.getGameTime() || !java.util.Objects.equals(ctx.harvestActor, current())) {
            return otherwise;
        }
        BlockBreakEvent.Tool tool = of(ctx.harvestTool);
        ctx.harvestLevel = null;
        ctx.harvestPos = NO_POS;
        ctx.harvestState = null;
        ctx.harvestActor = null;
        ctx.harvestTool = ItemStack.EMPTY;
        return tool;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Specific producers

    /** Fire is about to burn the block at {@code pos}, set off by the fire ticking next to it. */
    public static boolean cancelBurn(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(BlockBurnEvent.class)) return false;
        Context ctx = CONTEXT.get();
        BlockPos fire = ctx.ticking() && ctx.tickBlock() instanceof BaseFireBlock ? BlockPos.of(ctx.tickPos()) : null;
        return Hooks.post(new BlockBurnEvent(server, pos, level.getBlockState(pos), fire,
                currentOr(Actor.Nature.INSTANCE))).isCancelled();
    }

    /** Leaves at {@code pos} are about to decay. */
    public static boolean cancelLeavesDecay(ServerLevel level, BlockPos pos, BlockState leaves) {
        if (!Hooks.listens(LeavesDecayEvent.class)) return false;
        BlockState after = level.getFluidState(pos).createLegacyBlock();
        return Hooks.post(new LeavesDecayEvent(level, pos, leaves, after, currentOr(Actor.Nature.INSTANCE)))
                .isCancelled();
    }

    /** A fluid is about to flow into {@code pos}, moving in {@code direction}. */
    public static boolean cancelFlow(LevelAccessor level, BlockPos pos, BlockState before, Direction direction,
            FluidState fluid) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(BlockFromToEvent.class)) return false;
        BlockPos from = pos.relative(direction.getOpposite());
        return Hooks.post(new BlockFromToEvent(server, from, direction, before, fluid.createLegacyBlock(),
                currentOr(Actor.Nature.INSTANCE))).isCancelled();
    }

    /** Fluids meeting are about to form {@code after} at {@code pos}. */
    public static boolean cancelForm(LevelAccessor level, BlockPos pos, BlockState after) {
        if (!(level instanceof ServerLevel server) || !Hooks.listens(BlockFormEvent.class)) return false;
        BlockState before = level.getBlockState(pos);
        if (before == after) return false;
        return Hooks.post(new BlockFormEvent(server, pos, before, after, currentOr(Actor.Nature.INSTANCE)))
                .isCancelled();
    }

    /**
     * An actor is about to use a block. Called for both halves of vanilla's click (with the item, then without), and
     * fires once: the half without the item is skipped right after the half with it, for the same player and block.
     */
    public static boolean cancelUse(BlockState state, Level level, Player player, InteractionHand hand,
            ItemStack item, BlockHitResult hit, boolean withItem) {
        if (!(level instanceof ServerLevel server) || player == null) return false;
        Context ctx = CONTEXT.get();
        BlockPos pos = hit.getBlockPos();
        long time = server.getGameTime();
        if (!withItem && ctx.lastUser == player.getId() && ctx.lastUsePos == pos.asLong()
                && ctx.lastUseTime == time) {
            ctx.lastUser = -1;
            return false;
        }
        if (!Hooks.listens(BlockUseEvent.class)) return false;
        BlockUseEvent event = Hooks.post(new BlockUseEvent(server, pos, state, hand, item, hit, actorFor(player)));
        if (event.isCancelled()) {
            resync(player, server, pos);
            return true;
        }
        if (withItem) {
            ctx.lastUser = player.getId();
            ctx.lastUsePos = pos.asLong();
            ctx.lastUseTime = time;
        }
        return false;
    }

    /**
     * A player is about to set a sign's text. Returns the text to write, or null if cancelled. Only fires for an edit
     * vanilla would accept.
     */
    public static @Nullable List<FilteredText> signChange(SignBlockEntity sign, Player player, boolean front,
            List<FilteredText> text) {
        if (!(sign.getLevel() instanceof ServerLevel level) || sign.isWaxed()
                || !player.getUUID().equals(sign.getPlayerWhoMayEdit()) || !Hooks.listens(SignChangeEvent.class)) {
            return text;
        }
        List<String> lines = new ArrayList<>(text.size());
        for (FilteredText line : text) lines.add(player.isTextFilteringEnabled() ? line.filteredOrEmpty() : line.raw());
        BlockPos pos = sign.getBlockPos();
        SignChangeEvent event = Hooks.post(new SignChangeEvent(level, pos, sign.getBlockState(), lines, front,
                actorFor(player)));
        if (event.isCancelled()) {
            sign.setAllowedPlayerEditor(null);
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_ALL);
            return null;
        }
        if (!event.isChanged()) return text;
        List<FilteredText> edited = new ArrayList<>();
        for (String line : event.getLines()) edited.add(FilteredText.passThrough(line));
        return edited;
    }

    /** A bucket is about to pour at {@code pos}; fires only for the space it will actually fill. */
    public static boolean cancelBucketEmpty(BucketItem bucket, @Nullable Player player, Level level, BlockPos pos,
            @Nullable BlockHitResult hit, ItemStack container) {
        if (!(level instanceof ServerLevel server) || !(bucket.content instanceof FlowingFluid)
                || CONTEXT.get().suppresses(level, pos) || !Hooks.listens(BucketEmptyEvent.class)) {
            return false;
        }
        BlockState before = level.getBlockState(pos);
        BlockState after;
        if (before.isAir() || before.canBeReplaced(bucket.content)) {
            after = bucket.content.defaultFluidState().createLegacyBlock();
        } else if (before.getBlock() instanceof LiquidBlockContainer container1
                && container1.canPlaceLiquid(player, level, pos, before, bucket.content)) {
            after = before.hasProperty(BlockStateProperties.WATERLOGGED) && bucket.content.isSame(Fluids.WATER)
                    ? before.setValue(BlockStateProperties.WATERLOGGED, true) : before;
        } else {
            return false; // vanilla tries the space in front instead, which calls this again
        }
        if (level.dimensionType().ultraWarm() && bucket.content.isSame(Fluids.WATER)) after = before; // evaporates
        ItemStack stack = container != null ? container : new ItemStack(bucket);
        Actor actor = player != null ? actorFor(player) : currentOr(Actor.Unknown.INSTANCE);
        BucketEmptyEvent event = Hooks.post(new BucketEmptyEvent(server, BlockChange.of(server, pos, before, after),
                stack, hit != null ? hit.getDirection() : null, actor));
        if (event.isCancelled()) resync(player, server, pos);
        return event.isCancelled();
    }

    /** An empty bucket is about to take fluid from {@code pos}. {@code player} is null for a dispenser. */
    public static boolean cancelBucketFill(@Nullable Player player, LevelAccessor level, BlockPos pos, BlockState before,
            ItemStack bucket, @Nullable Direction face) {
        if (!(level instanceof ServerLevel server) || CONTEXT.get().suppresses(server, pos)
                || !Hooks.listens(BucketFillEvent.class)) {
            return false;
        }
        BlockState after = before.hasProperty(BlockStateProperties.WATERLOGGED)
                ? before.setValue(BlockStateProperties.WATERLOGGED, false)
                : Blocks.AIR.defaultBlockState();
        Actor actor = player != null ? actorFor(player) : currentOr(Actor.Unknown.INSTANCE);
        BucketFillEvent event = Hooks.post(new BucketFillEvent(server, BlockChange.of(server, pos, before, after),
                bucket, face, actor));
        if (event.isCancelled()) resync(player, server, pos);
        return event.isCancelled();
    }

    /**
     * A dispenser firing: it is the actor while it does, as a machine at its position. A bucket it fills or empties,
     * or TNT it lights, is asked about before anything happens, so a cancelled one stays in the dispenser instead of
     * being thrown out, and cancelled TNT is never primed (vanilla primes it before removing the block).
     */
    public static ItemStack dispense(BlockSource source, ItemStack stack, Supplier<ItemStack> work) {
        ServerLevel level = source.level();
        BlockPos pos = source.pos();
        try (Actors.Scope scope = Actors.push(new Actor.Machine(level, pos,
                Actors.remembered(source.blockEntity())))) {
            BlockPos target = pos.relative(source.state().getValue(DispenserBlock.FACING));
            BlockState state = level.getBlockState(target);
            boolean fill = stack.is(Items.BUCKET) && state.getBlock() instanceof BucketPickup
                    && (level.getFluidState(target).isSource() || state.getBlock() instanceof PowderSnowBlock);
            boolean empty = stack.getItem() instanceof BucketItem bucket && bucket.content != Fluids.EMPTY;
            if (stack.is(Items.FLINT_AND_STEEL) && state.getBlock() instanceof TntBlock) {
                if (cancelIgnition(level, target, state, null)) {
                    forgetIgnition();
                    return stack;
                }
                return EntitySpawnRefunds.dispensing(stack, work); // the removal obeys the decision just made
            }
            if (!fill && !empty) return EntitySpawnRefunds.dispensing(stack, work);
            if (fill ? cancelBucketFill(null, level, target, state, stack, null)
                    : cancelBucketEmpty((BucketItem) stack.getItem(), null, level, target, null, stack)) {
                return stack;
            }
            suppressAt(level, target);
            try {
                // A mob bucket whose mob is refused takes its water back, and stays in the dispenser.
                return EntitySpawnRefunds.dispensing(stack, work);
            } finally {
                restoreSuppressAt();
            }
        }
    }

    /** After a cancelled click the client has already predicted: show it the block and its inventory as they are. */
    private static void resync(@Nullable Player player, ServerLevel level, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer) return;
        sp.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        sp.inventoryMenu.sendAllDataToRemote();
        sp.containerMenu.sendAllDataToRemote();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Captured changes: growth that places many blocks at once

    /**
     * Runs work that may change many blocks, capturing every change with NeoForge's block snapshots, then fires one
     * event for all of them: undone in reverse if cancelled, applied (neighbours notified) if not. Works inside an
     * item use's own capture too, taking only its own snapshots so the item use's event doesn't report them again.
     *
     * @return whether the event was cancelled
     */
    private static boolean captured(ServerLevel level, BooleanSupplier work, boolean[] result,
            Class<? extends BlockChangeEvent> type, Function<List<BlockChange>, BlockChangeEvent> event) {
        if (!level.captureBlockSnapshots && !Hooks.listens(type)) {
            result[0] = work.getAsBoolean(); // nobody listens: run it exactly as vanilla would
            return false;
        }
        boolean outer = !level.captureBlockSnapshots;
        int start = level.capturedBlockSnapshots.size();
        level.captureBlockSnapshots = true;
        List<BlockSnapshot> mine;
        try {
            result[0] = work.getAsBoolean();
        } finally {
            if (outer) level.captureBlockSnapshots = false;
            List<BlockSnapshot> all = level.capturedBlockSnapshots;
            if (all.size() > start) {
                List<BlockSnapshot> tail = all.subList(start, all.size());
                mine = new ArrayList<>(tail);
                tail.clear();
            } else {
                mine = List.of();
            }
        }
        if (mine.isEmpty()) return false;

        // Inside an item use's capture, these are taken out even when nobody listens, so the item use doesn't report
        // the growth as places.
        boolean cancelled = false;
        if (Hooks.listens(type)) {
            Map<BlockPos, BlockSnapshot> first = new LinkedHashMap<>();
            for (BlockSnapshot snapshot : mine) first.putIfAbsent(snapshot.getPos(), snapshot);
            List<BlockChange> changes = new ArrayList<>(first.size());
            for (BlockSnapshot snapshot : first.values()) {
                BlockPos pos = snapshot.getPos();
                BlockState now = level.getBlockState(pos);
                if (now == snapshot.getState()) continue;
                changes.add(BlockChange.withData(level, pos, snapshot.getState(), now, snapshot.getTag(),
                        now.hasBlockEntity() ? level.getBlockEntity(pos) : null));
            }
            if (!changes.isEmpty()) cancelled = Hooks.post(event.apply(changes)).isCancelled();
        }
        if (cancelled) {
            for (BlockSnapshot snapshot : Lists.reverse(mine)) {
                level.restoringBlockSnapshots = true;
                try {
                    snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS);
                } finally {
                    level.restoringBlockSnapshots = false;
                }
            }
        } else {
            for (BlockSnapshot snapshot : mine) {
                BlockPos pos = snapshot.getPos();
                BlockState old = snapshot.getState();
                BlockState now = level.getBlockState(pos);
                now.onPlace(level, pos, old, false);
                level.markAndNotifyBlock(pos, level.getChunkAt(pos), old, now, snapshot.getFlags(), 512);
            }
        }
        return cancelled;
    }

    /**
     * Bone meal applied at {@code pos}: everything it grows is one {@link BlockFertilizeEvent}. Cancelled, the blocks
     * go back and the bone meal is given back.
     */
    public static boolean fertilize(ServerLevel level, BlockPos pos, @Nullable Player player, ItemStack stack,
            BooleanSupplier work) {
        Actor actor = player != null ? actorFor(player) : currentOr(Actor.Unknown.INSTANCE);
        BlockState state = level.getBlockState(pos);
        int count = stack.getCount();
        Context ctx = CONTEXT.get();
        Actor previous = ctx.fertilizer;
        ctx.fertilizer = actor;
        boolean[] result = new boolean[1];
        boolean cancelled;
        try {
            cancelled = captured(level, work, result, BlockFertilizeEvent.class,
                    changes -> new BlockFertilizeEvent(level, pos, state, changes, actor));
        } finally {
            ctx.fertilizer = previous;
        }
        if (cancelled) {
            stack.setCount(count);
            resync(player, level, pos);
            return false;
        }
        return result[0];
    }

    /**
     * A tree, giant mushroom or huge fungus growing from {@code pos}: everything it places is one
     * {@link StructureGrowEvent}. Cancelled, every block goes back and growing reports failure.
     */
    public static boolean growStructure(ServerLevel level, BlockPos pos, BlockState sapling, BooleanSupplier work) {
        Context ctx = CONTEXT.get();
        Actor fertilizer = ctx.fertilizer;
        boolean bonemeal = fertilizer != null;
        Actor actor = fertilizer != null ? fertilizer : currentOr(Actor.Nature.INSTANCE);
        boolean[] result = new boolean[1];
        boolean cancelled = captured(level, work, result, StructureGrowEvent.class,
                changes -> new StructureGrowEvent(level, pos, sapling, changes, bonemeal, actor));
        return !cancelled && result[0];
    }

    /** Translates one item use's captured changes (NeoForge's place events) into the events that fit them. */
    static List<BlockChangeEvent> classifyUse(ServerLevel level, List<BlockSnapshot> snapshots, Actor actor) {
        List<BlockChangeEvent> events = new ArrayList<>(snapshots.size());
        for (BlockSnapshot snapshot : snapshots) {
            BlockPos pos = snapshot.getPos();
            BlockState before = snapshot.getState();
            BlockState after = level.getBlockState(pos);
            Kind kind = actorKind(before, after);
            if (kind == null || !Hooks.listens(type(kind))) continue;
            BlockChange change = BlockChange.withData(level, pos, before, after, snapshot.getTag(),
                    after.hasBlockEntity() ? level.getBlockEntity(pos) : null);
            events.add(build(kind, level, change, NO_POS, actor));
        }
        return events;
    }

    /** Whether every captured change of an item use is a place, so it is one multi-place. */
    static boolean allPlaces(ServerLevel level, List<BlockSnapshot> snapshots) {
        for (BlockSnapshot snapshot : snapshots) {
            if (actorKind(snapshot.getState(), level.getBlockState(snapshot.getPos())) != Kind.PLACE) return false;
        }
        return true;
    }

    /** Who broke the block whose drops are falling: the breaker, else whoever acts now or the ticking block. */
    static Actor dropActor(@Nullable Entity breaker) {
        Actor actor = actorOrStack(breaker);
        return actor != null ? actor : currentOr(Actor.Unknown.INSTANCE);
    }

    static Actor orUnknown(@Nullable Actor actor) {
        return orElse(actor, Actor.Unknown.INSTANCE);
    }
}
