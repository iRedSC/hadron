package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceUnloadEvent;
import dev.iredsc.hadron.neoforge.HeldEffects;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather, time of day and /forceload, at ServerLevel's own setters; and every game event, held while a refusal is
 * still to come ({@link HeldEffects}).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Unique private static final byte TIME_OTHER = 0, TIME_TICK = 1, TIME_SLEEP = 2;

    @Shadow @Final private ServerLevelData serverLevelData;

    /** Why setDayTime is being called right now: the clock's tick (no event), sleeping, or anything else. */
    @Unique private byte hadron$timeCause;
    /** The natural cycle's thunder flag, held back until its rain flag says what players will see. */
    @Unique private boolean hadron$pendingThunder;

    @Unique
    private ServerLevel hadron$self() {
        return (ServerLevel) (Object) this;
    }

    // ---- Weather

    /** /weather, rituals and weather machines. */
    @Inject(method = "setWeatherParameters", at = @At("HEAD"), cancellable = true)
    private void hadron$setWeather(int clearTime, int weatherTime, boolean raining, boolean thundering,
            CallbackInfo ci) {
        if (WorldHooks.weatherDenied(hadron$self(), serverLevelData, raining, thundering, false)) ci.cancel();
    }

    /** Sleeping through the night clears the weather. */
    @Inject(method = "resetWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void hadron$resetWeather(CallbackInfo ci) {
        if (WorldHooks.weatherDenied(hadron$self(), serverLevelData, false, false, true)) ci.cancel();
    }

    /** The natural cycle sets thunder, then rain; hold thunder back until both are known. */
    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/ServerLevelData;setThundering(Z)V"))
    private boolean hadron$cycleThunder(ServerLevelData data, boolean thundering) {
        hadron$pendingThunder = thundering;
        return false;
    }

    @WrapWithCondition(method = "advanceWeatherCycle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/ServerLevelData;setRaining(Z)V"))
    private boolean hadron$cycleRain(ServerLevelData data, boolean raining) {
        if (WorldHooks.weatherDenied(hadron$self(), data, raining, hadron$pendingThunder, true)) return false;
        data.setThundering(hadron$pendingThunder);
        return true;
    }

    // ---- Time

    @Inject(method = "setDayTime", at = @At("HEAD"), cancellable = true)
    private void hadron$setDayTime(long time, CallbackInfo ci) {
        if (hadron$timeCause == TIME_TICK) return;
        long wanted = WorldHooks.dayTime(hadron$self(), serverLevelData, time, hadron$timeCause == TIME_SLEEP);
        if (wanted == time) return;
        ci.cancel();
        serverLevelData.setDayTime(wanted);
    }

    @Inject(method = "tickTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private void hadron$clockStart(CallbackInfo ci) {
        hadron$timeCause = TIME_TICK;
    }

    @Inject(method = "tickTime", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V", shift = At.Shift.AFTER))
    private void hadron$clockEnd(CallbackInfo ci) {
        hadron$timeCause = TIME_OTHER;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private void hadron$sleepStart(CallbackInfo ci) {
        hadron$timeCause = TIME_SLEEP;
    }

    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V", shift = At.Shift.AFTER))
    private void hadron$sleepEnd(CallbackInfo ci) {
        hadron$timeCause = TIME_OTHER;
    }

    // ---- /forceload and mods using setChunkForced

    @Inject(method = "setChunkForced", at = @At("HEAD"), cancellable = true)
    private void hadron$force(int x, int z, boolean add, CallbackInfoReturnable<Boolean> cir) {
        if (!add || !Hooks.listens(ChunkForceLoadEvent.class)) return;
        if (hadron$self().getForcedChunks().contains(ChunkPos.asLong(x, z))) return;
        if (WorldHooks.forceDenied(WorldActors.orUnknown(), hadron$self(), new ChunkPos(x, z), true)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "setChunkForced", at = @At("RETURN"))
    private void hadron$unforce(int x, int z, boolean add, CallbackInfoReturnable<Boolean> cir) {
        if (add || !cir.getReturnValue() || !Hooks.listens(ChunkForceUnloadEvent.class)) return;
        WorldHooks.forceReleased(WorldActors.orUnknown(), hadron$self(), new ChunkPos(x, z));
    }

    // ---- Game events

    /**
     * Every game event goes through here before NeoForge's event and the listeners hear it. Inside a hold it waits
     * whole, and comes back through here when kept; otherwise it goes on as before, past one check.
     */
    @WrapOperation(method = "gameEvent(Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/CommonHooks;onVanillaGameEvent(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/gameevent/GameEvent$Context;)Z"))
    private boolean hadron$holdGameEvent(Level level, Holder<GameEvent> event, Vec3 pos, GameEvent.Context context,
            Operation<Boolean> original) {
        if (!HeldEffects.holding()) return original.call(level, event, pos, context);
        ServerLevel self = hadron$self();
        HeldEffects.hold(() -> self.gameEvent(event, pos, context));
        return false;
    }
}
