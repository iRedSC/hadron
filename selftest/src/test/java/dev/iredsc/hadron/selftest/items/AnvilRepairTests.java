package dev.iredsc.hadron.selftest.items;

import static dev.iredsc.hadron.selftest.items.Support.describe;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.item.ItemRepairEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Repairing in an anvil with a material. A plugin listening only for {@link ItemRepairEvent}, not its parent, must
 * hear it and be able to refuse it.
 */
public final class AnvilRepairTests {
    private static final int FIRST_HOTBAR = 30;
    private static final int DAMAGE = 200;

    /** Opens an anvil with a damaged iron sword and an iron ingot in it, offering the repaired sword. */
    private static AnvilMenu repairSword(TestContext t, TestPlayer alice) {
        BlockPos anvil = t.pos(1, 0, 0);
        t.setBlock(anvil, Blocks.ANVIL);
        alice.player().giveExperienceLevels(10);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(DAMAGE);
        alice.player().getInventory().setItem(0, sword);
        alice.player().getInventory().setItem(1, new ItemStack(Items.IRON_INGOT));
        alice.useBlock(anvil);
        t.assertTrue(alice.player().containerMenu instanceof AnvilMenu, "right-clicking the anvil should open it");
        alice.clickSlot(FIRST_HOTBAR);
        alice.clickSlot(0);
        alice.clickSlot(FIRST_HOTBAR + 1);
        alice.clickSlot(1);
        AnvilMenu menu = (AnvilMenu) alice.player().containerMenu;
        ItemStack offered = menu.getSlot(2).getItem();
        t.assertTrue(offered.is(Items.IRON_SWORD) && offered.getDamageValue() < DAMAGE, "the anvil should offer "
                + "the repaired sword, but offers " + describe(offered));
        return menu;
    }

    @HadronTest
    @Covers(ItemRepairEvent.class)
    void aListenerForRepairsAloneHearsAnAnvilRepair(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemRepairEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemRepairEvent e) {
                heard.add(e);
            }
        });
        AnvilMenu menu = repairSword(t, alice);
        alice.clickSlot(2);
        t.assertEquals(1, heard.size(), "ItemRepairEvents heard for one anvil repair");
        t.assertEquals(DAMAGE, heard.get(0).getBefore().getDamageValue(), "the sword's damage before");
        t.assertTrue(menu.getCarried().is(Items.IRON_SWORD), "alice should have taken the sword");
    }

    @HadronTest
    @Covers(ItemRepairEvent.class)
    void aRefusedRepairGivesNothingWhenOnlyRepairsAreHeard(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemRepairEvent e) {
                e.setCancelled(true);
            }
        });
        AnvilMenu menu = repairSword(t, alice);
        alice.clickSlot(2);
        t.assertTrue(menu.getCarried().isEmpty(), "a refused repair should give nothing, but the cursor holds "
                + describe(menu.getCarried()));
        t.assertEquals(DAMAGE, menu.getSlot(0).getItem().getDamageValue(), "the damaged sword left in the anvil");
        t.assertEquals(10, alice.player().experienceLevel, "alice's levels");
    }

    @HadronTest
    @Covers(ItemRepairEvent.class)
    void aRefusedShiftClickRepairGivesNothingWhenOnlyRepairsAreHeard(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemRepairEvent e) {
                e.setCancelled(true);
            }
        });
        AnvilMenu menu = repairSword(t, alice);
        alice.clickSlot(2, 0, ClickType.QUICK_MOVE);
        t.assertEquals(DAMAGE, menu.getSlot(0).getItem().getDamageValue(), "the damaged sword left in the anvil");
        t.assertEquals(0, alice.player().getInventory().countItem(Items.IRON_SWORD), "swords alice took");
        t.assertEquals(10, alice.player().experienceLevel, "alice's levels");
    }
}
