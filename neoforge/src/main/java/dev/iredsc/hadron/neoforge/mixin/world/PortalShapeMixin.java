package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.event.world.PortalCreateEvent;
import dev.iredsc.hadron.neoforge.WorldHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A frame being filled with portal blocks: fire lighting it, or a mod. */
@Mixin(PortalShape.class)
public abstract class PortalShapeMixin {
    @Shadow @Final private LevelAccessor level;
    @Shadow @Final private Direction rightDir;
    @Shadow private BlockPos bottomLeft;
    @Shadow private int height;
    @Shadow @Final private int width;

    @Inject(method = "createPortalBlocks", at = @At("HEAD"), cancellable = true)
    private void hadron$create(CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || bottomLeft == null) return;
        BlockPos top = bottomLeft.relative(Direction.UP, height - 1).relative(rightDir, width - 1);
        if (WorldHooks.portalDenied(server, BoundingBox.fromCorners(bottomLeft, top),
                PortalCreateEvent.CreateReason.FIRE)) {
            ci.cancel();
        }
    }
}
