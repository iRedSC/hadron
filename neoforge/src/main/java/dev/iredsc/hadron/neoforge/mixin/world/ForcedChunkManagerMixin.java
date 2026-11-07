package dev.iredsc.hadron.neoforge.mixin.world;

import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceUnloadEvent;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldHooks;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge's ticket controllers, which chunk loaders and claim mods use. Refused before the controller records
 * anything, so a cancelled force isn't restored on the next start either.
 */
@Mixin(ForcedChunkManager.class)
public abstract class ForcedChunkManagerMixin {
    @Unique private static final String FORCE = "forceChunk(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Comparable;IIZZ"
            + "Lnet/minecraft/server/level/TicketType;Ljava/util/function/Function;)Z";

    @Inject(method = FORCE, at = @At("HEAD"), cancellable = true)
    private static void hadron$force(ServerLevel level, ResourceLocation id, Comparable<?> owner, int x, int z,
            boolean add, boolean ticking, TicketType<?> type, Function<?, ?> tickets,
            CallbackInfoReturnable<Boolean> cir) {
        if (!add || !Hooks.listens(ChunkForceLoadEvent.class)) return;
        if (WorldHooks.forceDenied(WorldHooks.ticketOwner(level, owner), level, new ChunkPos(x, z), ticking)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = FORCE, at = @At("RETURN"))
    private static void hadron$release(ServerLevel level, ResourceLocation id, Comparable<?> owner, int x, int z,
            boolean add, boolean ticking, TicketType<?> type, Function<?, ?> tickets,
            CallbackInfoReturnable<Boolean> cir) {
        if (add || !cir.getReturnValue() || !Hooks.listens(ChunkForceUnloadEvent.class)) return;
        WorldHooks.forceReleased(WorldHooks.ticketOwner(level, owner), level, new ChunkPos(x, z));
    }
}
