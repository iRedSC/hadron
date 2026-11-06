package dev.iredsc.hadron.neoforge.mixin.net;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets visibility re-ask tracking about one viewer, and despawn an entity for one viewer. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
    @Invoker("updatePlayer")
    void hadron$updatePlayer(ServerPlayer player);

    @Invoker("removePlayer")
    void hadron$removePlayer(ServerPlayer player);
}
