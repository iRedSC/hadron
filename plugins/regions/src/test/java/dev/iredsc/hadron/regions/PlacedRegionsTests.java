package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.test.SideEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import net.minecraft.world.entity.animal.Cow;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Inventories, menus and resources whose place is uncertain, a player's own, or reached from afar. */
public final class PlacedRegionsTests {
    /** Stands {@code who} at {@code pos}, well clear of the regions these tests define. */
    private static void standAt(TestPlayer who, BlockPos pos) {
        who.player().teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    @HadronTest
    void aRemoteTerminalOutsideCantReachAChestInside(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        ChestBlockEntity box = (ChestBlockEntity) t.level().getBlockEntity(chest);
        define(t, admin(t), "terminal-reach", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        BlockPos terminal = t.pos(-4, 0, 0);
        standAt(bob, terminal.north());
        // A mod's terminal, open on its own block outside, whose slots show the chest inside: bob shift-clicks one of
        // his own slots, which moves the stack into the chest.
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), box);
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), new InventoryHolder.Entity(bob.player()), menu,
                new InventoryHolder.Block(t.level(), terminal), 30, 0, ClickType.QUICK_MOVE,
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "a click that can move items into a chest inside should be refused");
    }

    @HadronTest
    void aMenuNothingIdentifiesIsRefusedToAnOutsiderWhereverTheyStand(TestContext t) {
        define(t, admin(t), "unplaced-menu", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-6, 0, 0));
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), new SimpleContainer(27));
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), InventoryHolder.Unknown.INSTANCE, menu,
                InventoryHolder.Unknown.INSTANCE, 0, 0, ClickType.PICKUP, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "an inventory nobody can place could be inside, so an outsider is refused");
    }

    @HadronTest
    void anOutsidersPlayerInventoryStaysTheirsInsideARegion(TestContext t) {
        define(t, admin(t), "own-pockets", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(3, 0, 0));
        ServerPlayer viewer = bob.player();
        InventoryClickEvent click = new InventoryClickEvent(viewer, new InventoryHolder.Entity(viewer),
                viewer.inventoryMenu, new InventoryHolder.Entity(viewer), 36, 0, ClickType.QUICK_MOVE,
                new Actor.Player(viewer));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertFalse(click.isCancelled(), "bob's own inventory goes where he goes, so a region doesn't refuse it");
    }

    @HadronTest
    void anOutsidersCableCantDrainABatteryInside(TestContext t) {
        define(t, admin(t), "battery-room", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        ResourceTransferEvent drain = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Block(t.level(), t.pos(3, 0, 0)), new ResourceHolder.Block(t.level(), t.pos(-4, 0, 0)),
                1000);
        t.plugin().getServer().getPluginManager().callEvent(drain);
        t.assertTrue(drain.isCancelled(), "bob's cable should not take energy out of a battery inside");
        ResourceTransferEvent outside = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Block(t.level(), t.pos(-5, 0, 0)), new ResourceHolder.Block(t.level(), t.pos(-4, 0, 0)),
                1000);
        t.plugin().getServer().getPluginManager().callEvent(outside);
        t.assertFalse(outside.isCancelled(), "a transfer between two blocks outside is none of the region's business");
    }

    @HadronTest
    void anOutsiderCantStripAnArmorStandThroughAnEditor(TestContext t) {
        ArmorStand stand = EntityType.ARMOR_STAND.create(t.level());
        BlockPos at = t.pos(3, 0, 0);
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        t.level().addFreshEntity(stand);
        define(t, admin(t), "stand-editor", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        ArmorStandManipulateEvent edit = new ArmorStandManipulateEvent(stand, EquipmentSlot.HEAD, InteractionHand.MAIN_HAND,
                ItemStack.EMPTY, stand.getItemBySlot(EquipmentSlot.HEAD), new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(edit);
        t.assertTrue(edit.isCancelled(), "bob should not take the helmet off a stand inside");
    }

    @HadronTest
    void aPublicParkElsewhereDoesntOpenAVaultToAnUnplacedInventory(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        standAt(alice, t.pos(-7, 0, 3));
        define(t, alice, "park", t.pos(-8, 0, -1), t.pos(-6, 2, 1));
        alice.command("rg priority park 1");
        alice.command("rg flag park use allow");
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-7, 0, 0));
        ChestMenu menu = ChestMenu.threeRows(99, bob.player().getInventory(), new SimpleContainer(27));
        InventoryClickEvent click = new InventoryClickEvent(bob.player(), InventoryHolder.Unknown.INSTANCE, menu,
                InventoryHolder.Unknown.INSTANCE, 0, 0, ClickType.PICKUP, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertTrue(click.isCancelled(), "an inventory nobody can place may be in the vault, which the park, "
                + "though higher, doesn't cover");
    }

    @HadronTest
    void nothingCarriesBetweenTwoInventoriesNobodyCanPlace(TestContext t) {
        define(t, admin(t), "unknown-both", t.pos(2, 0, -1), t.pos(4, 2, 1));
        InventoryMoveItemEvent pipe = new InventoryMoveItemEvent(InventoryHolder.Unknown.INSTANCE, 0,
                new ItemStack(Items.DIAMOND, 8), InventoryHolder.Unknown.INSTANCE, new ItemStack(Items.DIAMOND),
                InventoryHolder.Unknown.INSTANCE, Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(pipe);
        t.assertTrue(pipe.isCancelled(), "either end may be in the region and the other outside it");
    }

    @HadronTest
    void aHopperNobodyOwnsCantEmptyAChestInsideIntoAPlayerOutside(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        define(t, admin(t), "player-sink", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-4, 0, 0));
        InventoryHolder.Block source = new InventoryHolder.Block(t.level(), chest);
        InventoryMoveItemEvent pipe = new InventoryMoveItemEvent(source, 0, new ItemStack(Items.DIAMOND, 8),
                new InventoryHolder.Entity(bob.player()), new ItemStack(Items.DIAMOND), source, Actor.Nature.INSTANCE);
        t.plugin().getServer().getPluginManager().callEvent(pipe);
        t.assertTrue(pipe.isCancelled(), "items should not leave the region for bob's pockets outside");
        ResourceTransferEvent drain = new ResourceTransferEvent(Actor.Nature.INSTANCE, Resource.ENERGY,
                new ResourceHolder.Block(t.level(), chest), new ResourceHolder.Entity(bob.player()), 1000);
        t.plugin().getServer().getPluginManager().callEvent(drain);
        t.assertTrue(drain.isCancelled(), "energy should not leave the region for bob outside");
    }

    @HadronTest
    void aBackpackAnOutsiderCarriesStaysTheirsInsideARegion(TestContext t) {
        define(t, admin(t), "own-backpack", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(3, 0, 0));
        ServerPlayer viewer = bob.player();
        InventoryHolder backpack = new InventoryHolder.Item(new InventoryHolder.Entity(viewer),
                new ItemStack(Items.BUNDLE));
        InventoryClickEvent click = new InventoryClickEvent(viewer, backpack, viewer.inventoryMenu,
                new InventoryHolder.Entity(viewer), 36, 0, ClickType.PICKUP, new Actor.Player(viewer));
        t.plugin().getServer().getPluginManager().callEvent(click);
        t.assertFalse(click.isCancelled(), "what bob carries goes where he goes, as his pockets do");
    }

    @HadronTest
    void anOutsiderCantSpendOrPickUpIntoAChestInside(TestContext t) {
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        define(t, admin(t), "item-vault", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        InventoryHolder.Block box = new InventoryHolder.Block(t.level(), chest);
        ItemChangeEvent spend = new ItemChangeEvent(t.level(), box, new ItemStack(Items.DIAMOND, 8), ItemStack.EMPTY,
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(spend);
        t.assertTrue(spend.isCancelled(), "bob should not use up the diamonds in a chest inside");
        ItemEntity loose = new ItemEntity(t.level(), chest.getX() - 4.5, chest.getY(), chest.getZ() + 0.5,
                new ItemStack(Items.DIRT));
        ItemPickupEvent pickup = new ItemPickupEvent(t.level(), loose, box, new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(pickup);
        t.assertTrue(pickup.isCancelled(), "bob's pickup should not land in a chest inside");
        ItemPickupEvent own = new ItemPickupEvent(t.level(), loose, new InventoryHolder.Entity(bob.player()),
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(own);
        t.assertFalse(own.isCancelled(), "picking up into his own pockets is bob's business");
    }

    @HadronTest
    void aDispenserOutsideCantDressAnArmorStandInside(TestContext t) {
        BlockPos at = t.pos(2, 0, 0);
        ArmorStand stand = EntityType.ARMOR_STAND.create(t.level());
        stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        t.level().addFreshEntity(stand);
        define(t, admin(t), "stand-dispenser", t.pos(2, 0, -1), t.pos(4, 2, 1));
        BlockPos dispenser = t.pos(1, 0, 0);
        t.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        DispenserBlockEntity box = (DispenserBlockEntity) t.level().getBlockEntity(dispenser);
        box.setItem(0, new ItemStack(Items.CARVED_PUMPKIN));
        SideEffects effects = t.watchSideEffects();
        t.setBlock(dispenser.west(), Blocks.REDSTONE_BLOCK);
        t.tick(6);
        t.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the stand inside should stay bare, but wears "
                + stand.getItemBySlot(EquipmentSlot.HEAD));
        t.assertEquals(1, box.getItem(0).getCount(), "the pumpkin left in the dispenser");
        effects.assertNone("a refused dispenser");
    }

    @HadronTest
    void anOutsiderCantHangAFrameOnAWallInsideFromOutside(TestContext t) {
        BlockPos wall = t.pos(2, 0, 0);
        t.setBlock(wall, Blocks.STONE);
        TestPlayer alice = admin(t);
        define(t, alice, "frame-wall", t.pos(2, 0, -1), t.pos(4, 2, 1));
        alice.command("rg flag frame-wall use allow");
        TestPlayer bob = t.player("bob");
        standAt(bob, t.pos(-1, 0, 0));
        bob.hold(new ItemStack(Items.ITEM_FRAME));
        SideEffects effects = t.watchSideEffects();
        bob.useBlock(wall, Direction.WEST);
        t.tick(2);
        t.assertTrue(t.level().getEntitiesOfClass(ItemFrame.class, new AABB(wall).inflate(2)).isEmpty(),
                "no frame should hang on the region's wall");
        t.assertEquals(1, bob.player().getMainHandItem().getCount(), "bob's frame, still in his hand");
        effects.assertNone("a refused frame");
    }

    @HadronTest
    void anOutsiderCantCaptureAMobIntoABlockInside(TestContext t) {
        Cow cow = EntityType.COW.create(t.level());
        BlockPos field = t.pos(-4, 0, 0);
        cow.moveTo(field.getX() + 0.5, field.getY(), field.getZ() + 0.5);
        t.level().addFreshEntity(cow);
        define(t, admin(t), "mob-jar", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        EntityCaptureEvent jar = new EntityCaptureEvent(cow,
                new EntityCaptureEvent.Storage.Block(t.level(), t.pos(3, 0, 0)), new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(jar);
        t.assertTrue(jar.isCancelled(), "bob should not put a cow into a jar inside");
        EntityCaptureEvent carried = new EntityCaptureEvent(cow, new EntityCaptureEvent.Storage.Carried(bob.player()),
                new Actor.Player(bob.player()));
        t.plugin().getServer().getPluginManager().callEvent(carried);
        t.assertFalse(carried.isCancelled(), "carrying a cow from the field is no region's business");
    }
}
