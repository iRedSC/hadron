package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.neoforge.PlayerState;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Abilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Abilities and level changes, measured from what the client was last told, and the player's one actor. Abilities
 * reach the client only through onUpdateAbilities, so every mod's flight grant passes through it.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements PlayerState {
    @Shadow public ServerGamePacketListenerImpl connection;

    @Unique private Actor.Player hadron$actor;
    @Unique private boolean hadron$synced;
    @Unique private boolean hadron$mayfly;
    @Unique private float hadron$flySpeed;
    @Unique private float hadron$walkSpeed;
    @Unique private int hadron$level = Integer.MIN_VALUE;

    @Unique
    private ServerPlayer hadron$self() {
        return (ServerPlayer) (Object) this;
    }

    @Override
    public Actor.Player hadron$actor() {
        Actor.Player actor = hadron$actor;
        if (actor == null) hadron$actor = actor = new Actor.Player(hadron$self());
        return actor;
    }

    @Override
    public void hadron$syncAbilities() {
        Abilities a = hadron$self().getAbilities();
        hadron$synced = true;
        hadron$mayfly = a.mayfly;
        hadron$flySpeed = a.getFlyingSpeed();
        hadron$walkSpeed = a.getWalkingSpeed();
        hadron$level = hadron$self().experienceLevel;
    }

    @Inject(method = "onUpdateAbilities", at = @At("HEAD"))
    private void hadron$abilities(CallbackInfo ci) {
        if (!hadron$synced || connection == null) return;
        Abilities a = hadron$self().getAbilities();
        if (a.mayfly == hadron$mayfly && a.getFlyingSpeed() == hadron$flySpeed
                && a.getWalkingSpeed() == hadron$walkSpeed) {
            return;
        }
        WorldHooks.abilities(hadron$self(), hadron$mayfly, hadron$flySpeed, hadron$walkSpeed);
        hadron$mayfly = a.mayfly;
        hadron$flySpeed = a.getFlyingSpeed();
        hadron$walkSpeed = a.getWalkingSpeed();
    }

    /** A respawned player is a new object; carry over what the client knows so the change is measured from it. */
    @Inject(method = "restoreFrom", at = @At("HEAD"))
    private void hadron$inherit(ServerPlayer that, boolean keepEverything, CallbackInfo ci) {
        ServerPlayerMixin old = (ServerPlayerMixin) (Object) that;
        hadron$synced = old.hadron$synced;
        hadron$mayfly = old.hadron$mayfly;
        hadron$flySpeed = old.hadron$flySpeed;
        hadron$walkSpeed = old.hadron$walkSpeed;
        hadron$level = old.hadron$level;
    }

    @Inject(method = "doTick", at = @At("HEAD"))
    private void hadron$levelTick(CallbackInfo ci) {
        int now = hadron$self().experienceLevel;
        int old = hadron$level;
        if (now == old) return;
        hadron$level = now;
        if (old != Integer.MIN_VALUE) WorldHooks.levelChanged(hadron$self(), old, now);
    }
}
