package dev.iredsc.hadron.selftest.inventories;

import static dev.iredsc.hadron.selftest.items.Support.assertMachineActor;
import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;
import static dev.iredsc.hadron.selftest.items.Support.one;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Place;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import dev.iredsc.hadron.api.inventory.PluginContainer;
import dev.iredsc.hadron.api.event.inventory.MenuSlots;
import dev.iredsc.hadron.api.data.NamespacedKey;
import dev.iredsc.hadron.api.Hadron;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryCloseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.test.Covers;
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
    @Covers(InventoryOpenEvent.class)
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
    @Covers(value = Hadron.class, method = "createInventory")
    @Covers(value = PluginContainer.class, method = "open")
    @Covers(value = PluginContainer.class, method = "getHolder")
    @Covers(value = PluginContainer.class, method = "getId")
    @Covers(value = PluginContainer.class, method = "getPlugin")
    @Covers(value = PluginContainer.class, method = "getRows")
    void aPluginsContainerOpensAndClicksAsThePluginsOwn(TestContext t) {
        TestPlayer alice = t.player("alice");
        PluginContainer shop = Hadron.createInventory(t.plugin(), "shop", 2);
        NamespacedKey id = new NamespacedKey(t.plugin(), "shop");
        t.assertEquals(id, shop.getId(), "the container's id, in the plugin's namespace");
        t.assertTrue(shop.getPlugin() == t.plugin(), "the container's plugin should be the one that made it");
        t.assertEquals(2, shop.getRows(), "rows");
        t.assertEquals(18, shop.getContainerSize(), "two rows of nine slots");
        shop.setItem(0, new ItemStack(Items.DIAMOND, 2));
        List<InventoryOpenEvent> opens = new ArrayList<>();
        List<InventoryClickEvent> clicks = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryOpenEvent e) {
                opens.add(e);
            }

            @EventHandler
            void on(InventoryClickEvent e) {
                clicks.add(e);
            }
        });
        t.assertTrue(shop.open(alice.player(), Component.literal("Shop")), "nothing refused the open");
        InventoryOpenEvent open = one(t, opens, "InventoryOpenEvent");
        InventoryHolder.Plugin holder = new InventoryHolder.Plugin(id);
        t.assertEquals(holder, open.getHolder(), "the opened container's holder");
        t.assertEquals(holder, shop.getHolder(), "the container's own holder");
        Place own = Place.Nowhere.pluginContainer(holder);
        t.assertTrue(open.getPlaces().contains(own), "a plugin's own container is placed as the plugin's, for "
                + "protection to let through, but the open's places are " + open.getPlaces());
        for (Place place : open.getPlaces()) {
            t.assertTrue(place.equals(own) || place instanceof Place.Spot, "the open names only the shop and where "
                    + "alice's own slots are, but also " + place);
        }
        assertPluginSlots(t, open.getHolderSlots(), open.getViewerSlots(), shop, alice);

        alice.clickSlot(0);
        alice.clickSlot(18);
        t.assertEquals(2, clicks.size(), "InventoryClickEvents fired");
        t.assertEquals(holder, clicks.get(0).getHolder(), "the clicked shop slot's holder");
        t.assertFalse(clicks.get(0).isViewerSlot(), "slot 0 is the shop's");
        t.assertTrue(clicks.get(1).isViewerSlot(), "slot 18 is alice's own inventory");
        t.assertEquals(18, clicks.get(1).getHolderSlots().size(), "the shop's slots, seen from a click");
    }

    private static void assertPluginSlots(TestContext t, List<Slot> holderSlots, List<Slot> viewerSlots,
            PluginContainer shop, TestPlayer viewer) {
        t.assertEquals(18, holderSlots.size(), "the shop's slots");
        for (Slot slot : holderSlots) {
            t.assertTrue(slot.container == shop, "every holder slot should be the shop's, but one is "
                    + slot.container);
        }
        t.assertEquals(36, viewerSlots.size(), "alice's main inventory and hotbar");
        for (Slot slot : viewerSlots) {
            t.assertTrue(slot.container == viewer.player().getInventory(), "every viewer slot should be alice's, but "
                    + "one is " + slot.container);
        }
    }

    @HadronTest
    @Covers(value = PluginContainer.class, method = "open")
    void aRefusedOpenOfAPluginsContainerSaysSo(TestContext t) {
        TestPlayer alice = t.player("alice");
        PluginContainer shop = Hadron.createInventory(t.plugin(), "shop", 1);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryOpenEvent e) {
                e.setCancelled(true);
            }
        });
        t.assertFalse(shop.open(alice.player(), Component.literal("Shop")), "a refused open should return false");
        t.assertTrue(alice.player().containerMenu == alice.player().inventoryMenu, "alice should have nothing open");
    }

    @HadronTest
    @Covers(value = MenuSlots.class, method = "viewerSlots")
    @Covers(value = MenuSlots.class, method = "holderSlots")
    @Covers(value = MenuSlots.class, method = "isViewers")
    void aChestsSlotsSplitIntoTheChestsAndTheViewers(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        ChestBlockEntity chest = chest(t, pos);
        List<InventoryOpenEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryOpenEvent e) {
                heard.add(e);
            }
        });
        alice.useBlock(pos);
        InventoryOpenEvent e = one(t, heard, "InventoryOpenEvent");
        t.assertEquals(27, e.getHolderSlots().size(), "the chest's slots");
        for (Slot slot : e.getHolderSlots()) {
            t.assertTrue(slot.container == chest, "every holder slot should be the chest's, but one is "
                    + slot.container);
        }
        t.assertEquals(36, e.getViewerSlots().size(), "alice's main inventory and hotbar");
        AbstractContainerMenu menu = alice.player().containerMenu;
        t.assertEquals(e.getViewerSlots(), MenuSlots.viewerSlots(menu, alice.player()), "the same split, asked directly");
        t.assertEquals(e.getHolderSlots(), MenuSlots.holderSlots(menu, alice.player()), "the same split, asked directly");
        t.assertTrue(MenuSlots.isViewers(menu.getSlot(27), alice.player()), "slot 27 is alice's first main slot");
        t.assertFalse(MenuSlots.isViewers(menu.getSlot(0), alice.player()), "slot 0 is the chest's");
    }

    /**
     * A mod's menu that shows the player's inventory through NeoForge's item handlers, as many do. Built by reflection
     * because self-tests compile against Hadron's API only; this is the one port, so the classes must be there.
     */
    @HadronTest
    @Covers(value = MenuSlots.class, method = "isViewers")
    void aModsMenuShowingThePlayersInventoryThroughItemHandlersCountsAsTheViewers(TestContext t) throws Exception {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        ChestBlockEntity chest = chest(t, t.pos(1, 0, 0));
        Class<?> itemHandler = Class.forName("net.neoforged.neoforge.items.IItemHandler");
        Class<?> slotItemHandler = Class.forName("net.neoforged.neoforge.items.SlotItemHandler");
        Object players = Class.forName("net.neoforged.neoforge.items.wrapper.PlayerInvWrapper")
                .getConstructor(net.minecraft.world.entity.player.Inventory.class)
                .newInstance(alice.player().getInventory());
        Object chests = Class.forName("net.neoforged.neoforge.items.wrapper.InvWrapper")
                .getConstructor(net.minecraft.world.Container.class).newInstance(chest);
        var newSlot = slotItemHandler.getConstructor(itemHandler, int.class, int.class, int.class);
        // 0 main, 36 armour, 40 offhand: one through each of PlayerInvWrapper's three ranged parts.
        for (int index : new int[] {0, 36, 40}) {
            Slot slot = (Slot) newSlot.newInstance(players, index, 0, 0);
            t.assertTrue(MenuSlots.isViewers(slot, alice.player()), "slot " + index + " of a PlayerInvWrapper over "
                    + "alice's inventory is alice's");
            t.assertFalse(MenuSlots.isViewers(slot, bob.player()), "slot " + index + " of alice's inventory isn't bob's");
        }
        Slot chestSlot = (Slot) newSlot.newInstance(chests, 0, 0, 0);
        t.assertFalse(MenuSlots.isViewers(chestSlot, alice.player()), "a chest's slot through an InvWrapper is the "
                + "chest's");
    }

    @HadronTest
    @Covers(InventoryCloseEvent.class)
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
    @Covers(InventoryClickEvent.class)
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
    @Covers(InventoryMoveItemEvent.class)
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
    @Covers(InventoryMoveItemEvent.class)
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
