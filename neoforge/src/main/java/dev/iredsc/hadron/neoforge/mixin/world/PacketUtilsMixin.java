package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.neoforge.PortKey;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a player's packet is handled on the server thread, that player is the actor, so whatever it makes a mod do
 * (use an item, click a block, open a machine) is theirs. Every game packet the server handles on its own thread
 * comes through this one scheduled task.
 */
@Mixin(PacketUtils.class)
public abstract class PacketUtilsMixin {
    @WrapOperation(method = "lambda$ensureRunningOnSameThread$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private static void hadron$attribute(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (!(listener instanceof ServerGamePacketListenerImpl game) || Hooks.host() == null) {
            original.call(packet, listener);
            return;
        }
        long scope = Actors.Port.enter(PortKey.KEY, WorldActors.player(game.player));
        try {
            original.call(packet, listener);
        } finally {
            Actors.Port.exit(PortKey.KEY, scope);
        }
    }
}
