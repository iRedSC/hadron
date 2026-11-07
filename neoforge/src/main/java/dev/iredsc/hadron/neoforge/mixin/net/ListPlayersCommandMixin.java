package dev.iredsc.hadron.neoforge.mixin.net;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.iredsc.hadron.neoforge.NetVisibility;
import java.util.List;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@code /list} leaves out players the sender can't see; the console and command blocks see everyone. */
@Mixin(ListPlayersCommand.class)
abstract class ListPlayersCommandMixin {
    @WrapOperation(method = "format", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"))
    private static List<ServerPlayer> hadron$visible(PlayerList list, Operation<List<ServerPlayer>> original,
            CommandSourceStack source, Function<ServerPlayer, Component> names) {
        List<ServerPlayer> all = original.call(list);
        ServerPlayer viewer = source.getPlayer();
        if (viewer == null) return all;
        return all.stream().filter(p -> NetVisibility.canSeePlayer(viewer, p)).toList();
    }
}
