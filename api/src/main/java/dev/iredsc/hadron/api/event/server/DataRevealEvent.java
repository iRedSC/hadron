package dev.iredsc.hadron.api.event.server;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Server data about a block, an entity (and what it holds) or an account is about to be sent to a player who didn't
 * open it: a Jade or WTHIT tooltip, a map radar, a sigil that reads another player's mana network. Remove keys from
 * {@link #getData()} to hide those fields, or cancel to send nothing.
 *
 * <p><b>Bridge event.</b> Vanilla sends no such data, so Hadron core never fires this; the bridge for an info mod
 * fires it before sending, with the data as the tag it is about to send, and sends what's left (or nothing).
 */
@FiredBy(core = NONE,
        bridges = "is about to send block, entity, inventory or account data to a player who didn't open it (Jade, "
                + "WTHIT, map radar, a sigil reading another player's network)",
        fillIn = "the data, with removable fields")
public class DataRevealEvent extends ServerEvent implements Cancellable {
    private final ServerPlayer viewer;
    private final @Nullable ServerLevel level;
    private final @Nullable BlockPos pos;
    private final @Nullable Entity entity;
    private final ResourceHolder.@Nullable Account account;
    private final CompoundTag data;
    private boolean cancelled;

    /** About a block, and its block entity if it has one. */
    public DataRevealEvent(ServerPlayer viewer, ServerLevel level, BlockPos pos, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.level = Objects.requireNonNull(level, "level");
        this.pos = Objects.requireNonNull(pos, "pos").immutable();
        this.entity = null;
        this.account = null;
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** About an entity. */
    public DataRevealEvent(ServerPlayer viewer, Entity entity, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.level = (ServerLevel) entity.level();
        this.pos = null;
        this.account = null;
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** About an account, such as another player's mana network, which may belong to someone offline. */
    public DataRevealEvent(ServerPlayer viewer, ResourceHolder.Account account, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.account = Objects.requireNonNull(account, "account; use ResourceHolder.Account.player(uuid) for a "
                + "player's");
        this.level = null;
        this.pos = null;
        this.entity = null;
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** Who will see it. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** The level of the block or entity; empty when it's about an account, which has no place. */
    public Optional<ServerLevel> getLevel() {
        return Optional.ofNullable(level);
    }

    /** The block, when it's about a block. */
    public Optional<BlockPos> getBlockPos() {
        return Optional.ofNullable(pos);
    }

    /** The entity, when it's about an entity. */
    public Optional<Entity> getEntity() {
        return Optional.ofNullable(entity);
    }

    /**
     * The account, when it's about an account. Whether it's the viewer's own:
     * {@code account.equals(ResourceHolder.Account.player(event.getViewer().getUUID()))}.
     */
    public Optional<ResourceHolder.Account> getAccount() {
        return Optional.ofNullable(account);
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
