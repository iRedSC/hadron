package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.WorldActors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Datapack functions run with an actor, like commands: the entity they run as (an advancement reward runs as its
 * player), otherwise {@link Actor.Server} named after the function, such as {@code function mypack:tick}. Covers tick
 * and load tags, {@code /schedule}, and rewards. A function started by something already acting (a player's
 * {@code /function}) keeps that actor.
 */
@Mixin(ServerFunctionManager.class)
public abstract class ServerFunctionManagerMixin {
    @WrapMethod(method = "execute")
    private void hadron$attribute(CommandFunction<CommandSourceStack> function, CommandSourceStack source,
            Operation<Void> original) {
        if (Hooks.host() == null || !Actors.isEmpty()) {
            original.call(function, source);
            return;
        }
        Entity entity = source.getEntity();
        Actor actor = entity != null ? WorldActors.of(entity) : new Actor.Server("function " + function.id());
        try (Actors.Scope scope = dev.iredsc.hadron.api.actor.Operation.begin(actor)) {
            original.call(function, source);
        }
    }
}
