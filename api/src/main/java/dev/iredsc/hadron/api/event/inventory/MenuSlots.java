package dev.iredsc.hadron.api.event.inventory;

import dev.iredsc.hadron.api.HostKey;
import dev.iredsc.hadron.checks.StaticState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Which of a menu's slots are the viewer's own inventory (main, hotbar, armour, offhand) and which are the holder's.
 * A mod's menu may show the player's inventory through its loader's item handlers rather than vanilla's container, so
 * the port knows more forms than {@code slot.container == player.getInventory()}. Slots a mod adds to the player
 * outside that inventory (Curios, Accessories) count as the holder's.
 */
public final class MenuSlots {
    @StaticState(reason = "the port installs, once at startup, how its loader's item handlers show the player's "
            + "inventory; it holds no server's state")
    private static volatile @Nullable Backend backend;

    private MenuSlots() {}

    /** Whether {@code slot} is one of {@code viewer}'s own inventory slots. */
    public static boolean isViewers(Slot slot, Player viewer) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(viewer, "viewer");
        if (slot.container == viewer.getInventory()) return true;
        Backend b = backend;
        return b != null && b.isViewers(slot, viewer);
    }

    /** {@code menu}'s slots that are {@code viewer}'s own inventory, in menu order. */
    public static List<Slot> viewerSlots(AbstractContainerMenu menu, Player viewer) {
        return split(menu, viewer, true);
    }

    /** {@code menu}'s slots that aren't {@code viewer}'s own inventory, in menu order. */
    public static List<Slot> holderSlots(AbstractContainerMenu menu, Player viewer) {
        return split(menu, viewer, false);
    }

    private static List<Slot> split(AbstractContainerMenu menu, Player viewer, boolean viewers) {
        List<Slot> out = new ArrayList<>();
        for (Slot slot : menu.slots) if (isViewers(slot, viewer) == viewers) out.add(slot);
        return List.copyOf(out);
    }

    /** For the loader port only. */
    public interface Backend {
        /** Whether a slot that isn't on the viewer's vanilla {@code Inventory} still shows it. */
        boolean isViewers(Slot slot, Player viewer);
    }

    /** For the loader port only. */
    public static final class Port {
        private Port() {}

        /** Takes the {@link HostKey}. */
        public static void install(HostKey key, @Nullable Backend installed) {
            HostKey.require(key, "MenuSlots.Port.install", "ask which slots are a viewer's with MenuSlots");
            backend = installed;
        }
    }
}
