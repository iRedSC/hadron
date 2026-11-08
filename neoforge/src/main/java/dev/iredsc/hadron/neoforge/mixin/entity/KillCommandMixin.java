package dev.iredsc.hadron.neoforge.mixin.entity;

import dev.iredsc.hadron.neoforge.EntityRemovals;
import java.util.Collection;
import net.minecraft.server.commands.KillCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@code /kill} asks before removing each entity that won't die from it, and skips the ones refused, so its reply
 * counts only what it really killed. Asked here rather than in {@code kill()}, which mods override and vanilla calls
 * after dropping a broken boat's item.
 */
@Mixin(KillCommand.class)
abstract class KillCommandMixin {
    @ModifyVariable(method = "kill(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;)I",
            at = @At("HEAD"), argsOnly = true)
    private static Collection<? extends Entity> hadron$refused(Collection<? extends Entity> targets) {
        return EntityRemovals.killable(targets);
    }
}
