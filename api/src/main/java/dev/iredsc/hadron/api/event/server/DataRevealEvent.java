package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Server data about a block or entity (and what it holds) is about to be sent to a player who didn't open it: a
 * Jade or WTHIT tooltip, a map radar. Remove keys from {@link #getData()} to hide those fields, or cancel to send
 * nothing.
 *
 * <p><b>Bridge event.</b> Vanilla sends no such data, so Hadron core never fires this; the bridge for an info mod
 * fires it before sending, with the data as the tag it is about to send, and sends what's left (or nothing).
 */
@FiredBy(core = NONE,
        bridges = "is about to send block, entity or inventory data to a player who didn't open it (Jade, WTHIT, map "
                + "radar)",
        fillIn = "the data, with removable fields")
public class DataRevealEvent extends ServerEvent implements Cancellable {
    private final ServerPlayer viewer;
    private final ServerLevel level;
    private final BlockPos pos;
    private final Entity entity;
    private final CompoundTag data;
    private boolean cancelled;

    /** About a block, and its block entity if it has one. */
    public DataRevealEvent(ServerPlayer viewer, ServerLevel level, BlockPos pos, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.level = Objects.requireNonNull(level, "level");
        this.pos = Objects.requireNonNull(pos, "pos").immutable();
        this.entity = null;
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** About an entity. */
    public DataRevealEvent(ServerPlayer viewer, Entity entity, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.level = (ServerLevel) entity.level();
        this.pos = null;
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** Who will see it. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    public ServerLevel getLevel() {
        return level;
    }

    /** The block, when it's about a block. */
    public Optional<BlockPos> getBlockPos() {
        return Optional.ofNullable(pos);
    }

    /** The entity, when it's about an entity. */
    public Optional<Entity> getEntity() {
        return Optional.ofNullable(entity);
    }

    /** What will be sent. Editable: remove a key to hide that field. */
    public CompoundTag getData() {
        return data;
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
