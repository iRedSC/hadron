package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * A restriction outside the effect registry is about to be put on or taken off an entity. Core fires it for a mob's
 * AI being turned off or on ({@link #NO_AI}); bridges fire it for their mods' stuns, freezes, holds and interrupted
 * casts, with their own status ids. Cancel it and the entity's status stays as it was.
 */
public class EntityStatusChangeEvent extends EntityEvent implements Cancellable {
    /** A mob's AI is turned off ({@code Mob.setNoAi}). */
    public static final ResourceLocation NO_AI = ResourceLocation.fromNamespaceAndPath("hadron", "no_ai");

    private final ResourceLocation status;
    private final boolean applied;
    private boolean cancelled;

    public EntityStatusChangeEvent(Entity entity, ResourceLocation status, boolean applied, Actor actor) {
        super(entity, actor);
        this.status = Objects.requireNonNull(status, "status; bridges use an id in their mod's namespace");
        this.applied = applied;
    }

    /** Which restriction, such as {@link #NO_AI} or a mod's {@code ironsspellbooks:stunned}. */
    public ResourceLocation getStatus() {
        return status;
    }

    /** True when the restriction is being put on, false when it is being taken off. */
    public boolean isApplied() {
        return applied;
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
