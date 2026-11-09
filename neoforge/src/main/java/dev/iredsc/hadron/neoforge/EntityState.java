package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.world.level.Level;

/**
 * What the entity mixin adds to every {@code Entity}, so hot paths keep their state in fields instead of maps. Cast
 * any entity to it; never implement it yourself.
 */
public interface EntityState {
    /** The cached actor for this entity, or null until first asked or after its attribution changed. */
    @Nullable Actor hadron$actor();

    void hadron$setActor(@Nullable Actor actor);

    /** Where the entity was last seen by the move check; only meaningful while {@link #hadron$tracked()}. */
    boolean hadron$tracked();

    @Nullable Level hadron$trackedLevel();

    double hadron$trackedX();

    double hadron$trackedY();

    double hadron$trackedZ();

    void hadron$track(Level level, double x, double y, double z);

    void hadron$untrack();

    /** Set on the new entity of a conversion, so its arrival isn't a spawn. */
    boolean hadron$converted();

    void hadron$setConverted(boolean converted);

    /** True while a living entity's death loot is being decided and a death listener is waiting for it. */
    boolean hadron$dying();

    void hadron$setDying(boolean dying);

    /**
     * Set on a player whose click a plugin just refused (a lead put on or taken off, every tie to a fence), so the click
     * ends there, failed: not counted toward advancements, and not passed on to the held item. Read only by the click
     * handlers that clear it just before the call, so a mark left by a direct call can't fail a later one.
     */
    boolean hadron$interactRefused();

    void hadron$setInteractRefused(boolean refused);

    /** Experience held back from the vanilla drop until the death event decides it. */
    int hadron$deathExp();

    void hadron$setDeathExp(int exp);
}
