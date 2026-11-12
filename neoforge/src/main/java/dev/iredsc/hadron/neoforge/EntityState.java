package dev.iredsc.hadron.neoforge;

import org.jspecify.annotations.Nullable;
import dev.iredsc.hadron.api.actor.Actor;
import net.minecraft.world.entity.Pose;
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

    /** The facing and pose it had when the move check last saw it, to put back with its position. */
    float hadron$trackedYRot();

    float hadron$trackedXRot();

    @Nullable Pose hadron$trackedPose();

    /**
     * A camel's pose clock ({@code Camel.LAST_POSE_CHANGE_TICK}) when the move check last saw it, and the game time it
     * was read: its seat height moves with that clock while it sits down or stands up. 0 for anything else.
     */
    long hadron$trackedPoseTick();

    long hadron$trackedAt();

    void hadron$trackTurn(float yRot, float xRot, Pose pose, long poseTick, long at);

    /**
     * The game time the move check snapshotted this root vehicle's riders, at the start of its tick: its check after
     * the tick runs only when that is the current tick. -1 when never.
     */
    long hadron$checkAt();

    void hadron$setCheckAt(long at);

    /**
     * A rider's place in its root vehicle's snapshot: the root's id, the game time it was taken, and where and how it
     * faced then. 0 for the vehicle when no snapshot holds it. An id, so a rider never keeps a vehicle alive.
     */
    int hadron$seatOf();

    long hadron$seatAt();

    double hadron$seatX();

    double hadron$seatY();

    double hadron$seatZ();

    float hadron$seatYRot();

    float hadron$seatXRot();

    void hadron$seat(int vehicle, long at, double x, double y, double z, float yRot, float xRot);

    /** Set on the new entity of a conversion, so its arrival isn't a spawn. */
    boolean hadron$converted();

    void hadron$setConverted(boolean converted);

    /** True while a living entity's death loot is being decided and a death listener is waiting for it. */
    boolean hadron$dying();

    void hadron$setDying(boolean dying);

    /** Why the lead is coming off, while a caller that knows runs {@code dropLeash}; {@link LeashDrop#UNSAID} otherwise. */
    LeashDrop hadron$leashDrop();

    void hadron$setLeashDrop(LeashDrop cause);

    /** Experience held back from the vanilla drop until the death event decides it. */
    int hadron$deathExp();

    void hadron$setDeathExp(int exp);
}
