package dev.iredsc.hadron.neoforge.mixin.block;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.event.block.BlockDamageEvent;
import dev.iredsc.hadron.neoforge.BlockHooks;
import dev.iredsc.hadron.neoforge.EntityActors;
import dev.iredsc.hadron.neoforge.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player starting to mine a block is asked about before the block is hit; a player mining a block is reported once,
 * by NeoForge's break event inside, with the item in their main hand as the tool (vanilla harvests with it), and the
 * block removal that follows is not reported again.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow protected ServerLevel level;
    @Shadow @Final protected ServerPlayer player;
    @Shadow private boolean isDestroyingBlock;
    @Shadow private BlockPos destroyPos;
    @Shadow private boolean hasDelayedDestroy;
    @Shadow private BlockPos delayedDestroyPos;
    /** The block this player was last refused starting on, until they start again; null when none. */
    @Unique private @Nullable BlockPos hadron$refused;

    /**
     * A finish for a refused start does nothing. Vanilla checks a finish only against the last block mined, with that
     * mining's timer, so without this a player who mined a block, stopped, and was refused starting again could still
     * break it by finishing.
     */
    @Inject(method = "handleBlockBreakAction", cancellable = true, at = @At("HEAD"))
    private void hadron$finishRefused(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face,
            int maxBuildHeight, int sequence, CallbackInfo ci) {
        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            hadron$refused = null;
        } else if (action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK && pos.equals(hadron$refused)) {
            hadron$refused = null;
            player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
            ci.cancel();
        }
    }

    /**
     * Where vanilla starts mining: past its own refusals (out of reach, adventure mode, spawn protection) and creative's
     * instant break, before an on-hit enchantment acts, the block is hit or the crack starts. Refused, the client is
     * shown the block unchanged, as vanilla does for its own refusals.
     */
    @Inject(method = "handleBlockBreakAction", cancellable = true, at = @At(value = "FIELD", ordinal = 0,
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyProgressStart:I",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void hadron$startMining(BlockPos pos, ServerboundPlayerActionPacket.Action action, Direction face,
            int maxBuildHeight, int sequence, CallbackInfo ci) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !Hooks.listens(BlockDamageEvent.class)) return;
        BlockDamageEvent event = Hooks.post(new BlockDamageEvent(level, pos, state, player.getMainHandItem(), face,
                EntityActors.of(player)));
        if (!event.isCancelled()) return;
        // Mining of this block left over from before stops too, so nothing carries the refused block on to breaking.
        hadron$refused = pos.immutable();
        if (isDestroyingBlock && destroyPos.equals(pos)) isDestroyingBlock = false;
        if (hasDelayedDestroy && delayedDestroyPos.equals(pos)) hasDelayedDestroy = false;
        level.destroyBlockProgress(player.getId(), pos, -1);
        player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        ci.cancel();
    }

    @WrapMethod(method = "destroyBlock")
    private boolean hadron$destroyOnce(BlockPos pos, Operation<Boolean> original) {
        BlockHooks.suppressAt(level, pos);
        BlockHooks.Mining previous = BlockHooks.enterMining(player, level, pos);
        try {
            return original.call(pos);
        } finally {
            BlockHooks.exitMining(previous);
            BlockHooks.restoreSuppressAt();
        }
    }
}
