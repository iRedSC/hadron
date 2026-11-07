package dev.iredsc.hadron.neoforge.mixin.worlds;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Dimension arguments ask the server for suggestions. A vanilla client otherwise suggests from the dimension list it
 * got once at login, so a world created after it joined would never be suggested; the server's list is always current.
 */
@Mixin(Commands.class)
abstract class CommandsSuggestionsMixin {
    @WrapOperation(method = "fillUsableCommands", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/builder/ArgumentBuilder;build()Lcom/mojang/brigadier/tree/CommandNode;"))
    private CommandNode<SharedSuggestionProvider> hadron$askServer(ArgumentBuilder<SharedSuggestionProvider, ?> builder,
            Operation<CommandNode<SharedSuggestionProvider>> original) {
        if ((Object) builder instanceof RequiredArgumentBuilder<?, ?> raw && raw.getType() instanceof DimensionArgument
                && raw.getSuggestionsProvider() == null) {
            @SuppressWarnings("unchecked")
            RequiredArgumentBuilder<SharedSuggestionProvider, ?> argument =
                    (RequiredArgumentBuilder<SharedSuggestionProvider, ?>) raw;
            argument.suggests(SuggestionProviders.ASK_SERVER);
        }
        return original.call(builder);
    }
}
