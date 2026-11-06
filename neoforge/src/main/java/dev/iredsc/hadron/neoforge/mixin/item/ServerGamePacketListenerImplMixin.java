package dev.iredsc.hadron.neoforge.mixin.item;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.item.ItemHeldEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.ItemHooks;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ItemHeldEvent: a player selecting a hotbar slot, after vanilla checked the slot is valid. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", cancellable = true, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Inventory;selected:I", opcode = Opcodes.PUTFIELD))
    private void hadron$held(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        int previous = player.getInventory().selected;
        int next = packet.getSlot();
        if (previous == next || !Hooks.listens(ItemHeldEvent.class)) return;
        Actor actor = ItemHooks.actor(player);
        if (Hooks.post(new ItemHeldEvent(player, previous, next, actor)).isCancelled()) {
            player.connection.send(new ClientboundSetCarriedItemPacket(previous));
            ci.cancel();
        }
    }
}
