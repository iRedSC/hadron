package dev.iredsc.hadron.selftest.inventories;

import static dev.iredsc.hadron.selftest.items.Support.assertMachineActor;
import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;
import static dev.iredsc.hadron.selftest.items.Support.one;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryCloseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Menus a player opens, clicks in and closes, and items moving between containers on their own. */
public final class InventoryTests {
    private static ChestBlockEntity chest(TestContext t, BlockPos pos) {
        t.setBlock(pos, Blocks.CHEST);
        return (ChestBlockEntity) t.level().getBlockEntity(pos);
    }

    @HadronTest
    void cancelledOpenKeepsTheChestClosed(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        chest(t, pos);
        List<InventoryOpenEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryOpenEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.useBlock(pos);
        InventoryOpenEvent e = one(t, heard, "InventoryOpenEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(new InventoryHolder.Block(t.level(), pos), e.getHolder(), "the opened inventory's holder");
        t.assertTrue(alice.player().containerMenu == alice.player().inventoryMenu, "a refused open should leave "
                + "alice in her own inventory, but she has " + alice.player().containerMenu + " open");
        t.assertTrue(alice.received(net.minecraft.network.protocol.game.ClientboundOpenScreenPacket.class).isEmpty(),
                "alice's client should never be told to open the chest");
    }

    @HadronTest
    void closingAChestFiresCloseWithItsHolder(TestContext t) {
        // The harness has no close-window packet yet; this closes it as the server's packet handler does.
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        chest(t, pos);
        List<InventoryCloseEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryCloseEvent e) {
                heard.add(e);
            }
        });
        alice.useBlock(pos);
        t.assertTrue(alice.player().containerMenu instanceof ChestMenu, "the chest should open");
        alice.player().closeContainer();
        InventoryCloseEvent e = one(t, heard, "InventoryCloseEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(new InventoryHolder.Block(t.level(), pos), e.getHolder(), "the closed inventory's holder");
    }

    @HadronTest
    void cancelledClickMovesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        ChestBlockEntity chest = chest(t, pos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        List<InventoryClickEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryClickEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.useBlock(pos);
        alice.clickSlot(0);
        alice.clickSlot(0, 0, ClickType.QUICK_MOVE);
        t.assertEquals(2, heard.size(), "InventoryClickEvents fired");
        InventoryClickEvent e = heard.get(0);
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(0, e.getRawSlot(), "the clicked slot");
        t.assertEquals(new InventoryHolder.Block(t.level(), pos), e.getHolder(), "the clicked slot's holder");
        t.assertEquals(3, chest.getItem(0).getCount(), "diamonds left in the chest");
        t.assertTrue(alice.player().containerMenu.getCarried().isEmpty(), "the cursor should be empty, but holds "
                + describe(alice.player().containerMenu.getCarried()));
        t.assertInventory(alice, Items.DIAMOND, 0);
    }

    @HadronTest
    void cancelledHopperPullLeavesTheItemsInTheChestAndNamesTheHopper(TestContext t) {
        dev.iredsc.hadron.selftest.items.Support.watch(t);
        BlockPos hopperPos = t.pos(4, 0, 0);
        BlockPos chestPos = hopperPos.above();
        t.setBlock(hopperPos, Blocks.HOPPER);
        ChestBlockEntity chest = chest(t, chestPos);
        chest.setItem(0, new ItemStack(Items.DIRT, 5));
        List<InventoryMoveItemEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryMoveItemEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        t.await("the hopper to try to pull from the chest", () -> !heard.isEmpty(), 60);
        t.tick(5);
        InventoryMoveItemEvent e = heard.get(0);
        assertMachineActor(t, e.getActor(), hopperPos);
        t.assertEquals(new InventoryHolder.Block(t.level(), chestPos), e.getSource(), "the move's source");
        t.assertEquals(new InventoryHolder.Block(t.level(), hopperPos), e.getDestination(), "the move's destination");
        t.assertEquals(5, chest.getItem(0).getCount(), "dirt left in the chest");
        t.assertTrue(((HopperBlockEntity) t.level().getBlockEntity(hopperPos)).isEmpty(), "the hopper should stay empty");
    }

    @HadronTest
    void aHopperMoveIsHeardAsAnInventoryChange(TestContext t) {
        dev.iredsc.hadron.selftest.items.Support.watch(t);
        BlockPos hopperPos = t.pos(4, 0, 0);
        t.setBlock(hopperPos, Blocks.HOPPER);
        ChestBlockEntity chest = chest(t, hopperPos.above());
        chest.setItem(0, new ItemStack(Items.DIRT, 1));
        List<InventoryChangeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryChangeEvent e) {
                heard.add(e);
            }
        });
        t.await("the hopper to pull from the chest", () -> !heard.isEmpty(), 60);
        t.tick(2);
        InventoryHolder hopper = new InventoryHolder.Block(t.level(), hopperPos);
        t.assertTrue(heard.get(0).getHolders().contains(hopper), "the change should list the hopper, but lists "
                + heard.get(0).getHolders());
        t.assertTrue(chest.getItem(0).isEmpty(), "the uncancelled move should empty the chest");
    }
}
