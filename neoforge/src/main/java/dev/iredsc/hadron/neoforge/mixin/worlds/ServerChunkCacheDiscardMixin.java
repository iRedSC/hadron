package dev.iredsc.hadron.neoforge.mixin.worlds;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.iredsc.hadron.neoforge.WorldsHooks;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Worlds.unload(level, false): closing the world doesn't write its chunks. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheDiscardMixin {
    @WrapWithCondition(method = "close",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;save(Z)V"))
    private boolean hadron$saveUnlessDiscarded(ServerChunkCache self, boolean flush) {
        return !WorldsHooks.discarding();
    }
}
