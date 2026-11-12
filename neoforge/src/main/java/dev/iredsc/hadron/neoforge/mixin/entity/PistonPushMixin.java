package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.EntityMoves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A piston moves entities on each tick of its travel, after its block move was asked about: each entity it would move
 * into another block is asked about before it is moved, with the piston's moving block as what moves it; a slime
 * block's launch waits on that answer.
 */
@Mixin(PistonMovingBlockEntity.class)
abstract class PistonPushMixin {
    /** The piston direction vanilla's collision ignores moving blocks along, while it pushes an entity. */
    @Shadow
    @Final
    private static ThreadLocal<Direction> NOCLIP;

    @WrapMethod(method = "moveCollidedEntities")
    private static void hadron$collided(Level level, BlockPos pos, float progress, PistonMovingBlockEntity piston,
            Operation<Void> original) {
        if (level.isClientSide() || !EntityMoves.watchesPushes()) {
            original.call(level, pos, progress, piston);
            return;
        }
        EntityMoves.pushing(pos, () -> original.call(level, pos, progress, piston));
    }

    @WrapMethod(method = "moveStuckEntities")
    private static void hadron$stuck(Level level, BlockPos pos, float progress, PistonMovingBlockEntity piston,
            Operation<Void> original) {
        if (level.isClientSide() || !EntityMoves.watchesPushes()) {
            original.call(level, pos, progress, piston);
            return;
        }
        EntityMoves.pushing(pos, () -> original.call(level, pos, progress, piston));
    }

    @WrapMethod(method = "moveEntityByPiston")
    private static void hadron$push(Direction noClip, Entity entity, double distance, Direction direction,
            Operation<Void> original) {
        if (entity.level().isClientSide() || !EntityMoves.checksPush(entity)) {
            original.call(noClip, entity, distance, direction);
            return;
        }
        // Where vanilla's move will really take it: the same limit and collision, under the same no-clip direction.
        Vec3 delta = new Vec3(distance * direction.getStepX(), distance * direction.getStepY(),
                distance * direction.getStepZ());
        EntityMotionAccessor motion = (EntityMotionAccessor) entity;
        Vec3 resolved;
        NOCLIP.set(noClip);
        try {
            resolved = EntityMoves.resolve(entity, delta, motion::hadron$collide, motion.hadron$pistonDeltas(),
                    motion.hadron$pistonDeltasGameTime(), motion.hadron$stuckSpeedMultiplier());
        } finally {
            NOCLIP.set(null);
        }
        EntityMoves.pushed(entity, resolved, () -> original.call(noClip, entity, distance, direction));
    }

    /** A slime block's launch is held until the push that follows it is asked about. */
    @WrapOperation(method = "moveCollidedEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private static void hadron$launch(Entity entity, double x, double y, double z, Operation<Void> original) {
        EntityMoves.launching(entity, () -> original.call(entity, x, y, z));
    }
}
