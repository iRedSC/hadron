package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A command a player typed runs with them as the actor. Chat commands reach the server thread through their own task,
 * not PacketUtils, so this covers what the packet attribution can't.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @WrapMethod(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V")
    private void hadron$attribute(ParseResults<CommandSourceStack> parse, String command, Operation<Void> original) {
        if (Hooks.host() != null && parse.getContext().getSource().getEntity() instanceof ServerPlayer player
                && Actors.isEmpty()) {
            try (Actors.Scope scope = Actors.push(WorldActors.player(player))) {
                original.call(parse, command);
            }
            return;
        }
        original.call(parse, command);
    }
}
