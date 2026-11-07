package dev.iredsc.hadron.neoforge.mixin.block;

import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a piston is sticky, including mods' pistons built on vanilla's. */
@Mixin(PistonBaseBlock.class)
public interface PistonBaseBlockAccessor {
    @Accessor("isSticky")
    boolean hadron$isSticky();
}
