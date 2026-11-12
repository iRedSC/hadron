package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.event.Places;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Server data about a block, an entity (and what it holds) or an account is about to be sent to a player who didn't
 * open it: a Jade or WTHIT tooltip, a map radar, a sigil that reads another player's mana network. Each reveal is about
 * exactly one {@link Target}, sealed so a guard's {@code switch} must handle all three, accounts included:
 *
 * <pre>{@code
 * switch (event.getTarget()) {
 *     case DataRevealEvent.Target.Block b -> ...    // b.level(), b.pos()
 *     case DataRevealEvent.Target.Entity e -> ...   // e.entity()
 *     case DataRevealEvent.Target.Account a -> ...  // a.account(), which may be an offline player's
 * }
 * }</pre>
 *
 * Remove keys from {@link #getData()} to hide those fields, or cancel to send nothing.
 *
 * <p><b>Bridge event.</b> Vanilla sends no such data, so Hadron core never fires this; the bridge for an info mod
 * fires it before sending, with the data as the tag it is about to send, and sends what's left (or nothing).
 */
@FiredBy(core = NONE,
        bridges = "is about to send block, entity, inventory or account data to a player who didn't open it (Jade, "
                + "WTHIT, map radar, a sigil reading another player's network)",
        fillIn = "the data, with removable fields")
public class DataRevealEvent extends ServerEvent implements Cancellable, Placed {
    /** What the data is about. Sealed: a {@code switch} over it must handle every kind. */
    public sealed interface Target {
        /** A block, and its block entity if it has one. */
        record Block(ServerLevel level, BlockPos pos) implements Target {
            public Block {
                Objects.requireNonNull(level, "level");
                pos = Objects.requireNonNull(pos, "pos").immutable();
            }
        }

        /** An entity, and what it holds. */
        record Entity(net.minecraft.world.entity.Entity entity) implements Target {
            public Entity {
                Objects.requireNonNull(entity, "entity");
            }

            /** The entity's level. */
            public ServerLevel level() {
                return (ServerLevel) entity.level();
            }
        }

        /**
         * An account, such as another player's mana network, which may belong to someone offline. Whether it's the
         * viewer's own: {@code account.equals(ResourceHolder.Account.player(event.getViewer().getUUID()))}.
         */
        record Account(ResourceHolder.Account account) implements Target {
            public Account {
                Objects.requireNonNull(account, "account; use ResourceHolder.Account.player(uuid) for a player's");
            }
        }
    }

    private final ServerPlayer viewer;
    private final Target target;
    private final CompoundTag data;
    private boolean cancelled;

    /**
     * @param target what the data is about: {@code new Target.Block(level, pos)}, {@code new Target.Entity(entity)}
     *     or {@code new Target.Account(account)}
     * @param data the tag about to be sent; pass an empty one when only the target is revealed
     */
    public DataRevealEvent(ServerPlayer viewer, Target target, CompoundTag data) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.target = Objects.requireNonNull(target, "target; pass new DataRevealEvent.Target.Block(level, pos), "
                + ".Entity(entity) or .Account(account)");
        this.data = Objects.requireNonNull(data, "data; pass an empty CompoundTag when only the target is revealed");
    }

    /** Who will see it. */
    public ServerPlayer getViewer() {
        return viewer;
    }

    /** What the data is about: a block, an entity or an account. */
    public Target getTarget() {
        return target;
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

    /**
     * Where what the data is about is: the block, where the entity stands, or {@link Place.Nowhere} for an account.
     * Not where the viewer is: they read it from wherever they stand.
     */
    @Override
    public Places getPlaces() {
        return Places.of(switch (target) {
            case Target.Block b -> Place.Spot.at(b.level(), b.pos());
            case Target.Entity e -> Place.of(e.entity());
            case Target.Account a -> Place.Nowhere.account(a.account());
        });
    }
}
