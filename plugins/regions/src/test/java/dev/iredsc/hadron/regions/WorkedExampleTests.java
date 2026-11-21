package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.RegionsTests.admin;
import static dev.iredsc.hadron.regions.RegionsTests.define;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/** The design's worked examples, each as it happens in a running server. */
public final class WorkedExampleTests {
    private static <M extends Mob> M spawn(TestContext t, EntityType<M> type, BlockPos at) {
        M mob = type.create(t.level());
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        t.level().addFreshEntity(mob);
        return mob;
    }

    /** A fresh region, as /region define makes one, for boxes too big to pick corners of from where players stand. */
    static void put(TestContext t, String id, BlockPos a, BlockPos b) {
        ((RegionsPlugin) t.plugin()).regions().put(Region.of(id, t.level().dimension().location(),
                net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(a, b)));
    }

    /** What the player was shown above the hotbar, oldest first. */
    static List<String> actionBar(TestPlayer p) {
        return p.received(ClientboundSystemChatPacket.class).stream().filter(ClientboundSystemChatPacket::overlay)
                .map(m -> m.content().getString()).toList();
    }

    private static void fire(TestContext t, dev.iredsc.hadron.api.event.Event e) {
        t.plugin().getServer().getPluginManager().callEvent(e);
    }

    /** 1. A hopper nobody owns is the world: inside base it works, outside it can't feed a chest inside. */
    @HadronTest
    void aHopperOutsideCantFeedAChestInsideUntilTheRegionLetsTheWorldMoveItems(TestContext t) {
        TestPlayer alice = admin(t);
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        define(t, alice, "base", t.pos(3, 0, -1), t.pos(5, 2, 1));
        alice.command("rg watch");
        t.assertChat(alice, "Watching refusals within 32 blocks");
        BlockPos hopper = t.pos(2, 0, 0);
        t.setBlock(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        ((HopperBlockEntity) t.level().getBlockEntity(hopper)).setItem(0, new ItemStack(Items.DIAMOND, 4));
        t.tick(40);
        t.assertTrue(((ChestBlockEntity) t.level().getBlockEntity(chest)).isEmpty(),
                "a hopper outside base should not feed the chest inside");
        t.assertTrue(actionBar(alice).contains("base · world · inventory.move · from outside"),
                "alice watches base, so she should see why, but saw " + actionBar(alice));
        alice.command("rg allow base world inventory.move");
        t.tick(40);
        t.assertEquals(4, ((ChestBlockEntity) t.level().getBlockEntity(chest)).getItem(0).getCount(),
                "the diamonds the hopper fed in once base lets the world move items");
    }

    /** 2. A hopper minecart acts for whoever placed it; one nobody placed is the world, judged from its origin. */
    @HadronTest
    void aHopperMinecartActsForWhoPlacedItOrFromWhereItEnteredTheWorld(TestContext t) {
        TestPlayer alice = admin(t);
        BlockPos chest = t.pos(3, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        define(t, alice, "base", t.pos(2, 0, -1), t.pos(4, 2, 1));
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember base carol");
        MinecartHopper cart = EntityType.HOPPER_MINECART.create(t.level());
        BlockPos outside = t.pos(-6, 0, 0);
        cart.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
        t.level().addFreshEntity(cart);
        cart.moveTo(chest.getX() + 0.5, chest.getY() - 1, chest.getZ() + 0.5);
        InventoryHolder from = new InventoryHolder.Block(t.level(), chest);
        InventoryHolder into = new InventoryHolder.Entity(cart);
        InventoryMoveItemEvent wild = new InventoryMoveItemEvent(from, into, new ItemStack(Items.DIAMOND), into,
                new Actor.EntityActor(cart, Attribution.none()));
        fire(t, wild);
        t.assertTrue(wild.isCancelled(), "a minecart nobody placed, rolled in from outside, is the world from outside");
        InventoryMoveItemEvent outsiders = new InventoryMoveItemEvent(from, into, new ItemStack(Items.DIAMOND), into,
                new Actor.EntityActor(cart, Attribution.of(bob.uuid())));
        fire(t, outsiders);
        t.assertTrue(outsiders.isCancelled(), "bob's minecart is bob: everyone, and inventory.move is use's");
        InventoryMoveItemEvent members = new InventoryMoveItemEvent(from, into, new ItemStack(Items.DIAMOND), into,
                new Actor.EntityActor(cart, Attribution.of(carol.uuid())));
        fire(t, members);
        t.assertFalse(members.isCancelled(), "carol's minecart is a member's");
        t.assertTrue(Actors.origin(cart) instanceof dev.iredsc.hadron.api.event.Place.Spot,
                "the port should have recorded where the minecart entered the world, but has " + Actors.origin(cart));
    }

    /** 3. A visitor fights a monster in spawn, and the monster, though it wandered in, hits back (a husk: it never burns). */
    @HadronTest
    void aVisitorFightsAMonsterInSpawnAndOneFromOutsideHitsBack(TestContext t) {
        TestPlayer alice = admin(t);
        Husk zombie = spawn(t, EntityType.HUSK, t.pos(-6, 0, 0));
        define(t, alice, "spawn", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        zombie.moveTo(t.pos(1, 0, 1).getX() + 0.5, t.pos(1, 0, 1).getY(), t.pos(1, 0, 1).getZ() + 0.5);
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(zombie);
        t.tick(2);
        t.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "bob's hit should land on the husk in spawn");
        EntityDamageEvent back = new EntityDamageEvent(bob.player(), t.level().damageSources().mobAttack(zombie), 3f,
                new Actor.EntityActor(zombie, Attribution.none()));
        fire(t, back);
        t.assertFalse(back.isCancelled(), "fresh regions carry world: allow [entity.*.player], so it hits bob");
    }

    /** 4. A visitor can't hit a cow in spawn: they see the region's name; an operator sees the decision and [allow]. */
    @HadronTest
    void aVisitorCantHitACowInSpawnAndAnOperatorSeesWhyAndTheFix(TestContext t) {
        TestPlayer alice = admin(t);
        Cow cow = spawn(t, EntityType.COW, t.pos(1, 0, 1));
        define(t, alice, "spawn", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(cow);
        t.tick(2);
        t.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow's health after bob's hit");
        t.assertEquals(List.of("spawn"), actionBar(bob), "what bob is shown");
        alice.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        alice.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        alice.attack(cow);
        t.tick(2);
        t.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow's health after alice's hit");
        t.assertTrue(actionBar(alice).contains("spawn · everyone · entity.hurt.animal (build)"),
                "alice can change spawn, so she should see the decision, but saw " + actionBar(alice));
        t.assertChat(alice, "spawn · everyone · entity.hurt.animal (build) [allow]");
    }

    /** 5. A skeleton outside can't shoot a cow inside, wherever it is when the arrow lands; one inside can. */
    @HadronTest
    void aSkeletonOutsideCantShootACowInsideEvenMovedOrGone(TestContext t) {
        TestPlayer alice = admin(t);
        // Off to the side, so no player stands in the line of fire.
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 4));
        put(t, "spawn", t.pos(1, 0, 2), t.pos(5, 3, 6));
        List<EntityDamageEvent> hits = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            void on(EntityDamageEvent e) {
                if (e.getEntity() == cow) hits.add(e);
            }
        });
        for (String then : List.of("stays", "moves away", "is removed")) {
            Skeleton skeleton = spawn(t, EntityType.SKELETON, t.pos(-3, 0, 4));
            Arrow arrow = shoot(t, skeleton, cow);
            if (then.equals("moves away")) skeleton.moveTo(skeleton.getX() - 40, skeleton.getY(), skeleton.getZ());
            if (then.equals("is removed")) skeleton.discard();
            int before = hits.size();
            t.await("the arrow from a skeleton that " + then + " to reach the cow", () -> hits.size() > before, 40);
            t.assertTrue(hits.get(before).isCancelled(), "the hit of a skeleton outside that " + then
                    + " should be refused: its arrow entered the world outside");
            t.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow's health once the skeleton " + then);
            arrow.discard();
        }
        Skeleton inside = spawn(t, EntityType.SKELETON, t.pos(5, 0, 4));
        shoot(t, inside, cow);
        int before = hits.size();
        t.await("the arrow from a skeleton inside to reach the cow", () -> hits.size() > before, 40);
        t.assertFalse(hits.get(before).isCancelled(), "a skeleton that spawned inside is the region's own world");
        t.assertTrue(cow.getHealth() < cow.getMaxHealth(), "the cow should be hurt by the skeleton inside");
    }

    /** An arrow fired from the skeleton's eyes straight at the middle of {@code target}. */
    private static Arrow shoot(TestContext t, Skeleton from, Entity target) {
        Arrow arrow = new Arrow(t.level(), from, new ItemStack(Items.ARROW), null);
        arrow.shoot(target.getX() - arrow.getX(), target.getY(0.5) - arrow.getY(), target.getZ() - arrow.getZ(), 1.6f,
                0f);
        t.level().addFreshEntity(arrow);
        return arrow;
    }

    /** 6. A mod's furniture is whatever its type's category says: placed by an outsider, it is building. */
    @HadronTest
    void anOutsidersPlacedFurnitureIsBuildingWithoutKnowingTheMod(TestContext t) {
        TestPlayer alice = admin(t);
        define(t, alice, "spawn", t.pos(-2, 0, -2), t.pos(2, 2, 2));
        TestPlayer bob = t.player("bob");
        Interaction chair = EntityType.INTERACTION.create(t.level());
        chair.moveTo(t.pos(1, 0, 1).getX() + 0.5, t.pos(1, 0, 1).getY(), t.pos(1, 0, 1).getZ() + 0.5);
        EntitySpawnEvent placed = new EntitySpawnEvent(chair, EntitySpawnEvent.Cause.PLACED, null,
                new Actor.Player(bob.player()));
        fire(t, placed);
        t.assertTrue(placed.isCancelled(), "entity.place.misc is build's, which spawn denies everyone");
        EntitySpawnEvent members = new EntitySpawnEvent(chair, EntitySpawnEvent.Cause.PLACED, null,
                new Actor.Player(bob.player()));
        alice.command("rg addmember spawn bob");
        fire(t, members);
        t.assertFalse(members.isCancelled(), "a member places furniture");
    }

    /** 7. A plot inside spawn only states what differs. */
    @HadronTest
    void aPlotInsideSpawnDecidesFirstAndPassesDownWhatItDoesntSay(TestContext t) {
        TestPlayer alice = admin(t);
        // Blocks are set before the regions exist: inside one, a change nobody is behind is refused.
        // So is the husk spawned: test code is nobody, and an unknown actor's spawn inside a region is refused.
        Husk zombie = spawn(t, EntityType.HUSK, t.pos(3, 0, 3));
        BlockPos carols = t.pos(2, 1, 2);
        BlockPos bobs = t.pos(3, 1, 2);
        t.setBlock(carols, Blocks.DIRT);
        t.setBlock(bobs, Blocks.DIRT);
        define(t, alice, "plot-1", t.pos(1, 0, 1), t.pos(4, 3, 4));
        put(t, "spawn", t.pos(-6, 0, -6), t.pos(6, 3, 6));
        alice.command("rg priority plot-1 10");
        TestPlayer carol = t.player("carol");
        TestPlayer bob = t.player("bob");
        alice.command("rg addowner plot-1 carol");
        carol.breakBlock(carols);
        t.assertBlock(carols, Blocks.AIR);
        bob.breakBlock(bobs);
        t.assertBlock(bobs, Blocks.DIRT);
        t.assertEquals(List.of("plot-1"), actionBar(bob), "the plot decides first, so it is the one named");
        bob.hold(new ItemStack(Items.DIAMOND_SWORD));
        t.tick(20);
        bob.attack(zombie);
        t.tick(2);
        t.assertTrue(zombie.getHealth() < zombie.getMaxHealth(), "neither region says anything about monsters");
    }
}
