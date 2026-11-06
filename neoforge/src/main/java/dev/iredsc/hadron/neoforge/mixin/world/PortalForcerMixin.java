package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.event.world.PortalCreateEvent;
import dev.iredsc.hadron.neoforge.WorldHooks;
import java.util.Optional;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The exit portal built when someone reaches a world with none nearby. Refusing it makes vanilla report that no
 * portal could be made, so the traveller stays where they are.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
    /** How far from the arrival point vanilla looks for a spot. */
    @Unique private static final int SEARCH = 16;

    @Shadow @Final protected ServerLevel level;

    @Inject(method = "createPortal", at = @At("HEAD"), cancellable = true)
    private void hadron$create(BlockPos pos, Direction.Axis axis,
            CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        BoundingBox area = new BoundingBox(pos.getX() - SEARCH, level.getMinBuildHeight(), pos.getZ() - SEARCH,
                pos.getX() + SEARCH, level.getMaxBuildHeight() - 1, pos.getZ() + SEARCH);
        if (WorldHooks.portalDenied(level, area, PortalCreateEvent.CreateReason.NETHER_PAIR)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
