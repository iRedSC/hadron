package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.event.player.PlayerModPacketEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * PlayerModPacketEvent, on the server thread, before the mod's handler. A handler meant for the server thread (the
 * default) is queued exactly as NeoForge would; one meant for the network thread is moved to the server thread only
 * while a plugin listens, so it can be refused before it runs.
 */
@Mixin(NetworkRegistry.class)
public abstract class NetworkRegistryMixin {
    @WrapOperation(method = "handleModdedPayload(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;"
            + "Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/handling/IPayloadHandler;handle(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;"
                    + "Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void hadron$modPacket(IPayloadHandler handler, CustomPacketPayload payload, IPayloadContext context,
            Operation<Void> original) {
        boolean mainThread = handler instanceof MainThreadPayloadHandler;
        if (!(context.listener() instanceof ServerGamePacketListenerImpl game)
                || (!mainThread && !Hooks.listens(PlayerModPacketEvent.class))) {
            original.call(handler, payload, context);
            return;
        }
        IPayloadHandler inner = mainThread ? ((MainThreadPayloadHandler) handler).handler() : handler;
        ServerPlayer player = game.player;
        context.enqueueWork(() -> {
            if (Hooks.listens(PlayerModPacketEvent.class)
                    && Hooks.post(new PlayerModPacketEvent(player, payload)).isCancelled()) {
                return;
            }
            inner.handle(payload, context);
        });
    }
}
