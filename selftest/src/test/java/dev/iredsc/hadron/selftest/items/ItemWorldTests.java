package dev.iredsc.hadron.selftest.items;

import static dev.iredsc.hadron.selftest.items.Support.assertMachineActor;
import static dev.iredsc.hadron.selftest.items.Support.assertNatureActor;
import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;
import static dev.iredsc.hadron.selftest.items.Support.dropAt;
import static dev.iredsc.hadron.selftest.items.Support.itemsNear;
import static dev.iredsc.hadron.selftest.items.Support.one;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemDespawnEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** Items entering and leaving the world: drops, pickups, despawning, merging. */
public final class ItemWorldTests {
    private static final int OFFHAND_MENU_SLOT = InventoryMenu.SHIELD_SLOT;
    private static final int OFFHAND_INVENTORY_SLOT = 40;

    @HadronTest
    void refusedDropFromTheHandStaysInTheHandAndNothingDrops(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIRT, 5));
        List<ItemDropEvent> heard = new ArrayList<>();
        int[] offered = {-1};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDropEvent e) {
                heard.add(e);
                offered[0] = e.getItem().getCount(); // the live stack: it goes back to the inventory after this
                e.setCancelled(true);
            }
        });
        alice.dropStack();
        t.tick(2);
        ItemDropEvent e = one(t, heard, "ItemDropEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(5, offered[0], "the number of items the drop event offered");
        t.assertHolding(alice, Items.DIRT, 5);
        t.assertInventory(alice, Items.DIRT, 5);
        t.assertEquals(0, itemsNear(t, t.pos(0, 0, 0), 4).size(), "item entities after a refused drop");
    }

    @HadronTest
    void refusedDropFromTheCursorGoesBackIntoTheInventory(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIRT, 5));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDropEvent e) {
                e.setCancelled(true);
            }
        });
        alice.clickSlot(InventoryMenu.USE_ROW_SLOT_START); // the stack onto the cursor
        alice.clickSlot(InventoryMenu.SLOT_CLICKED_OUTSIDE, 0, ClickType.PICKUP); // thrown out of the window
        t.tick(2);
        t.assertInventory(alice, Items.DIRT, 5);
        t.assertTrue(alice.player().containerMenu.getCarried().isEmpty(), "the cursor should be empty once the "
                + "refused drop went back into the inventory, but holds "
                + describe(alice.player().containerMenu.getCarried()));
        t.assertEquals(0, itemsNear(t, t.pos(0, 0, 0), 4).size(), "item entities after a refused drop");
    }

    @HadronTest
    void refusedDropFromTheCursorWithAFullInventoryStaysOnTheCursor(TestContext t) {
        TestPlayer alice = t.player("alice");
        for (int i = 0; i < 36; i++) alice.player().getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        alice.player().getInventory().setItem(OFFHAND_INVENTORY_SLOT, new ItemStack(Items.DIRT, 5));
        alice.player().inventoryMenu.broadcastChanges();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDropEvent e) {
                e.setCancelled(true);
            }
        });
        alice.clickSlot(OFFHAND_MENU_SLOT); // the offhand's dirt onto the cursor; the main inventory is full
        alice.clickSlot(InventoryMenu.SLOT_CLICKED_OUTSIDE, 0, ClickType.PICKUP);
        t.tick(2);
        ItemStack carried = alice.player().containerMenu.getCarried();
        t.assertTrue(carried.is(Items.DIRT) && carried.getCount() == 5, "a refused drop with nowhere else to go "
                + "should stay on the cursor, but the cursor holds " + describe(carried));
        t.assertEquals(0, itemsNear(t, t.pos(0, 0, 0), 4).size(), "item entities after a refused drop");
        t.assertInventory(alice, Items.STONE, 36 * 64);
    }

    @HadronTest
    void refusedDropperDropStaysInTheDropperAttributedToTheMachine(TestContext t) {
        Support.watch(t);
        BlockPos dropper = t.pos(2, 0, 0);
        t.setBlock(dropper, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        DispenserBlockEntity be = (DispenserBlockEntity) t.level().getBlockEntity(dropper);
        be.setItem(0, new ItemStack(Items.DIRT, 3));
        List<ItemDropEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDropEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        t.setBlock(t.pos(2, 0, 1), Blocks.REDSTONE_BLOCK);
        t.await("the dropper to fire", () -> !heard.isEmpty(), 60);
        t.tick(2);
        ItemDropEvent e = one(t, heard, "ItemDropEvent");
        assertMachineActor(t, e.getActor(), dropper);
        t.assertEquals(3, be.getItem(0).getCount(), "the dirt left in the dropper");
        t.assertEquals(0, itemsNear(t, dropper, 3).size(), "item entities after a refused dropper drop");
    }

    @HadronTest
    void refusedPickupLeavesTheItemOnTheGround(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemPickupEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemPickupEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        ItemEntity item = dropAt(t, t.pos(0, 0, 0), new ItemStack(Items.DIAMOND, 2));
        t.await("alice to touch the diamonds", () -> !heard.isEmpty(), 60);
        t.tick(5);
        t.assertTrue(!heard.isEmpty(), "touching an item should fire ItemPickupEvent, but none fired");
        ItemPickupEvent e = heard.get(0);
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(new InventoryHolder.Entity(alice.player()), e.getDestination(), "the pickup's destination");
        t.assertTrue(item.isAlive() && item.getItem().getCount() == 2, "a refused pickup should leave the 2 diamonds "
                + "on the ground, but the item entity " + (item.isAlive() ? "holds " + describe(item.getItem())
                : "is gone"));
        t.assertInventory(alice, Items.DIAMOND, 0);
    }

    @HadronTest
    void uncancelledPickupPutsTheItemInTheInventory(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemEntity item = dropAt(t, t.pos(0, 0, 0), new ItemStack(Items.DIAMOND, 2));
        t.await("alice to pick up the diamonds", () -> !item.isAlive(), 60);
        t.assertInventory(alice, Items.DIAMOND, 2);
    }

    @HadronTest
    void refusedHopperPickupLeavesTheItemAndNamesTheHopper(TestContext t) {
        Support.watch(t);
        BlockPos hopper = t.pos(4, 0, 0);
        t.setBlock(hopper, Blocks.HOPPER);
        List<ItemPickupEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemPickupEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        ItemEntity item = dropAt(t, hopper.above(), new ItemStack(Items.DIRT, 4));
        t.await("the hopper to try to take the item", () -> !heard.isEmpty(), 60);
        t.tick(5);
        t.assertTrue(!heard.isEmpty(), "a hopper under an item should fire ItemPickupEvent, but none fired");
        assertMachineActor(t, heard.get(0).getActor(), hopper);
        t.assertTrue(item.isAlive() && item.getItem().getCount() == 4, "a refused hopper pickup should leave the "
                + "item, but " + (item.isAlive() ? "it holds " + describe(item.getItem()) : "it is gone"));
        t.assertTrue(((HopperBlockEntity) t.level().getBlockEntity(hopper)).isEmpty(),
                "the hopper should stay empty after a refused pickup");
    }

    @HadronTest
    void refusedDespawnKeepsTheItemByNature(TestContext t) {
        Support.watch(t);
        List<ItemDespawnEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDespawnEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        ItemEntity item = dropAt(t, t.pos(6, 0, 6), new ItemStack(Items.DIRT));
        item.setPickUpDelay(32767);
        // Five seconds from despawning (6000 ticks). The test classpath is vanilla, so NeoForge's lifespan field
        // isn't visible here; the age is set the way a save file would.
        try {
            java.lang.reflect.Field age = ItemEntity.class.getDeclaredField("age");
            age.setAccessible(true);
            age.setInt(item, 6000 - 10);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Couldn't age the item entity: " + e, e);
        }
        t.await("the item to reach its lifespan", () -> !heard.isEmpty(), 60);
        t.tick(2);
        t.assertTrue(!heard.isEmpty(), "an item past its lifespan should fire ItemDespawnEvent, but none fired");
        assertNatureActor(t, heard.get(0).getActor());
        t.assertTrue(item.isAlive(), "a refused despawn should keep the item, but it is gone");
    }

    @HadronTest
    void refusedMergeKeepsBothStacksApart(TestContext t) {
        Support.watch(t);
        List<ItemMergeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemMergeEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        ItemEntity a = dropAt(t, t.pos(6, 0, 6), new ItemStack(Items.DIRT, 3));
        ItemEntity b = dropAt(t, t.pos(6, 0, 6), new ItemStack(Items.DIRT, 4));
        a.setPickUpDelay(1000); // not 32767: vanilla never merges an item with that ("never pick up") delay
        b.setPickUpDelay(1000);
        t.await("the two stacks to try to merge", () -> !heard.isEmpty(), 120); // items look for merges every 40 ticks
        t.tick(2);
        t.assertTrue(!heard.isEmpty(), "two dirt stacks side by side should fire ItemMergeEvent, but none fired");
        assertNatureActor(t, heard.get(0).getActor());
        t.assertTrue(a.isAlive() && b.isAlive() && a.getItem().getCount() == 3 && b.getItem().getCount() == 4,
                "a refused merge should leave 3 and 4, but left " + (a.isAlive() ? describe(a.getItem()) : "none")
                        + " and " + (b.isAlive() ? describe(b.getItem()) : "none"));
    }
}
