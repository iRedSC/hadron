package dev.iredsc.hadron.neoforge;

import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.api.event.inventory.MenuSlots;
import dev.iredsc.hadron.neoforge.mixin.item.CombinedInvWrapperAccessor;
import dev.iredsc.hadron.neoforge.mixin.item.RangedWrapperAccessor;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import org.jspecify.annotations.Nullable;

/**
 * Tells {@link MenuSlots} which slots of a mod's menu are the viewer's own: a mod showing the player's inventory
 * through NeoForge's item handlers ({@code PlayerInvWrapper}, {@code PlayerMainInvWrapper} and the rest) has slots
 * whose vanilla container is empty, so the slot is traced through its wrappers to the container behind it.
 */
final class PlayerSlots implements MenuSlots.Backend {
    private PlayerSlots() {}

    static void register(HostKey key) {
        MenuSlots.Port.install(key, new PlayerSlots());
    }

    @Override
    public boolean isViewers(Slot slot, Player viewer) {
        return slot instanceof SlotItemHandler handler
                && containerBehind(handler.getItemHandler(), handler.getContainerSlot()) == viewer.getInventory();
    }

    /**
     * The vanilla container behind slot {@code index} of {@code handler}, through NeoForge's ranged and combined
     * wrappers; null when it ends in a handler that isn't a vanilla container (a mod's own storage).
     */
    static @Nullable Container containerBehind(IItemHandler handler, int index) {
        IItemHandler current = handler;
        int at = index;
        // Bounded, in case a mod builds a wrapper that contains itself.
        for (int depth = 0; depth < 16; depth++) {
            switch (current) {
                case InvWrapper w -> {
                    return w.getInv();
                }
                case RangedWrapper r -> {
                    RangedWrapperAccessor a = (RangedWrapperAccessor) r;
                    at += a.hadron$minSlot();
                    current = a.hadron$compose();
                }
                case CombinedInvWrapper c -> {
                    CombinedInvWrapperAccessor a = (CombinedInvWrapperAccessor) c;
                    int which = a.hadron$indexForSlot(at);
                    if (which < 0) return null;
                    current = a.hadron$handlerFromIndex(which);
                    at = a.hadron$slotFromIndex(at, which);
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }
}
