package dev.iredsc.hadron.selftest.items;

import static dev.iredsc.hadron.selftest.items.Support.assertMachineActor;
import static dev.iredsc.hadron.selftest.items.Support.assertPlayerActor;
import static dev.iredsc.hadron.selftest.items.Support.describe;
import static dev.iredsc.hadron.selftest.items.Support.one;
import static dev.iredsc.hadron.selftest.items.Support.spawn;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.item.EquipmentChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemBreakEvent;
import dev.iredsc.hadron.api.event.item.ItemConsumeEvent;
import dev.iredsc.hadron.api.event.item.ItemDamageEvent;
import dev.iredsc.hadron.api.event.item.ItemHeldEvent;
import dev.iredsc.hadron.api.event.item.ItemRepairEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.AABB;

/** Using, eating, wearing out, mending, holding and equipping items. */
public final class ItemUseTests {
    private static final int CHEST_MENU_SLOT = 6;

    @HadronTest
    @Covers(ItemUseEvent.class)
    void refusedUseThrowsNothingAndKeepsTheStack(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.SNOWBALL, 16));
        List<ItemUseEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemUseEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useItem();
        t.tick(2);
        ItemUseEvent e = one(t, heard, "ItemUseEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(InteractionHand.MAIN_HAND, e.getHand(), "the hand used");
        t.assertHolding(alice, Items.SNOWBALL, 16);
        t.assertEquals(0, t.level().getEntitiesOfClass(Snowball.class, new AABB(t.pos(0, 0, 0)).inflate(30)).size(),
                "snowballs thrown after a refused use");
        effects.assertNone("a refused use");
    }

    @HadronTest
    @Covers(ItemConsumeEvent.class)
    void refusedEatingKeepsTheFoodAndTheHunger(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.player().getFoodData().setFoodLevel(10);
        alice.hold(new ItemStack(Items.BREAD, 3));
        List<ItemConsumeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemConsumeEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.useItem();
        t.tick(45); // eating bread takes 32 ticks
        ItemConsumeEvent e = one(t, heard, "ItemConsumeEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(e.getConsumer() == alice.player(), "the consumer should be alice, but was " + e.getConsumer());
        t.assertHolding(alice, Items.BREAD, 3);
        t.assertEquals(10, alice.player().getFoodData().getFoodLevel(), "alice's food level");
    }

    @HadronTest
    @Covers(ItemDamageEvent.class)
    void changedItemDamageIsTheDurabilityLost(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        List<ItemDamageEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDamageEvent e) {
                heard.add(e);
                e.setDamage(5);
            }
        });
        alice.breakBlock(stone);
        ItemDamageEvent e = one(t, heard, "ItemDamageEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(1, e.getBefore().getCount(), "the stack before");
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals(5, alice.player().getMainHandItem().getDamageValue(), "the pickaxe's damage");
    }

    @HadronTest
    @Covers(ItemDamageEvent.class)
    void cancelledItemDamageKeepsDurabilityButTheBlockStillBreaks(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemDamageEvent e) {
                e.setCancelled(true);
            }
        });
        alice.breakBlock(stone);
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the pickaxe's damage");
    }

    @HadronTest
    @Covers(ItemBreakEvent.class)
    void aToolRunningOutFiresItemBreakForItsUser(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        alice.hold(pick);
        List<ItemBreakEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemBreakEvent e) {
                heard.add(e);
            }
        });
        alice.breakBlock(stone);
        ItemBreakEvent e = one(t, heard, "ItemBreakEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(e.getItem().is(Items.IRON_PICKAXE), "the broken item should be the pickaxe, but was "
                + describe(e.getItem()));
        t.assertHolding(alice, Items.IRON_PICKAXE, 0);
    }

    @HadronTest
    @Covers(ItemRepairEvent.class)
    void refusedMendingLeavesTheToolDamaged(TestContext t) {
        TestPlayer alice = t.player("alice");
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.enchant(t.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.MENDING), 1);
        pick.setDamageValue(50);
        alice.hold(pick);
        List<ItemRepairEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemRepairEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        BlockPos at = t.pos(0, 0, 0);
        t.level().addFreshEntity(new ExperienceOrb(t.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 10));
        t.tick(10);
        t.assertTrue(!heard.isEmpty(), "an orb reaching a mending tool should fire ItemRepairEvent, but none fired");
        assertPlayerActor(t, heard.get(0).getActor(), alice);
        t.assertEquals(50, alice.player().getMainHandItem().getDamageValue(), "the pickaxe's damage");
    }

    @HadronTest
    @Covers(ItemHeldEvent.class)
    void refusedHotbarChangeKeepsTheSelectedSlot(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<ItemHeldEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemHeldEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.selectSlot(4);
        ItemHeldEvent e = one(t, heard, "ItemHeldEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(0, e.getPreviousSlot(), "the previous slot");
        t.assertEquals(4, e.getNewSlot(), "the new slot");
        t.assertEquals(0, alice.player().getInventory().selected, "alice's selected slot");
    }

    @HadronTest
    @Covers(EquipEvent.class)
    void refusedRightClickEquipKeepsTheArmourInHand(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.IRON_CHESTPLATE));
        List<EquipEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EquipEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        SideEffects effects = t.watchSideEffects();
        alice.useItem();
        t.tick();
        EquipEvent e = one(t, heard, "EquipEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertEquals(EquipmentSlot.CHEST, e.getSlot().orElse(null), "the slot equipped");
        t.assertHolding(alice, Items.IRON_CHESTPLATE, 1);
        t.assertTrue(alice.player().getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "alice should wear nothing, but "
                + "wears " + describe(alice.player().getItemBySlot(EquipmentSlot.CHEST)));
        effects.assertNone("a refused equip");
    }

    @HadronTest
    @Covers(EquipmentChangeEvent.class)
    void rightClickEquipFiresEquipmentChangeAfterwards(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.IRON_CHESTPLATE));
        List<EquipmentChangeEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EquipmentChangeEvent e) {
                if (e.getSlot().orElse(null) == EquipmentSlot.CHEST) heard.add(e);
            }
        });
        alice.useItem();
        t.tick();
        EquipmentChangeEvent e = one(t, heard, "chest EquipmentChangeEvent");
        assertPlayerActor(t, e.getActor(), alice);
        t.assertTrue(e.getFrom().isEmpty() && e.getTo().is(Items.IRON_CHESTPLATE), "the change should be from "
                + "nothing to the chestplate, but was " + describe(e.getFrom()) + " to " + describe(e.getTo()));
        t.assertTrue(alice.player().getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE),
                "alice should wear the chestplate");
    }

    @HadronTest
    @Covers(EquipEvent.class)
    void refusedArmourSlotClickLeavesTheArmourOnTheCursor(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.IRON_CHESTPLATE));
        List<EquipEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EquipEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        alice.clickSlot(InventoryMenu.USE_ROW_SLOT_START);
        alice.clickSlot(CHEST_MENU_SLOT);
        t.assertTrue(!heard.isEmpty(), "putting armour in the chest slot should fire EquipEvent, but none fired");
        assertPlayerActor(t, heard.get(0).getActor(), alice);
        t.assertTrue(alice.player().getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "a refused armour-slot click "
                + "should leave the chest slot empty, but it holds "
                + describe(alice.player().getItemBySlot(EquipmentSlot.CHEST)));
        ItemStack carried = alice.player().containerMenu.getCarried();
        t.assertTrue(carried.is(Items.IRON_CHESTPLATE) && carried.getCount() == 1, "the chestplate should still be "
                + "on the cursor, but the cursor holds " + describe(carried));
        alice.clickSlot(InventoryMenu.USE_ROW_SLOT_START);
        t.assertHolding(alice, Items.IRON_CHESTPLATE, 1);
    }

    @HadronTest
    @Covers(EquipEvent.class)
    void refusedDispenserEquipLeavesTheHelmetInTheDispenser(TestContext t) {
        Support.watch(t);
        BlockPos dispenser = t.pos(2, 0, 0);
        t.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        DispenserBlockEntity be = (DispenserBlockEntity) t.level().getBlockEntity(dispenser);
        be.setItem(0, new ItemStack(Items.IRON_HELMET));
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND.create(t.level()), t.pos(3, 0, 0));
        List<EquipEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EquipEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        Support.awaitLoaded(t, stand);
        SideEffects effects = t.watchSideEffects();
        t.setBlock(t.pos(2, 0, 1), Blocks.REDSTONE_BLOCK);
        t.await("the dispenser to fire", () -> !heard.isEmpty(), 60);
        t.tick(2);
        effects.assertNone("a refused dispenser equip");
        EquipEvent e = one(t, heard, "EquipEvent");
        assertMachineActor(t, e.getActor(), dispenser);
        t.assertTrue(e.getEntity() == stand, "the entity equipped should be the armor stand, but was " + e.getEntity());
        t.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the stand should wear nothing");
        t.assertTrue(be.getItem(0).is(Items.IRON_HELMET), "the helmet should stay in the dispenser, which holds "
                + describe(be.getItem(0)));
        t.assertEquals(0, Support.itemsNear(t, dispenser, 3).size(), "items thrown out of the dispenser");
    }
}
