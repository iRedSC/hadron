package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceUnloadEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mods that keep chunks loaded with a permanent ticket type of their own, rather than through setChunkForced. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final public ServerLevel level;

    @Inject(method = "addRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;"
            + "ILjava/lang/Object;Z)V", at = @At("HEAD"), cancellable = true)
    private void hadron$ticket(TicketType<?> type, ChunkPos pos, int distance, Object value, boolean forceTicks,
            CallbackInfo ci) {
        if (!WorldHooks.isModForceTicket(type) || !Hooks.listens(ChunkForceLoadEvent.class)) return;
        if (WorldHooks.forceDenied(WorldActors.orUnknown(), level, pos, forceTicks)) ci.cancel();
    }

    @Inject(method = "removeRegionTicket(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;"
            + "ILjava/lang/Object;Z)V", at = @At("TAIL"))
    private void hadron$release(TicketType<?> type, ChunkPos pos, int distance, Object value, boolean forceTicks,
            CallbackInfo ci) {
        if (!WorldHooks.isModForceTicket(type) || !Hooks.listens(ChunkForceUnloadEvent.class)) return;
        WorldHooks.forceReleased(WorldActors.orUnknown(), level, pos);
    }
}
