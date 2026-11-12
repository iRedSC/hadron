package dev.iredsc.hadron.neoforge.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.iredsc.hadron.neoforge.EntityTeleports;
import net.minecraft.world.entity.monster.Shulker;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A shulker's own teleport is asked about at NeoForge's event inside it, then placed with {@code setPos}, no teleport
 * method: run as one teleport, so getting it and its riders off what they ride is part of it (forced), and where it
 * lands is checked against what its event stated.
 */
@Mixin(Shulker.class)
abstract class ShulkerMixin {
    @WrapMethod(method = "teleportSomewhere")
    private boolean hadron$teleportSomewhere(Operation<Boolean> original) {
        return EntityTeleports.during((Shulker) (Object) this, original::call);
    }
}
