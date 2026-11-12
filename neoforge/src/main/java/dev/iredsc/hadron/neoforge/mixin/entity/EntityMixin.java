package dev.iredsc.hadron.neoforge.mixin.entity;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityCombustEvent;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.EntityState;
import dev.iredsc.hadron.neoforge.LeashDrop;
import dev.iredsc.hadron.neoforge.EntityMoves;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import dev.iredsc.hadron.neoforge.Hooks;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per-entity state in fields, and the teleport, fire and air chokepoints every entity shares. */
@Mixin(Entity.class)
abstract class EntityMixin implements EntityState {
    @Shadow
    public abstract Level level();

    @Shadow
    public abstract int getRemainingFireTicks();

    @Shadow
    public abstract boolean fireImmune();

    @Shadow
    public abstract int getAirSupply();

    @Unique private @Nullable Actor hadron$actor;
    @Unique private boolean hadron$tracked;
    @Unique private @Nullable Level hadron$trackedLevel;
    @Unique private double hadron$trackedX;
    @Unique private double hadron$trackedY;
    @Unique private double hadron$trackedZ;
    @Unique private float hadron$trackedYRot;
    @Unique private float hadron$trackedXRot;
    @Unique private @Nullable Pose hadron$trackedPose;
    @Unique private long hadron$trackedPoseTick;
    @Unique private long hadron$trackedAt;
    @Unique private int hadron$seatOf;
    @Unique private int hadron$heldBy;
    @Unique private boolean hadron$watched;
    @Unique private double hadron$beforeX;
    @Unique private double hadron$beforeY;
    @Unique private double hadron$beforeZ;
    @Unique private long hadron$beforeAt = -1;
    @Unique private double hadron$seatX;
    @Unique private double hadron$seatY;
    @Unique private double hadron$seatZ;
    @Unique private boolean hadron$converted;
    @Unique private boolean hadron$dying;
    @Unique private LeashDrop hadron$leashDrop = LeashDrop.UNSAID;
    @Unique private int hadron$deathExp;

    @Override
    public @Nullable Actor hadron$actor() {
        return hadron$actor;
    }

    @Override
    public void hadron$setActor(@Nullable Actor actor) {
        hadron$actor = actor;
    }

    @Override
    public boolean hadron$tracked() {
        return hadron$tracked;
    }

    @Override
    public @Nullable Level hadron$trackedLevel() {
        return hadron$trackedLevel;
    }

    @Override
    public double hadron$trackedX() {
        return hadron$trackedX;
    }

    @Override
    public double hadron$trackedY() {
        return hadron$trackedY;
    }

    @Override
    public double hadron$trackedZ() {
        return hadron$trackedZ;
    }

    @Override
    public void hadron$track(Level level, double x, double y, double z) {
        hadron$tracked = true;
        hadron$trackedLevel = level;
        hadron$trackedX = x;
        hadron$trackedY = y;
        hadron$trackedZ = z;
    }

    @Override
    public void hadron$untrack() {
        hadron$tracked = false;
        hadron$trackedLevel = null;
    }

    @Override
    public float hadron$trackedYRot() {
        return hadron$trackedYRot;
    }

    @Override
    public float hadron$trackedXRot() {
        return hadron$trackedXRot;
    }

    @Override
    public @Nullable Pose hadron$trackedPose() {
        return hadron$trackedPose;
    }

    @Override
    public long hadron$trackedPoseTick() {
        return hadron$trackedPoseTick;
    }

    @Override
    public long hadron$trackedAt() {
        return hadron$trackedAt;
    }

    @Override
    public void hadron$trackTurn(float yRot, float xRot, Pose pose, long poseTick, long at) {
        hadron$trackedYRot = yRot;
        hadron$trackedXRot = xRot;
        hadron$trackedPose = pose;
        hadron$trackedPoseTick = poseTick;
        hadron$trackedAt = at;
    }

    @Override
    public int hadron$seatOf() {
        return hadron$seatOf;
    }

    @Override
    public double hadron$seatX() {
        return hadron$seatX;
    }

    @Override
    public double hadron$seatY() {
        return hadron$seatY;
    }

    @Override
    public double hadron$seatZ() {
        return hadron$seatZ;
    }

    @Override
    public void hadron$seat(int vehicle, double x, double y, double z) {
        hadron$seatOf = vehicle;
        hadron$seatX = x;
        hadron$seatY = y;
        hadron$seatZ = z;
    }

    @Override
    public double hadron$beforeX() {
        return hadron$beforeX;
    }

    @Override
    public double hadron$beforeY() {
        return hadron$beforeY;
    }

    @Override
    public double hadron$beforeZ() {
        return hadron$beforeZ;
    }

    @Override
    public long hadron$beforeAt() {
        return hadron$beforeAt;
    }

    @Override
    public void hadron$before(double x, double y, double z, long at) {
        hadron$beforeX = x;
        hadron$beforeY = y;
        hadron$beforeZ = z;
        hadron$beforeAt = at;
    }

    @Override
    public boolean hadron$watched() {
        return hadron$watched;
    }

    @Override
    public void hadron$setWatched(boolean watched) {
        hadron$watched = watched;
    }

    @Override
    public int hadron$heldBy() {
        return hadron$heldBy;
    }

    @Override
    public void hadron$holdBy(int vehicle) {
        hadron$heldBy = vehicle;
    }

    /**
     * A rider a refused move held in place stays there: its own tick seats it again through here, which would carry it
     * back into the refused block (a rearing horse's seat moves with the animation, not with anything put back). Any
     * other seating is checked: one in another block than the last move check saw is asked about again.
     */
    @WrapMethod(method = "positionRider(Lnet/minecraft/world/entity/Entity;)V")
    private void hadron$positionRider(Entity rider, Operation<Void> original) {
        if (((EntityState) rider).hadron$heldBy() != 0
                && ((EntityState) rider).hadron$heldBy() == ((Entity) (Object) this).getId()) {
            return;
        }
        EntityState seat = (EntityState) rider;
        if (seat.hadron$seatOf() == 0) { // no move check watches this rider: nothing more to do
            original.call(rider);
            return;
        }
        seat.hadron$before(rider.getX(), rider.getY(), rider.getZ(), rider.level().getGameTime());
        original.call(rider);
        EntityMoves.seated((Entity) (Object) this, rider);
    }

    @Override
    public boolean hadron$converted() {
        return hadron$converted;
    }

    @Override
    public void hadron$setConverted(boolean converted) {
        hadron$converted = converted;
    }

    @Override
    public boolean hadron$dying() {
        return hadron$dying;
    }

    @Override
    public void hadron$setDying(boolean dying) {
        hadron$dying = dying;
    }

    @Override
    public LeashDrop hadron$leashDrop() {
        return hadron$leashDrop;
    }

    @Override
    public void hadron$setLeashDrop(LeashDrop cause) {
        hadron$leashDrop = cause;
    }

    @Override
    public int hadron$deathExp() {
        return hadron$deathExp;
    }

    @Override
    public void hadron$setDeathExp(int exp) {
        hadron$deathExp = exp;
    }

    // Teleports. Entity's own methods; ServerPlayer's overrides are in ServerPlayerMixin.

    @WrapMethod(method = "teleportTo(DDD)V")
    private void hadron$teleport(double x, double y, double z, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        EntityTeleports.Destination to = EntityTeleports.ask(self, null, x, y, z, self.getYRot(), self.getXRot());
        if (to == null) return;
        if (to.differs(self, null, x, y, z, self.getYRot(), self.getXRot())) {
            EntityTeleports.redirect(self, to); // another level or facing needs the method that takes them
            return;
        }
        EntityTeleports.during(self, () -> original.call(x, y, z));
    }

    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean hadron$teleportTo(ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative,
            float yRot, float xRot, Operation<Boolean> original) {
        Entity self = (Entity) (Object) this;
        // Vanilla's method ejects riders before changing world, so they stay unless a listener sends it elsewhere.
        EntityTeleports.Destination to = EntityTeleports.ask(self, level, x, y, z, yRot, xRot,
                dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.Riders.LEFT_BEHIND);
        if (to == null) return false;
        // Sent to another level than asked: this method would leave the passengers behind, so it isn't used.
        if (to.level() != level && to.crossesLevel(self)) return EntityTeleports.redirect(self, to);
        // Relative flags only shape the packet; the coordinates passed are absolute, so a new destination keeps them.
        return EntityTeleports.during(self, () -> original.call(to.level(), to.x(), to.y(), to.z(), relative,
                to.yRot(), to.xRot()));
    }

    @WrapMethod(method = "changeDimension")
    private @Nullable Entity hadron$changeDimension(DimensionTransition transition, Operation<Entity> original) {
        Entity self = (Entity) (Object) this;
        var pos = transition.pos();
        EntityTeleports.Destination to = EntityTeleports.ask(self, transition.newLevel(), pos.x, pos.y, pos.z,
                transition.yRot(), transition.xRot());
        if (to == null) return null;
        DimensionTransition going = to.differs(self, transition.newLevel(), pos.x, pos.y, pos.z, transition.yRot(),
                transition.xRot())
                ? new DimensionTransition(to.level(), to.position(), transition.speed(), to.yRot(), to.xRot(),
                        transition.missingRespawnBlock(), transition.postDimensionTransition())
                : transition;
        return EntityTeleports.during(self, () -> original.call(going));
    }

    /** Getting off a vehicle places the rider with a teleport; that is part of the dismount, not a teleport. */
    @WrapMethod(method = "dismountTo")
    private void hadron$dismountTo(double x, double y, double z, Operation<Void> original) {
        EntityTeleports.during((Entity) (Object) this, () -> original.call(x, y, z));
    }

    @Inject(method = "handlePortal", at = @At("HEAD"))
    private void hadron$portal(CallbackInfo ci) {
        EntityTeleports.portal((Entity) (Object) this);
    }

    // Catching fire: only when it isn't already burning, so standing in fire is one event, not one a tick.
    @WrapMethod(method = "igniteForTicks")
    private void hadron$ignite(int ticks, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!(level() instanceof ServerLevel) || getRemainingFireTicks() > 0 || ticks <= getRemainingFireTicks()
                || fireImmune() || !Hooks.listens(EntityCombustEvent.class)) {
            original.call(ticks);
            return;
        }
        EntityCombustEvent event = Hooks.post(new EntityCombustEvent(self, ticks,
                EntityActors.fromStack(self, Actor.Nature.INSTANCE)));
        if (!event.isCancelled()) original.call(event.getDuration());
    }

    @WrapMethod(method = "setAirSupply")
    private void hadron$air(int air, Operation<Void> original) {
        Entity self = (Entity) (Object) this;
        if (!(level() instanceof ServerLevel) || !self.isAddedToLevel() || air == getAirSupply()
                || !Hooks.listens(EntityAirChangeEvent.class)) {
            original.call(air);
            return;
        }
        EntityAirChangeEvent event = Hooks.post(new EntityAirChangeEvent(self, getAirSupply(), air,
                EntityActors.fromStack(self, Actor.Nature.INSTANCE)));
        if (!event.isCancelled()) original.call(event.getAmount());
    }
}
