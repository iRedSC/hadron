package dev.iredsc.hadron.neoforge.mixin.worlds;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.iredsc.hadron.neoforge.WorldsHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A created world generates from its own seed: terrain and structure placement (ChunkMap reads getSeed while the
 * level is built), structure checks and random sequences (the constructor reads the server's seed directly).
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeedMixin {
    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void hadron$createdWorldSeed(CallbackInfoReturnable<Long> cir) {
        Long seed = WorldsHooks.seed(((ServerLevel) (Object) this).dimension());
        if (seed != null) cir.setReturnValue(seed);
    }

    @ModifyExpressionValue(method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;seed()J"))
    private long hadron$createdWorldSeedInConstructor(long serverSeed) {
        Long seed = WorldsHooks.seed(((ServerLevel) (Object) this).dimension());
        return seed != null ? seed : serverSeed;
    }
}
