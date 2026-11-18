package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockDropItemEvent;
import dev.iredsc.hadron.api.event.block.BlockFormEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockMultiPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonExtendEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonRetractEvent;
import dev.iredsc.hadron.neoforge.mixin.block.PistonBaseBlockAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Hadron's block events ({@code dev.iredsc.hadron.api.event.block}) from NeoForge's events. The rest come from the
 * block mixins through {@link BlockHooks}.
 */
final class BlockEvents {
    private BlockEvents() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(BlockEvents::breakBlock);
        NeoForge.EVENT_BUS.addListener(BlockEvents::placeBlock);
        NeoForge.EVENT_BUS.addListener(BlockEvents::piston);
        NeoForge.EVENT_BUS.addListener(BlockEvents::fluidPlace);
        // Last, so plugins see the drops mods' listeners settled on.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, BlockEvents::drops);
    }

    /**
     * {@code Block.dropResources}, for every break with drops: player mining, {@code destroyBlock} by anything, a
     * block popping off its support. A cancel empties the drops rather than cancelling NeoForge's event, so what the
     * block does after breaking (an infested block's silverfish) still happens: only the drops are refused.
     */
    private static void drops(BlockDropsEvent event) {
        if (!Hooks.listens(BlockDropItemEvent.class)) return;
        List<net.minecraft.world.entity.item.ItemEntity> items = event.getDrops();
        BlockDropItemEvent hadron = Hooks.post(new BlockDropItemEvent(event.getLevel(), event.getPos(),
                event.getState(), event.getBlockEntity(), items, event.getTool(), event.getDroppedExperience(),
                BlockHooks.dropActor(event.getBreaker())));
        if (hadron.isCancelled()) {
            items.clear();
            event.setDroppedExperience(0);
            return;
        }
        items.removeIf(item -> item == null || item.getItem().isEmpty());
        event.setDroppedExperience(hadron.getExperience());
    }

    /**
     * A player or fake player mining a block, through the game mode, or a mod asking whether it may break one. Fired
     * before anything drops.
     */
    private static void breakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Hooks.listens(BlockBreakEvent.class)) return;
        BlockPos pos = event.getPos();
        BlockBreakEvent hadron = Hooks.post(new BlockBreakEvent(level, pos, event.getState(),
                level.getFluidState(pos).createLegacyBlock(), BlockHooks.breakEventTool(level, pos, event.getPlayer()),
                BlockHooks.orUnknown(BlockHooks.actorOrStack(event.getPlayer()))));
        if (hadron.isCancelled()) event.setCanceled(true);
    }

    /**
     * An item used on a block changed blocks, captured by NeoForge as it happened. Each change becomes the event that
     * fits it (a place, a log stripped, fire lit); several places at once are one multi-place. Cancelling any of
     * them makes NeoForge put every block back and give the item back.
     *
     * <p>Only the item-use path is translated, where the change has already been captured. NeoForge also fires this
     * as a permission check before an enderman places a block, before anything happens; that placement reaches the
     * generic {@code setBlock} hook instead, with the enderman as the actor.
     */
    private static void placeBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() != null && !(event.getEntity() instanceof Player)) return;
        List<BlockSnapshot> snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        Actor actor = BlockHooks.orUnknown(BlockHooks.actorOrStack(event.getEntity()));

        if (snapshots.size() > 1 && BlockHooks.allPlaces(level, snapshots)) {
            if (!Hooks.listens(BlockMultiPlaceEvent.class)) return;
            List<BlockChange> changes = new ArrayList<>(snapshots.size());
            for (BlockSnapshot snapshot : snapshots) {
                BlockPos pos = snapshot.getPos();
                BlockState now = level.getBlockState(pos);
                changes.add(BlockChange.withData(level, pos, snapshot.getState(), now, snapshot.getTag(),
                        now.hasBlockEntity() ? level.getBlockEntity(pos) : null));
            }
            BlockPos origin = event.getPos();
            if (Hooks.post(new BlockMultiPlaceEvent(level, origin, level.getBlockState(origin), changes, actor))
                    .isCancelled()) {
                event.setCanceled(true);
            }
            return;
        }
        for (BlockChangeEvent hadron : BlockHooks.classifyUse(level, snapshots, actor)) {
            if (Hooks.post(hadron).isCancelled()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** A piston about to move, whatever powered it. Fires only when it will really move something, or retract. */
    private static void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        boolean extend = event.getPistonMoveType().isExtend;
        if (!Hooks.listens(extend ? BlockPistonExtendEvent.class : BlockPistonRetractEvent.class)) return;
        BlockPos pos = event.getPos();
        Direction facing = event.getDirection();
        BlockState piston = level.getBlockState(pos);
        boolean sticky = piston.getBlock() instanceof PistonBaseBlock base
                && ((PistonBaseBlockAccessor) base).hadron$isSticky();

        List<BlockPos> toPush = List.of();
        List<BlockPos> toDestroy = List.of();
        // Vanilla pulls only a block that pushes normally; anything else stays and only the head retracts.
        BlockState pulled = level.getBlockState(pos.relative(facing, 2));
        boolean pulls = sticky && !pulled.isAir() && (pulled.getPistonPushReaction() == PushReaction.NORMAL
                || pulled.is(Blocks.PISTON) || pulled.is(Blocks.STICKY_PISTON));
        if (extend || pulls) {
            // NeoForge asks before vanilla removes the head, but vanilla works out what a retract pulls only once the
            // head is gone (a head can't be pushed, so the resolver would find nothing). The resolver is shown air
            // where the head is, for as long as it looks; the world itself is never touched.
            BlockPos headPos = pos.relative(facing);
            boolean hide = !extend && level.getBlockState(headPos).is(Blocks.PISTON_HEAD);
            if (hide) BlockHooks.pistonHiddenHead = headPos;
            try {
                PistonStructureResolver resolver = new PistonStructureResolver(level, pos, facing, extend);
                if (resolver.resolve()) {
                    toPush = resolver.getToPush();
                    toDestroy = resolver.getToDestroy();
                } else if (extend) {
                    return; // it can't push, so it won't move
                }
            } finally {
                if (hide) BlockHooks.pistonHiddenHead = null;
            }
        }
        Direction moving = extend ? facing : facing.getOpposite();

        Map<BlockPos, BlockState[]> states = new LinkedHashMap<>();
        Map<BlockPos, BlockEntity[]> entities = new LinkedHashMap<>();
        BlockPos head = pos.relative(facing);
        BlockState headAfter = extend
                ? Blocks.PISTON_HEAD.defaultBlockState().setValue(DirectionalBlock.FACING, facing)
                : Blocks.AIR.defaultBlockState();
        put(level, states, entities, head, headAfter, null);
        for (BlockPos destroyed : toDestroy) put(level, states, entities, destroyed, Blocks.AIR.defaultBlockState(), null);
        for (BlockPos source : toPush) {
            if (!states.containsKey(source)) put(level, states, entities, source, Blocks.AIR.defaultBlockState(), null);
        }
        List<BlockMoveEvent.Move> moves = new ArrayList<>(toPush.size());
        List<BlockPos> swept = new ArrayList<>(toPush.size() + 1);
        swept.add(head);
        for (BlockPos source : toPush) {
            BlockPos target = source.relative(moving);
            BlockState moved = level.getBlockState(source);
            moves.add(new BlockMoveEvent.Move(source, target, moved));
            if (!swept.contains(target)) swept.add(target);
            BlockEntity movedEntity = moved.hasBlockEntity() ? level.getBlockEntity(source) : null;
            if (states.containsKey(target)) {
                states.get(target)[1] = moved;
                java.util.Objects.requireNonNull(entities.get(target), "states and entities share keys")[1] = movedEntity;
            } else {
                put(level, states, entities, target, moved, movedEntity);
            }
        }
        List<BlockChange> changes = new ArrayList<>(states.size());
        for (Map.Entry<BlockPos, BlockState[]> entry : states.entrySet()) {
            BlockEntity[] be = java.util.Objects.requireNonNull(entities.get(entry.getKey()),
                    "states and entities share keys");
            changes.add(BlockChange.live(level, entry.getKey(), entry.getValue()[0], entry.getValue()[1], be[0], be[1]));
        }
        Actor actor = Actors.currentOr(Actor.Nature.INSTANCE);
        BlockMoveEvent hadron = extend
                ? new BlockPistonExtendEvent(level, pos, piston, facing, sticky, moves, swept, changes, actor)
                : new BlockPistonRetractEvent(level, pos, piston, facing, sticky, moves, swept, changes, actor);
        if (Hooks.post(hadron).isCancelled()) event.setCanceled(true);
    }

    private static void put(ServerLevel level, Map<BlockPos, BlockState[]> states, Map<BlockPos, BlockEntity[]> entities,
            BlockPos pos, BlockState after, @Nullable BlockEntity afterEntity) {
        BlockState before = level.getBlockState(pos);
        states.put(pos, new BlockState[] {before, after});
        entities.put(pos, new BlockEntity[] {before.hasBlockEntity() ? level.getBlockEntity(pos) : null, afterEntity});
    }

    /**
     * Lava flowing into water forming stone. Fire lit by lava comes through here too, but is reported by the generic
     * hook as a {@code BlockIgniteEvent} when it is set.
     */
    private static void fluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        BlockState after = event.getNewState();
        if (after.getBlock() instanceof BaseFireBlock || !Hooks.listens(BlockFormEvent.class)) return;
        if (BlockHooks.cancelForm(event.getLevel(), event.getPos(), after)) {
            event.setNewState(event.getOriginalState());
        }
    }
}
