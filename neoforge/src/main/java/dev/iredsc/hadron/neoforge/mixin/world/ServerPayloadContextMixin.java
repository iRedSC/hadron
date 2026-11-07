package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import java.util.function.Supplier;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Mods handle their own packets on the server thread through enqueueWork, not PacketUtils; the player who sent the
 * packet is the actor for that work too.
 */
@Mixin(ServerPayloadContext.class)
public abstract class ServerPayloadContextMixin {
    @Shadow
    public abstract ServerCommonPacketListener listener();

    @ModifyVariable(method = "enqueueWork(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private Runnable hadron$attribute(Runnable task) {
        if (!(listener() instanceof ServerGamePacketListenerImpl game) || Hooks.host() == null) return task;
        ServerPlayer player = game.player;
        return () -> {
            try (Actors.Scope scope = Actors.push(WorldActors.player(player))) {
                task.run();
            }
        };
    }

    @ModifyVariable(method = "enqueueWork(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), argsOnly = true)
    private Supplier<?> hadron$attribute(Supplier<?> task) {
        if (!(listener() instanceof ServerGamePacketListenerImpl game) || Hooks.host() == null) return task;
        ServerPlayer player = game.player;
        return () -> {
            try (Actors.Scope scope = Actors.push(WorldActors.player(player))) {
                return task.get();
            }
        };
    }
}
