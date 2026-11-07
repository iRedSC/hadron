package dev.iredsc.hadron.selftest.plugin;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/** Not a test: a chest menu whose class is {@link HostProbePlugin}'s, so Hadron knows the plugin opened it. */
public final class HostProbeMenu extends ChestMenu {
    public HostProbeMenu(int id, Inventory inventory) {
        super(MenuType.GENERIC_9x3, id, inventory, new SimpleContainer(27), 3);
    }
}
