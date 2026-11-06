package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.BlockHooks;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;

/**
 * An explosion's blocks are reported by the world family's {@code ExplosionEvent}, which lists them all; the block
 * breaks it makes are not reported again, one by one, as block breaks.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @WrapMethod(method = "finalizeExplosion")
    private void hadron$explodeOnce(boolean spawnParticles, Operation<Void> original) {
        BlockHooks.suppress();
        try {
            original.call(spawnParticles);
        } finally {
            BlockHooks.unsuppress();
        }
    }
}
