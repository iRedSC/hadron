package dev.iredsc.hadron.neoforge.mixin.world;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.PlayerState;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import java.util.Optional;
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

    @Unique private Actor.@Nullable Player hadron$actor;
    @Unique private boolean hadron$synced;
    @Unique private boolean hadron$mayfly;
    @Unique private float hadron$flySpeed;
    @Unique private float hadron$walkSpeed;
    @Unique private int hadron$level = Integer.MIN_VALUE;
    /** The level marked as vanilla working for the player's own tick, while it runs; null otherwise. */
    @Unique private @Nullable ServerLevel hadron$tickLevel;

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

    /**
     * A player's own tick runs from their connection with nobody on the stack. What it changes (frosted ice under
     * Frost Walker, an End gateway's exit) is vanilla's own work, silent in a watched area as it is everywhere else.
     */
    @WrapMethod(method = "doTick")
    private void hadron$tickAsVanilla(Operation<Void> original) {
        ServerLevel level = hadron$tickLevel = hadron$self().serverLevel();
        AreaIndex.enterVanillaWork(level);
        try {
            original.call();
        } finally {
            AreaIndex.exitVanillaWork(java.util.Objects.requireNonNullElse(hadron$tickLevel, level));
            hadron$tickLevel = null;
        }
    }

    /**
     * A menu closing: a barrel or trapped chest settling back closed. With nobody on the stack (the player leaving the
     * game) it is vanilla's own work, silent in a watched area.
     */
    @WrapOperation(method = "doCloseContainer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;removed(Lnet/minecraft/world/entity/player/Player;)V"))
    private void hadron$closeAsVanilla(AbstractContainerMenu menu, Player player, Operation<Void> original) {
        ServerLevel level = hadron$self().serverLevel();
        AreaIndex.enterVanillaWork(level);
        try {
            original.call(menu, player);
        } finally {
            AreaIndex.exitVanillaWork(level);
        }
    }

    /**
     * A respawn anchor's charge used up, in the world being respawned or returned to (leaving the End, from the
     * player's tick in the End): vanilla's own work there.
     */
    @WrapMethod(method = "findRespawnAndUseSpawnBlock")
    private static Optional<?> hadron$respawnAsVanilla(ServerLevel level, BlockPos pos, float angle, boolean forced,
            boolean keepInventory, Operation<Optional<?>> original) {
        AreaIndex.enterVanillaWork(level);
        try {
            return original.call(level, pos, angle, forced, keepInventory);
        } finally {
            AreaIndex.exitVanillaWork(level);
        }
    }

    /** A dimension change mid-tick: the rest of the tick (Frost Walker on arrival) is vanilla's in the new level. */
    @Inject(method = "setServerLevel", at = @At("TAIL"))
    private void hadron$tickMoves(ServerLevel level, CallbackInfo ci) {
        ServerLevel from = hadron$tickLevel;
        if (from == null || from == level) return;
        AreaIndex.exitVanillaWork(from);
        AreaIndex.enterVanillaWork(level);
        hadron$tickLevel = level;
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
