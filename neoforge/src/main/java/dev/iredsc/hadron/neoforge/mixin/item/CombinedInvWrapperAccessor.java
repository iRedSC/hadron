package dev.iredsc.hadron.neoforge.mixin.item;

import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Which of a combined wrapper's handlers a slot is in, and its index there, so a slot can be traced to its container. */
@Mixin(CombinedInvWrapper.class)
public interface CombinedInvWrapperAccessor {
    @Invoker("getIndexForSlot")
    int hadron$indexForSlot(int slot);

    @Invoker("getHandlerFromIndex")
    IItemHandlerModifiable hadron$handlerFromIndex(int index);

    @Invoker("getSlotFromIndex")
    int hadron$slotFromIndex(int slot, int index);
}
