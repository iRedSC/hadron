package dev.iredsc.hadron.neoforge.mixin.entity;

import org.jspecify.annotations.Nullable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.EntityActors;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@code EntityType.spawn}, used by spawn eggs, buckets of fish and armor stands. The player using the item is the
 * actor; a spawn the event refused returns null, so callers that check (spawn eggs) keep their item.
 */
@Mixin(EntityType.class)
abstract class EntityTypeMixin<T extends Entity> {
    @WrapMethod(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/MobSpawnType;ZZ)Lnet/minecraft/world/entity/Entity;")
    private T hadron$spawnByPlayer(ServerLevel level, ItemStack stack, Player player, BlockPos pos,
            MobSpawnType type, boolean offsetY, boolean offsetYMore, Operation<T> original) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return original.call(level, stack, player, pos, type, offsetY, offsetYMore);
        }
        try (Actors.Scope scope = Actors.push(EntityActors.of(serverPlayer))) {
            return original.call(level, stack, player, pos, type, offsetY, offsetYMore);
        }
    }

    @WrapMethod(method = "spawn(Lnet/minecraft/server/level/ServerLevel;Ljava/util/function/Consumer;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/MobSpawnType;ZZ)"
            + "Lnet/minecraft/world/entity/Entity;")
    private @Nullable T hadron$spawn(ServerLevel level, Consumer<T> configure, BlockPos pos, MobSpawnType type,
            boolean offsetY, boolean offsetYMore, Operation<T> original) {
        T entity = original.call(level, configure, pos, type, offsetY, offsetYMore);
        return entity != null && !entity.isAddedToLevel() ? null : entity;
    }
}
