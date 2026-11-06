package dev.iredsc.hadron.neoforge.mixin.net;

import dev.iredsc.hadron.neoforge.NetHooks;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player name suggestions for commands leave out players hidden from whoever is typing. */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
    @Shadow @Final private Entity entity;

    @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
    private void hadron$hideNames(CallbackInfoReturnable<Collection<String>> cir) {
        if (NetHooks.visibilityActive() && entity instanceof ServerPlayer viewer) {
            cir.setReturnValue(NetHooks.visibleNames(viewer, cir.getReturnValue()));
        }
    }
}
