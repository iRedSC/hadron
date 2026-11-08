package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * Lasting data a mod keeps on an entity is about to change, and no other entity event describes it: a colonist's job,
 * a familiar's upgrades, a dragon's age, a player's spell anchor. This is the event a bridge fires when an entity
 * change fits no shipped entity event, leaf or parent. Cancel it and the data stays as it was.
 *
 * <p>A leaf designed but not shipped yet (a relationship, an attribute) doesn't count as describing it: fire this
 * until the leaf ships. When it does, the bridge moves to the leaf, and a plugin listening here for that kind must
 * listen to the leaf instead; the leaf's Javadoc names each kind it replaces.
 *
 * <p>{@link #getKind()} says whose data it is, as an id in the mod's namespace such as {@code minecolonies:job}. The
 * data before and after is in the mod's own format: log it, don't parse it.
 *
 * <p><b>Fired by bridges</b>: vanilla has no single point where a mod's entity data changes, so core doesn't fire it.
 * A bridge fires it before its mod writes the data, with the data before and after both filled in, and skips the
 * write if it is cancelled.
 */
@FiredBy(core = NONE,
        bridges = "changes lasting data it keeps on an entity that no other entity event describes (a colonist's job, "
                + "a familiar's upgrades, a player's spell anchor)",
        fillIn = "the kind of data, and the data before and after")
public class EntityDataChangeEvent extends EntityEvent implements Cancellable {
    private final ResourceLocation kind;
    private final CompoundTag before;
    private final CompoundTag after;
    private boolean cancelled;

    /**
     * @param kind whose data, in the mod's namespace: {@code minecolonies:job}, {@code occultism:familiar_upgrades}
     * @param before the data as it is now, in the mod's format; an empty tag when there was none
     * @param after the data as it will be; an empty tag when it is being removed. An empty tag always means none, so
     *     data that is present but empty carries a key saying so
     */
    public EntityDataChangeEvent(Entity entity, ResourceLocation kind, CompoundTag before, CompoundTag after,
            Actor actor) {
        super(entity, actor);
        this.kind = Objects.requireNonNull(kind, "kind; use an id in your mod's namespace, such as minecolonies:job");
        if (kind.getNamespace().equals("hadron") || kind.getNamespace().equals("minecraft")) {
            throw new IllegalArgumentException(kind + " isn't a mod's id: the kind names whose data it is, in your "
                    + "mod's namespace, such as minecolonies:job");
        }
        this.before = Objects.requireNonNull(before, "before; pass an empty CompoundTag when there was none").copy();
        this.after = Objects.requireNonNull(after, "after; pass an empty CompoundTag when it is being removed").copy();
        if (before.equals(after)) {
            throw new IllegalArgumentException("before and after are the same, so nothing changes: fire "
                    + "EntityDataChangeEvent only when the mod's data does");
        }
    }

    /** The data kind: whose data this is, as an id in the mod's namespace. */
    public ResourceLocation getKind() {
        return kind;
    }

    /** A copy of the data before, in the mod's format. */
    public CompoundTag getBeforeData() {
        return before.copy();
    }

    /** A copy of the data after, in the mod's format. */
    public CompoundTag getAfterData() {
        return after.copy();
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
