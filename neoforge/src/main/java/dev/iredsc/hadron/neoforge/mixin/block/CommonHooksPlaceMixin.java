package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.HeldEffects;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A cancelled placement keeps the item as it was, leaves no side effect, and an allowed one keeps what the use did.
 *
 * <p>NeoForge reads the stack's component map before the use and again after it, but both are the one live map, so
 * putting the "before" back for the event also rewrote the "after": a cancel kept the lost durability. Both reads
 * are now copies.
 *
 * <p>NeoForge asks about a placement only after the item placed its blocks (or stripped the log), then puts them back
 * on a cancel; by then vanilla had already met the {@code placed_block} criterion and emitted the place's vibration,
 * and putting a door back emits one more. The whole call runs in a {@link HeldEffects} hold, dropped when the event
 * was cancelled.
 */
@Mixin(value = CommonHooks.class, remap = false)
abstract class CommonHooksPlaceMixin {
    @ModifyExpressionValue(method = "onPlaceItemIntoWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getComponents()Lnet/minecraft/core/component/DataComponentMap;"))
    private static DataComponentMap hadron$snapshot(DataComponentMap live) {
        // The map itself, copied: a local would be ambiguous here, since NeoForge also keeps a pre-use copy stack.
        return live instanceof PatchedDataComponentMap patched ? patched.copy() : live;
    }

    @WrapMethod(method = "onPlaceItemIntoWorld")
    private static InteractionResult hadron$holdEffects(UseOnContext context, Operation<InteractionResult> original) {
        boolean server = context.getLevel() instanceof ServerLevel level && level.getServer().isSameThread();
        return HeldEffects.placing(server, () -> original.call(context));
    }

    @ModifyExpressionValue(method = "onPlaceItemIntoWorld", at = {
            @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;onBlockPlace(Lnet/minecraft/world/entity/Entity;Lnet/neoforged/neoforge/common/util/BlockSnapshot;Lnet/minecraft/core/Direction;)Z"),
            @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;onMultiBlockPlace(Lnet/minecraft/world/entity/Entity;Ljava/util/List;Lnet/minecraft/core/Direction;)Z")})
    private static boolean hadron$cancelled(boolean cancelled) {
        if (cancelled) HeldEffects.refuse();
        return cancelled;
    }
}
