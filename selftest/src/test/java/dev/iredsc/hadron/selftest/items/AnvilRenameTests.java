package dev.iredsc.hadron.selftest.items;

import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Renaming in an anvil: a change to a stack that is neither an enchant nor a repair, so it is a plain ItemChangeEvent.
 * The harness has no rename action; the test hands the server the packet a client sends as the name is typed.
 */
public final class AnvilRenameTests {
    private static final int THREE_SLOT_HOTBAR = 30;

    private static AnvilMenu renameSword(TestContext t, TestPlayer alice) {
        BlockPos anvil = t.pos(1, 0, 0);
        t.setBlock(anvil, Blocks.ANVIL);
        alice.player().giveExperienceLevels(10);
        alice.player().getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        alice.useBlock(anvil);
        t.assertTrue(alice.player().containerMenu instanceof AnvilMenu, "right-clicking the anvil should open it");
        alice.clickSlot(THREE_SLOT_HOTBAR);
        alice.clickSlot(0);
        alice.player().connection.handleRenameItem(new ServerboundRenameItemPacket("Excalibur"));
        AnvilMenu menu = (AnvilMenu) alice.player().containerMenu;
        t.assertTrue(menu.getSlot(2).getItem().getHoverName().getString().equals("Excalibur"), "the anvil should "
                + "offer the renamed sword, but offers " + describe(menu.getSlot(2).getItem()));
        return menu;
    }

    @HadronTest
    @Covers(ItemChangeEvent.class)
    void takingARenamedItemIsOneItemChangeByThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemChangeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemChangeEvent e) {
                heard.add(e);
            }
        });
        AnvilMenu menu = renameSword(t, alice);
        alice.clickSlot(2);
        t.assertEquals(List.of("ItemChangeEvent"), heard.stream().map(e -> e.getClass().getSimpleName()).toList(),
                "the item change events for taking a renamed sword");
        ItemChangeEvent e = heard.get(0);
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(e.getBefore().is(Items.IRON_SWORD) && !e.getBefore().getHoverName().getString().equals("Excalibur"),
                "the stack before should be the unnamed sword, but was " + describe(e.getBefore()));
        t.assertEquals("Excalibur", e.getAfter().getHoverName().getString(), "the stack after");
        t.assertEquals("Excalibur", menu.getCarried().getHoverName().getString(), "what alice took");
    }

    @HadronTest
    @Covers(ItemChangeEvent.class)
    void aCancelledRenameGivesNothingAndCostsNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemChangeEvent e) {
                e.setCancelled(true);
            }
        });
        AnvilMenu menu = renameSword(t, alice);
        alice.clickSlot(2);
        t.assertTrue(menu.getCarried().isEmpty(), "a refused rename should give nothing, but the cursor holds "
                + describe(menu.getCarried()));
        t.assertTrue(menu.getSlot(0).getItem().is(Items.IRON_SWORD), "the sword should stay in the anvil");
        t.assertEquals(10, alice.player().experienceLevel, "alice's levels");
    }
}
