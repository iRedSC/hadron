package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.AreaIndex;
import dev.iredsc.hadron.neoforge.BlockHooks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
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

    /**
     * Turning the exit portal back to end stone for a respawn. Vanilla repeats it until the portal is gone, so it must
     * never be refused from under it, whoever started the respawn.
     */
    @WrapMethod(method = "respawnDragon")
    private void hadron$respawn(List<EndCrystal> crystals, Operation<Void> original) {
        BlockHooks.suppress();
        try {
            original.call(crystals);
        } finally {
            BlockHooks.unsuppress();
        }
    }

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
