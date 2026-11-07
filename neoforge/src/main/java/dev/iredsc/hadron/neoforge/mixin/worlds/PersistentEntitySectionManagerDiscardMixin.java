package dev.iredsc.hadron.neoforge.mixin.worlds;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.iredsc.hadron.neoforge.WorldsHooks;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Worlds.unload(level, false): closing the world doesn't write its entities. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerDiscardMixin {
    @WrapWithCondition(method = "close",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V"))
    private boolean hadron$saveUnlessDiscarded(PersistentEntitySectionManager<?> self) {
        return !WorldsHooks.discarding();
    }
}
