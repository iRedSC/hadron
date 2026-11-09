package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** The dragon fight placing gateways, the exit portal and the respawn pillars: vanilla's own. */
@Mixin(EndDragonFight.class)
public abstract class EndDragonFightMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @WrapMethod(method = "tick")
    private void hadron$fight(Operation<Void> original) {
        AreaIndex.enterVanillaWork(level);
        try {
            original.call();
        } finally {
            AreaIndex.exitVanillaWork(level);
        }
    }
}
