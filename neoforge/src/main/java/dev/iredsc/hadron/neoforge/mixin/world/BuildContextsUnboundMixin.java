package dev.iredsc.hadron.neoforge.mixin.world;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.iredsc.hadron.neoforge.Hooks;
import dev.iredsc.hadron.neoforge.ServerEvents;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * One line of a datapack function, about to run. Function lines never pass through {@code Commands.performCommand},
 * where typed commands are asked about, so each is asked about here. Typed commands run as {@code TopLevel} entries,
 * not these, so none is asked about twice.
 */
@Mixin(BuildContexts.Unbound.class)
public abstract class BuildContextsUnboundMixin<T extends ExecutionCommandSource<T>>
        implements ServerEvents.Rewritten {
    /** Set on the replacement a listener asked for, which runs without being asked about again. */
    @Unique private boolean hadron$asked;

    @Override
    public void hadron$markAsked() {
        hadron$asked = true;
    }

    @SuppressWarnings("unchecked")
    @WrapMethod(method = "execute(Lnet/minecraft/commands/ExecutionCommandSource;"
            + "Lnet/minecraft/commands/execution/ExecutionContext;Lnet/minecraft/commands/execution/Frame;)V")
    private void hadron$command(T source, ExecutionContext<T> context, Frame frame, Operation<Void> original) {
        if (hadron$asked || Hooks.host() == null || !(source instanceof CommandSourceStack stack)) {
            original.call(source, context, frame);
            return;
        }
        String command = toString(); // BuildContexts.toString is the command's text
        String run = ServerEvents.ask(stack, command);
        if (run == null) return;
        if (run.equals(command)) {
            original.call(source, context, frame);
            return;
        }
        ParseResults<CommandSourceStack> parse = stack.getServer().getCommands().getDispatcher().parse(run, stack);
        Optional<ContextChain<CommandSourceStack>> chain;
        try {
            Commands.validateParseResults(parse);
            chain = ContextChain.tryFlatten(parse.getContext().build(run));
            if (chain.isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
                        .createWithContext(parse.getReader());
            }
        } catch (CommandSyntaxException e) {
            stack.handleError(e, false, ((ExecutionContext<CommandSourceStack>) (Object) context).tracer());
            return;
        }
        BuildContexts.Unbound<CommandSourceStack> replacement = new BuildContexts.Unbound<>(run, chain.get());
        ((ServerEvents.Rewritten) (Object) replacement).hadron$markAsked();
        replacement.execute(stack, (ExecutionContext<CommandSourceStack>) (Object) context, frame);
    }
}
