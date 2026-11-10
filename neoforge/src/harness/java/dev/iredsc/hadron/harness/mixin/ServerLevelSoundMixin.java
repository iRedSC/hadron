package dev.iredsc.hadron.harness.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.harness.TestSideEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every sound and numbered event a world sends, for {@code SideEffects}: read at the broadcast itself, once whoever
 * it reaches (nobody near, or everyone but the player whose client plays it), so a sound NeoForge's event cancelled
 * or a hold dropped is never seen, and one a hold kept is seen once, when it plays. The copies of its packet the
 * broadcast then sends aren't recorded again ({@code PlayerConnectionSoundMixin}); anything else sent meanwhile is.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelSoundMixin {
    private static final String BROADCAST = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V";

    @WrapOperation(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At(value = "INVOKE", target = BROADCAST))
    private void hadron$recordSound(PlayerList players, @Nullable Player except, double x, double y, double z,
            double range, ResourceKey<Level> dimension, Packet<?> packet, Operation<Void> original) {
        if (packet instanceof ClientboundSoundPacket sound) {
            TestSideEffects.soundSent((ServerLevel) (Object) this, except, sound.getSound(), sound.getSource(),
                    new Vec3(x, y, z), null);
        }
        TestSideEffects.broadcasting(packet, () -> original.call(players, except, x, y, z, range, dimension, packet));
    }

    @WrapOperation(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At(value = "INVOKE", target = BROADCAST))
    private void hadron$recordEntitySound(PlayerList players, @Nullable Player except, double x, double y, double z,
            double range, ResourceKey<Level> dimension, Packet<?> packet, Operation<Void> original,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) Entity entity) {
        if (packet instanceof ClientboundSoundEntityPacket sound) {
            TestSideEffects.soundSent((ServerLevel) (Object) this, except, sound.getSound(), sound.getSource(),
                    new Vec3(x, y, z), entity);
        }
        TestSideEffects.broadcasting(packet, () -> original.call(players, except, x, y, z, range, dimension, packet));
    }

    @WrapOperation(method = "levelEvent", at = @At(value = "INVOKE", target = BROADCAST))
    private void hadron$recordLevelEvent(PlayerList players, @Nullable Player except, double x, double y, double z,
            double range, ResourceKey<Level> dimension, Packet<?> packet, Operation<Void> original) {
        if (packet instanceof ClientboundLevelEventPacket event) {
            TestSideEffects.levelEventSent((ServerLevel) (Object) this, except, event.getType(), event.getPos(),
                    event.getData(), false);
        }
        TestSideEffects.broadcasting(packet, () -> original.call(players, except, x, y, z, range, dimension, packet));
    }

    @WrapOperation(method = "globalLevelEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;)V"))
    private void hadron$recordGlobalLevelEvent(PlayerList players, Packet<?> packet, Operation<Void> original) {
        if (packet instanceof ClientboundLevelEventPacket event) {
            TestSideEffects.levelEventSent((ServerLevel) (Object) this, null, event.getType(), event.getPos(),
                    event.getData(), true);
        }
        TestSideEffects.broadcasting(packet, () -> original.call(players, packet));
    }
}
