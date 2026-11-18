package dev.iredsc.hadron.neoforge.mixin.item;

import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The handler a ranged wrapper shows part of, and where that part starts, so a slot can be traced to its container. */
@Mixin(RangedWrapper.class)
public interface RangedWrapperAccessor {
    @Accessor("compose")
    IItemHandlerModifiable hadron$compose();

    @Accessor("minSlot")
    int hadron$minSlot();
}
