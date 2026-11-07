package dev.iredsc.hadron.neoforge.mixin.worlds;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * As {@link CommandsSuggestionsMixin}, for the tree NeoForge actually sends: its {@code Commands.sendCommands} builds
 * the client's command tree with {@code CommandHelper.mergeCommandNode}, never vanilla's {@code fillUsableCommands}.
 */
@Mixin(targets = "net.neoforged.neoforge.server.command.CommandHelper")
abstract class CommandHelperSuggestionsMixin {
    @WrapOperation(method = "toResult", at = @At(value = "INVOKE",
            target = "Lcom/mojang/brigadier/builder/ArgumentBuilder;build()Lcom/mojang/brigadier/tree/CommandNode;"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandNode<?> hadron$askServer(ArgumentBuilder<?, ?> builder, Operation<CommandNode<?>> original) {
        if (builder instanceof RequiredArgumentBuilder raw && raw.getType() instanceof DimensionArgument
                && raw.getSuggestionsProvider() == null) {
            raw.suggests((SuggestionProvider) SuggestionProviders.ASK_SERVER);
        }
        return original.call(builder);
    }
}
