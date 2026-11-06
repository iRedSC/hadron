package dev.iredsc.hadron.api.event;

import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Operation;
import dev.iredsc.hadron.api.plugin.Server;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Something done to the world, an entity, an item, an inventory or a resource, by an {@link Actor}. Every block,
 * entity, item, inventory, resource and world event is one, so a plugin can always ask who did it, whether the actor
 * was a player, a machine, a mob or nature.
 */
public abstract class ActionEvent extends Event {
    private final Actor actor;
    private final Operation operation;

    protected ActionEvent(Actor actor) {
        this.actor = Objects.requireNonNull(actor, "actor; use Actor.Unknown.INSTANCE when nobody is known");
        Operation open = Operation.current();
        this.operation = open != null ? open : Operation.single(actor);
    }

    /** Who did it. Never null: {@link Actor.Nature} or {@link Actor.Unknown} when no one did. */
    public Actor getActor() {
        return actor;
    }

    /** The action this is part of, shared by every event it caused. */
    public Operation getOperation() {
        return operation;
    }

    /**
     * The player acting for it, if online: the player themselves, or the controller, otherwise the owner, of a
     * machine, fake player or entity. Empty for nature, unknown actors and ownerless machines, so code that assumes a
     * player was there has to handle the case where none was.
     */
    public Optional<ServerPlayer> getActingPlayer() {
        if (actor instanceof Actor.Player p) return Optional.of(p.player());
        Optional<UUID> id = actor.actingFor();
        Server server = Hadron.Lifecycle.running();
        if (id.isEmpty() || server == null) return Optional.empty();
        return Optional.ofNullable(server.getPlayer(id.get()));
    }
}
