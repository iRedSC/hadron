package dev.iredsc.hadron.api.event.entity;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventStack;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import static dev.iredsc.hadron.api.event.FiredBy.Core.PARTIAL;

/**
 * An entity is about to be sheared: a sheep's wool, a mooshroom's mushrooms, a snow golem's pumpkin, a bogged's
 * mushrooms, or a mod's shearable mob. Bukkit's {@code PlayerShearEntityEvent}. Fired only when the shear would
 * work, so a sheared sheep fires nothing. Cancel it and nothing is cut, nothing drops and the shears take no wear.
 *
 * <p><b>Core fires it for shears used by hand or from a dispenser</b>, including a mod's shears built on vanilla's.
 * A mod that shears in its own code (an auto-shearer) needs its bridge to fire this.
 */
@FiredBy(core = PARTIAL,
        coreSees = "shears used by hand or by a dispenser, on any mob NeoForge can shear",
        bridges = "the mod shearing in its own code (an auto-shearer, its own shears)")
public class EntityShearEvent extends EntityEvent implements Cancellable {
    private final EventStack.Copy tool;
    private boolean cancelled;

    public EntityShearEvent(Entity entity, ItemStack tool, Actor actor) {
        super(entity, actor);
        this.tool = EventStack.copyOf(tool, "tool");
    }

    /** The shears. A copy. */
    public ItemStack getItem() {
        return tool.get();
    }

    /**
     * The player behind it, if online: directly, or the owner of the fake player or machine that did. Same as
     * {@link #getActingPlayer()}.
     */
    public Optional<ServerPlayer> getPlayer() {
        return getActingPlayer();
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
