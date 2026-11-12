package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntityMoves;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A piston moves entities on each tick of its travel, after its block move was asked about: each entity it moves into
 * another block is asked about there, observed, with the piston's moving block as what moved it.
 */
@Mixin(PistonMovingBlockEntity.class)
abstract class PistonPushMixin {
    @WrapMethod(method = "moveCollidedEntities")
    private static void hadron$collided(Level level, BlockPos pos, float progress, PistonMovingBlockEntity piston,
            Operation<Void> original) {
        EntityMoves.pushing(pos, () -> original.call(level, pos, progress, piston));
    }

    @WrapMethod(method = "moveStuckEntities")
    private static void hadron$stuck(Level level, BlockPos pos, float progress, PistonMovingBlockEntity piston,
            Operation<Void> original) {
        EntityMoves.pushing(pos, () -> original.call(level, pos, progress, piston));
    }

    @WrapMethod(method = "moveEntityByPiston")
    private static void hadron$push(Direction noClip, Entity entity, double distance, Direction direction,
            Operation<Void> original) {
        EntityMoves.pushed(entity, () -> original.call(noClip, entity, distance, direction));
    }
}
