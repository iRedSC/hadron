package dev.iredsc.hadron.api.event.server;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A claim, team, party, alliance or trust list is about to change: created, removed, its area, its members or its
 * flags. Cancel it and nothing changes.
 *
 * <p><b>Bridge event.</b> Vanilla has no claims, so Hadron core never fires this; the bridge for a claims or teams
 * mod (FTB Chunks, Open Parties and Claims, FTB Teams) fires it before the change, drops the change if cancelled, and
 * fills in the system, the group, what changes and which players it affects.
 */
public class AccessControlChangeEvent extends ServerEvent implements Cancellable {
    public enum Change {
        CREATED, REMOVED, AREA, MEMBERS, FLAGS
    }

    private final Actor actor;
    private final String system;
    private final String group;
    private final Change change;
    private final Set<UUID> affectedPlayers;
    private boolean cancelled;

    /**
     * @param system the mod that owns it, by mod id, such as {@code ftbchunks}
     * @param group the claim, team or party, by that mod's own id for it
     * @param affectedPlayers players added, removed or whose rights change; empty when none in particular
     */
    public AccessControlChangeEvent(Actor actor, String system, String group, Change change,
            Set<UUID> affectedPlayers) {
        this.actor = Objects.requireNonNull(actor, "actor; use Actor.Unknown.INSTANCE when nobody is known");
        this.system = Objects.requireNonNull(system, "system; pass the owning mod's id, such as \"ftbchunks\"");
        this.group = Objects.requireNonNull(group, "group");
        this.change = Objects.requireNonNull(change, "change");
        this.affectedPlayers = Set.copyOf(affectedPlayers);
    }

    /** Who is making the change. */
    public Actor getActor() {
        return actor;
    }

    /** The mod that owns the claim or team, by mod id. */
    public String getSystem() {
        return system;
    }

    /** Which claim, team or party, by the owning mod's id for it. */
    public String getGroup() {
        return group;
    }

    public Change getChange() {
        return change;
    }

    public Set<UUID> getAffectedPlayers() {
        return affectedPlayers;
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
