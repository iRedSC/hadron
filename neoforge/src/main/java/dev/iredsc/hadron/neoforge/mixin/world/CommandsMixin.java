package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every command runs with an actor: the player or entity behind it, or {@link Actor.Server} for the console, RCON and
 * command blocks. So an operator's {@code /setblock} fires block events a logger records, instead of passing unseen.
 * Chat commands reach the server thread through their own task, not PacketUtils, so this also covers what the packet
 * attribution can't. A command run inside something already acting (a mod's code, a quest reward) keeps that actor.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @WrapMethod(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V")
    private void hadron$attribute(ParseResults<CommandSourceStack> parse, String command, Operation<Void> original) {
        if (Hooks.host() == null || !Actors.isEmpty()) {
            original.call(parse, command);
            return;
        }
        CommandSourceStack source = parse.getContext().getSource();
        Entity entity = source.getEntity();
        Actor actor = entity != null ? WorldActors.of(entity) : new Actor.Server(source.getTextName());
        try (Actors.Scope scope = dev.iredsc.hadron.api.actor.Operation.begin(actor)) {
            original.call(parse, command);
        }
    }
}
