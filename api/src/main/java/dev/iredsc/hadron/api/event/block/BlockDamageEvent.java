package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * A block is about to take lasting damage short of breaking: a cannonball cracking a wall. Damage is a fraction from
 * 0 (intact) to 1 (about to break). Cancel it and the block takes none. Not Bukkit's {@code BlockDamageEvent}, which
 * is a player starting to mine; that is part of {@link BlockBreakEvent} here.
 *
 * <p><b>Fired by bridges</b>: vanilla blocks have no lasting damage. A bridge fires it before its mod records the
 * damage, and records nothing if it is cancelled. When the damage breaks the block, it fires
 * {@link BlockBreakEvent} instead.
 */
@FiredBy(core = NONE,
        bridges = "puts lasting partial damage on a block short of breaking it",
        fillIn = "damage before and after")
public class BlockDamageEvent extends BlockEvent implements Cancellable {
    private final float oldDamage;
    private final float newDamage;
    private boolean cancelled;

    public BlockDamageEvent(ServerLevel level, BlockPos pos, BlockState state, float oldDamage, float newDamage,
            Actor actor) {
        super(level, pos, state, List.of(BlockChange.unchanged(level, pos, state)), actor);
        this.oldDamage = checkDamage(oldDamage);
        this.newDamage = checkDamage(newDamage);
    }

    private static float checkDamage(float damage) {
        if (!(damage >= 0 && damage <= 1)) {
            throw new IllegalArgumentException("Block damage is a fraction from 0 to 1, got " + damage);
        }
        return damage;
    }

    public float getOldDamage() {
        return oldDamage;
    }

    public float getNewDamage() {
        return newDamage;
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
