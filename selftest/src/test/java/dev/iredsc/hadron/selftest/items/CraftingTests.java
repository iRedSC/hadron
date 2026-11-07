package dev.iredsc.hadron.selftest.items;

import static dev.iredsc.hadron.selftest.items.Support.assertMachineActor;
import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;
import static dev.iredsc.hadron.selftest.items.Support.one;
import static dev.iredsc.hadron.selftest.items.Support.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemEnchantEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.PrepareAnvilEvent;
import dev.iredsc.hadron.api.event.item.PrepareItemCraftEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;

/** Crafting grids, anvils, the enchanting table, furnaces and trades. */
public final class CraftingTests {
    private static final int GRID_SLOT = InventoryMenu.CRAFT_SLOT_START;
    private static final int HOTBAR = InventoryMenu.USE_ROW_SLOT_START;
    /** In a menu over a 3-slot block (anvil, merchant): main inventory 3 to 29, hotbar 30 to 38. */
    private static final int THREE_SLOT_HOTBAR = 30;

    /** Puts the logs held in hotbar slot 0 into the 2x2 grid, as a player does: pick up, put down. */
    private static void logsIntoGrid(TestContext t, TestPlayer alice, int logs) {
        alice.hold(new ItemStack(Items.OAK_LOG, logs));
        alice.clickSlot(HOTBAR);
        alice.clickSlot(GRID_SLOT);
        t.assertEquals(logs, alice.player().inventoryMenu.getSlot(GRID_SLOT).getItem().getCount(), "logs in the grid");
    }

    /** What the server told alice's client the 2x2 grid's result slot holds, in order. */
    private static List<String> sentResults(TestPlayer alice) {
        return alice.received(net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket.class).stream()
                .filter(p -> p.getContainerId() == 0 && p.getSlot() == InventoryMenu.RESULT_SLOT)
                .map(p -> describe(p.getItem())).toList();
    }

    @HadronTest
    void cancelledCraftLeavesTheIngredientsAndMakesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemCraftEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemCraftEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        logsIntoGrid(t, alice, 1);
        alice.clickSlot(InventoryMenu.RESULT_SLOT);
        ItemCraftEvent e = one(t, heard, "ItemCraftEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(e.getResult().is(Items.OAK_PLANKS) && e.getResult().getCount() == 4, "the craft's result should "
                + "be 4 oak planks, but was " + describe(e.getResult()));
        InventoryMenu menu = alice.player().inventoryMenu;
        t.assertEquals(1, menu.getSlot(GRID_SLOT).getItem().getCount(), "logs left in the grid");
        t.assertTrue(menu.getCarried().isEmpty(), "the cursor should be empty, but holds "
                + describe(menu.getCarried()));
        t.assertInventory(alice, Items.OAK_PLANKS, 0);
    }

    @HadronTest
    void shiftClickCraftStopsWhereCancelledAndMakesExactlyThatMany(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemCraftEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemCraftEvent e) {
                heard.add(e);
                if (heard.size() == 3) e.setCancelled(true); // two crafts happen; the third is refused
            }
        });
        logsIntoGrid(t, alice, 5);
        alice.clickSlot(InventoryMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE);
        t.assertEquals(3, heard.size(), "ItemCraftEvents fired before the batch stopped");
        t.assertInventory(alice, Items.OAK_PLANKS, 8);
        t.assertEquals(3, alice.player().inventoryMenu.getSlot(GRID_SLOT).getItem().getCount(),
                "logs left in the grid after two crafts");
    }

    @HadronTest
    void preparedCraftResultIsWhatTheGridOffersAndGives(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<PrepareItemCraftEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PrepareItemCraftEvent e) {
                heard.add(e);
                if (e.getMatrix().stream().anyMatch(s -> s.is(Items.OAK_LOG))) e.setResult(new ItemStack(Items.DIAMOND));
            }
        });
        logsIntoGrid(t, alice, 1);
        t.assertTrue(!heard.isEmpty(), "filling the grid should fire PrepareItemCraftEvent, but none fired");
        assertPlayerActor(t, heard.get(heard.size() - 1).getActor(), alice);
        InventoryMenu menu = alice.player().inventoryMenu;
        t.assertTrue(menu.getSlot(InventoryMenu.RESULT_SLOT).getItem().is(Items.DIAMOND), "the grid should offer "
                + "the diamond the listener set, but offers " + describe(menu.getSlot(InventoryMenu.RESULT_SLOT)
                .getItem()) + "; events: " + heard.size() + ", last result " + describe(heard.get(heard.size() - 1)
                .getResult()) + ", result slots sent to alice: " + sentResults(alice));
        alice.clickSlot(InventoryMenu.RESULT_SLOT);
        t.assertTrue(menu.getCarried().is(Items.DIAMOND), "taking the result should give the diamond, but the "
                + "cursor holds " + describe(menu.getCarried()));
        t.assertTrue(menu.getSlot(GRID_SLOT).getItem().isEmpty(), "the log should be used up, but the grid holds "
                + describe(menu.getSlot(GRID_SLOT).getItem()));
    }

    @HadronTest
    void preparedCraftSetEmptyForbidsTheRecipe(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PrepareItemCraftEvent e) {
                e.setResult(ItemStack.EMPTY);
            }
        });
        logsIntoGrid(t, alice, 1);
        InventoryMenu menu = alice.player().inventoryMenu;
        t.assertTrue(menu.getSlot(InventoryMenu.RESULT_SLOT).getItem().isEmpty(), "a forbidden recipe should offer "
                + "nothing, but offers " + describe(menu.getSlot(InventoryMenu.RESULT_SLOT).getItem()));
        alice.clickSlot(InventoryMenu.RESULT_SLOT);
        t.assertEquals(1, menu.getSlot(GRID_SLOT).getItem().getCount(), "logs left in the grid");
        t.assertInventory(alice, Items.OAK_PLANKS, 0);
    }

    @HadronTest
    void forbiddenCrafterResultCraftsNothingAndKeepsTheIngredients(TestContext t) {
        Support.watch(t);
        BlockPos crafter = t.pos(2, 0, 3);
        t.setBlock(crafter, Blocks.CRAFTER);
        CrafterBlockEntity be = (CrafterBlockEntity) t.level().getBlockEntity(crafter);
        be.setItem(0, new ItemStack(Items.OAK_LOG));
        List<PrepareItemCraftEvent> heard = new ArrayList<>();
        List<ItemCraftEvent> crafts = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PrepareItemCraftEvent e) {
                heard.add(e);
                e.setResult(ItemStack.EMPTY);
            }

            @EventHandler
            void on(ItemCraftEvent e) {
                crafts.add(e);
            }
        });
        t.setBlock(crafter.south(), Blocks.REDSTONE_BLOCK);
        t.await("the crafter to fire", () -> !heard.isEmpty(), 60);
        t.tick(2);
        PrepareItemCraftEvent e = one(t, heard, "PrepareItemCraftEvent");
        assertMachineActor(t, e.getActor(), crafter);
        t.assertEquals(0, crafts.size(), "ItemCraftEvents for a forbidden result");
        t.assertEquals(1, be.getItem(0).getCount(), "logs left in the crafter");
        t.assertEquals(0, Support.itemsNear(t, crafter, 3).size(), "items the crafter put out");
    }

    /** Opens an anvil with a worn iron sword on the left and an iron ingot on the right. */
    private static AnvilMenu openAnvilWithWornSword(TestContext t, TestPlayer alice) {
        BlockPos anvil = t.pos(1, 0, 0);
        t.setBlock(anvil, Blocks.ANVIL);
        alice.player().giveExperienceLevels(10);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(200);
        alice.player().getInventory().setItem(0, sword);
        alice.player().getInventory().setItem(1, new ItemStack(Items.IRON_INGOT));
        alice.useBlock(anvil);
        t.assertTrue(alice.player().containerMenu instanceof AnvilMenu, "right-clicking the anvil should open it, "
                + "but the open menu is " + alice.player().containerMenu);
        alice.clickSlot(THREE_SLOT_HOTBAR);
        alice.clickSlot(0);
        alice.clickSlot(THREE_SLOT_HOTBAR + 1);
        alice.clickSlot(1);
        return (AnvilMenu) alice.player().containerMenu;
    }

    @HadronTest
    void preparedAnvilResultAndCostAreWhatThePlayerGetsAndPays(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<PrepareAnvilEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PrepareAnvilEvent e) {
                heard.add(e);
                e.setResult(new ItemStack(Items.DIAMOND_SWORD));
                e.setRepairCost(3);
            }
        });
        AnvilMenu menu = openAnvilWithWornSword(t, alice);
        t.assertTrue(!heard.isEmpty(), "filling the anvil should fire PrepareAnvilEvent, but none fired");
        assertPlayerActor(t, heard.get(heard.size() - 1).getActor(), alice);
        t.assertTrue(menu.getSlot(2).getItem().is(Items.DIAMOND_SWORD), "the anvil should offer the listener's "
                + "diamond sword, but offers " + describe(menu.getSlot(2).getItem()));
        t.assertEquals(3, menu.getCost(), "the anvil's cost");
        alice.clickSlot(2);
        t.assertTrue(menu.getCarried().is(Items.DIAMOND_SWORD), "taking the result should give the diamond sword, "
                + "but the cursor holds " + describe(menu.getCarried()));
        t.assertEquals(7, alice.player().experienceLevel, "alice's levels after paying 3 of 10");
    }

    @HadronTest
    void cancelledPrepareAnvilOffersNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PrepareAnvilEvent e) {
                e.setCancelled(true);
            }
        });
        AnvilMenu menu = openAnvilWithWornSword(t, alice);
        t.assertTrue(menu.getSlot(2).getItem().isEmpty(), "a cancelled PrepareAnvilEvent should offer nothing, but "
                + "the anvil offers " + describe(menu.getSlot(2).getItem()));
        alice.clickSlot(2);
        t.assertTrue(menu.getCarried().isEmpty(), "nothing should be taken");
        t.assertEquals(10, alice.player().experienceLevel, "alice's levels");
    }

    @HadronTest
    void cancelledAnvilRepairSpendsNothingAndKeepsBothInputs(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemChangeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemChangeEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        AnvilMenu menu = openAnvilWithWornSword(t, alice);
        t.assertTrue(!menu.getSlot(2).getItem().isEmpty(), "the anvil should offer a repaired sword");
        alice.clickSlot(2);
        t.assertTrue(!heard.isEmpty(), "taking an anvil result should fire ItemChangeEvent, but none fired");
        assertPlayerActor(t, heard.get(0).getActor(), alice);
        t.assertTrue(menu.getCarried().isEmpty(), "a refused repair should give nothing, but the cursor holds "
                + describe(menu.getCarried()));
        t.assertEquals(200, menu.getSlot(0).getItem().getDamageValue(), "the worn sword's damage, still in the anvil");
        t.assertTrue(menu.getSlot(1).getItem().is(Items.IRON_INGOT), "the ingot should stay in the anvil");
        t.assertEquals(10, alice.player().experienceLevel, "alice's levels");
    }

    @HadronTest
    void cancelledEnchantSpendsNoLapisNorLevels(TestContext t) {
        // The harness has no enchant-button packet yet; this calls what the server's packet handler calls for it.
        TestPlayer alice = t.player("alice");
        BlockPos table = t.pos(1, 0, 0);
        t.setBlock(table, Blocks.ENCHANTING_TABLE);
        alice.player().giveExperienceLevels(30);
        alice.player().getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        alice.player().getInventory().setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
        List<ItemEnchantEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemEnchantEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.useBlock(table);
        t.assertTrue(alice.player().containerMenu instanceof EnchantmentMenu, "the enchanting table should open");
        EnchantmentMenu menu = (EnchantmentMenu) alice.player().containerMenu;
        int hotbar = 29; // two table slots, then the main inventory 2 to 28
        alice.clickSlot(hotbar);
        alice.clickSlot(0);
        alice.clickSlot(hotbar + 1);
        alice.clickSlot(1);
        t.assertTrue(menu.costs[0] > 0, "the table should offer a first enchantment for the sword");
        menu.clickMenuButton(alice.player(), 0);
        ItemEnchantEvent e = one(t, heard, "ItemEnchantEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(1, e.getLevelCost(), "the levels the first option costs");
        t.assertTrue(!e.getEnchantmentsAfter().isEmpty(), "the event should say which enchantments would be added");
        t.assertTrue(menu.getSlot(0).getItem().getEnchantments().isEmpty(), "a refused enchant should leave the "
                + "sword plain");
        t.assertEquals(3, menu.getSlot(1).getItem().getCount(), "lapis left");
        t.assertEquals(30, alice.player().experienceLevel, "alice's levels");
    }

    @HadronTest(timeout = 800)
    void cancelledSmeltLeavesTheOreAndNamesTheFurnace(TestContext t) {
        Support.watch(t);
        BlockPos furnace = t.pos(2, 0, 0);
        t.setBlock(furnace, Blocks.FURNACE);
        AbstractFurnaceBlockEntity be = (AbstractFurnaceBlockEntity) t.level().getBlockEntity(furnace);
        be.setItem(0, new ItemStack(Items.RAW_IRON));
        be.setItem(1, new ItemStack(Items.COAL));
        List<ItemSmeltEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemSmeltEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        t.await("the furnace to finish its first smelt", () -> !heard.isEmpty(), 320);
        assertMachineActor(t, heard.get(0).getActor(), furnace);
        t.assertTrue(heard.get(0).getResult().is(Items.IRON_INGOT), "the smelt's result should be an iron ingot");
        t.tick(2);
        t.assertTrue(be.getItem(0).is(Items.RAW_IRON), "the raw iron should stay, but the input slot holds "
                + describe(be.getItem(0)));
        t.assertTrue(be.getItem(2).isEmpty(), "nothing should be made, but the output holds " + describe(be.getItem(2)));
    }

    @HadronTest
    void cancelledTradeKeepsTheEmeraldsAndGivesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        Villager villager = EntityType.VILLAGER.create(t.level());
        villager.setNoAi(true);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FARMER).setLevel(1));
        MerchantOffers offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 1), new ItemStack(Items.BREAD, 2), 10, 1, 0.05f));
        villager.setOffers(offers);
        spawn(t, villager, t.pos(1, 0, 0));
        Support.awaitLoaded(t, villager);
        alice.hold(new ItemStack(Items.EMERALD, 5));
        List<TradeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(TradeEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.interact(villager);
        t.assertTrue(alice.player().containerMenu instanceof MerchantMenu, "right-clicking the villager should open "
                + "trading, but the open menu is " + alice.player().containerMenu);
        AbstractContainerMenu menu = alice.player().containerMenu;
        alice.clickSlot(THREE_SLOT_HOTBAR);
        alice.clickSlot(0);
        t.assertTrue(menu.getSlot(2).getItem().is(Items.BREAD), "the trade should offer bread, but offers "
                + describe(menu.getSlot(2).getItem()));
        alice.clickSlot(2);
        TradeEvent e = one(t, heard, "TradeEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(menu.getCarried().isEmpty(), "a refused trade should give nothing, but the cursor holds "
                + describe(menu.getCarried()));
        t.assertEquals(5, menu.getSlot(0).getItem().getCount(), "emeralds still in the payment slot");
        t.assertEquals(0, offers.get(0).getUses(), "the offer's uses");
    }
}
