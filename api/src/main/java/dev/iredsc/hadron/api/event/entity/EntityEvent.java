package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Anything that happens to an entity. Listen for this to hear all of them, from every actor: a protection plugin
 * listening here covers damage, spawns, teleports, mounts, effects and every leaf added later.
 *
 * <p>{@link #getEntity()} is what it happens to; {@link #getActor()} is who did it. A creeper blowing up a player
 * fires an event whose entity is the player and whose actor is the creeper.
 */
public abstract class EntityEvent extends ActionEvent {
    private final Entity entity;
    private final ServerLevel level;
    private final Vec3 position;

    protected EntityEvent(Entity entity, Actor actor) {
        super(actor);
        this.entity = Objects.requireNonNull(entity, "entity");
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Entity events are server-side only: fire them for entities in a "
                    + "ServerLevel, never from client code (check !level.isClientSide() first)");
        }
        this.level = serverLevel;
        this.position = entity.position();
    }

    /** What this happens to. */
    public Entity getEntity() {
        return entity;
    }

    /** The level the entity was in when this fired. */
    public ServerLevel getLevel() {
        return level;
    }

    /** Where the entity was when this fired. */
    public Vec3 getPosition() {
        return position;
    }

    /** The block the entity was in when this fired. */
    public BlockPos getBlockPos() {
        return BlockPos.containing(position);
    }
}
