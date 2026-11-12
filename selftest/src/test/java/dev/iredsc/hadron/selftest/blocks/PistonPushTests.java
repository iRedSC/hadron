package dev.iredsc.hadron.selftest.blocks;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/**
 * A piston moves entities on each tick of its travel, after its block move was asked about: each it moves into
 * another block is asked about as it moves, observed, naming the piston (its moving block) as what moved it, with its
 * passengers as rides. Refused, that entity and its passengers stay; the piston's blocks finish moving.
 */
public final class PistonPushTests {
    /** A piston at pos(2, 2, 0) facing east with stone in front, nothing powering it yet; the stone lands at x + 2. */
    private static BlockPos piston(TestContext t) {
        BlockPos piston = t.pos(2, 2, 0);
        Fixtures.awaitTicking(t, piston);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);
        return piston;
    }

    /** A golem floating in the stone's path, its feet {@code below} blocks under the push, at x + 2.8. */
    private static IronGolem golem(TestContext t, BlockPos piston, int below) {
        IronGolem golem = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.IRON_GOLEM, piston.east(2));
        golem.setNoGravity(true);
        golem.setPos(piston.getX() + 2.8, piston.getY() - below, piston.getZ() + 0.5);
        return golem;
    }

    private static List<EntityMoveEvent> hear(TestContext t, BlockPos landing, boolean refuse) {
        List<EntityMoveEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (!(e.getActor() instanceof Actor.Machine m && m.pos().equals(landing))) return;
                heard.add(e);
                if (refuse) e.setCancelled(true);
            }
        });
        return heard;
    }

    private static void power(TestContext t, BlockPos piston) {
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick(5);
    }

    /** A tall golem whose feet are two blocks below the push is still pushed, and asked about by the piston. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aPistonPushingATallMobAsksAboutItsMove(TestContext t) {
        t.player("alice");
        BlockPos piston = piston(t);
        IronGolem golem = golem(t, piston, 2);
        t.tick();
        BlockPos stood = golem.blockPosition();
        List<EntityMoveEvent> heard = hear(t, piston.east(2), false);
        power(t, piston);
        t.assertTrue(!golem.blockPosition().equals(stood), "the piston should push the golem on");
        t.assertTrue(heard.stream().anyMatch(e -> e.getEntity() == golem && e.getFromBlock().equals(stood)
                        && e.getToBlock().equals(golem.blockPosition())),
                "the golem's push should be asked about, from " + stood + " to " + golem.blockPosition() + ": " + heard);
    }

    /** The same push refused: the golem stays where it stood, the stone still lands. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedPushLeavesATallMobWhereItStood(TestContext t) {
        t.player("alice");
        BlockPos piston = piston(t);
        IronGolem golem = golem(t, piston, 2);
        t.tick();
        BlockPos stood = golem.blockPosition();
        List<EntityMoveEvent> refused = hear(t, piston.east(2), true);
        power(t, piston);
        t.assertTrue(!refused.isEmpty(), "the golem's push should have been asked about");
        t.assertEquals(stood, golem.blockPosition(), "the golem's block, its push refused");
        t.assertBlock(piston.east(2), Blocks.STONE);
    }

    /** A golem that steps into the stone's path after the piston started is asked about when it is pushed. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aMobEnteringWhileThePistonTravelsIsAsked(TestContext t) {
        t.player("alice");
        BlockPos piston = piston(t);
        IronGolem golem = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.IRON_GOLEM, t.pos(4, 0, 4));
        golem.setNoGravity(true);
        List<EntityMoveEvent> refused = hear(t, piston.east(2), true);
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick();
        golem.setPos(piston.getX() + 2.8, piston.getY() - 2, piston.getZ() + 0.5);
        BlockPos entered = golem.blockPosition();
        t.tick(4);
        t.assertTrue(refused.stream().anyMatch(e -> e.getEntity() == golem), "the golem stepping in should be asked "
                + "about when pushed: " + refused);
        t.assertEquals(entered, golem.blockPosition(), "the golem's block, its push refused");
    }

    /** A pushed golem carrying two more: one move, naming both riders; refused, all three stay. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aPushedMobsRidersAreItsRidesAndStayWhenRefused(TestContext t) {
        t.player("alice");
        BlockPos piston = piston(t);
        IronGolem root = golem(t, piston, 0);
        IronGolem mid = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.IRON_GOLEM, piston.east(2));
        IronGolem top = dev.iredsc.hadron.selftest.entities.Fixtures.spawn(t, EntityType.IRON_GOLEM, piston.east(2));
        mid.setNoGravity(true);
        top.setNoGravity(true);
        t.assertTrue(mid.startRiding(root, true) && top.startRiding(mid, true), "the golems should stack");
        t.tick(3);
        List<BlockPos> stood = List.of(root.blockPosition(), mid.blockPosition(), top.blockPosition());
        List<EntityMoveEvent> refused = hear(t, piston.east(2), true);
        power(t, piston);
        EntityMoveEvent push = refused.stream().filter(e -> e.getEntity() == root).findFirst().orElse(null);
        t.assertTrue(push != null, "the root golem's push should have been asked about: " + refused);
        t.assertTrue(push.getCarried() instanceof Carried.Known k && k.entities().equals(List.<Entity>of(mid, top)),
                "the push's rides should be the two golems riding, but were " + push.getCarried());
        t.assertEquals(stood, List.of(root.blockPosition(), mid.blockPosition(), top.blockPosition()),
                "each golem's block, the push refused");
    }

    /** A player pushed by a piston: their move names the piston, not themselves. */
    @HadronTest
    @Covers(PlayerMoveEvent.class)
    void aPushedPlayersMoveNamesThePiston(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos piston = piston(t);
        alice.teleport(piston.getX() + 2.8, piston.getY(), piston.getZ() + 0.5);
        t.tick();
        BlockPos stood = alice.player().blockPosition();
        List<EntityMoveEvent> heard = hear(t, piston.east(2), true);
        power(t, piston);
        t.assertTrue(heard.stream().anyMatch(e -> e instanceof PlayerMoveEvent p && p.getPlayer() == alice.player()),
                "alice's push should be a PlayerMoveEvent naming the piston: " + heard);
        t.assertEquals(stood, alice.player().blockPosition(), "alice's block, her push refused");
    }

    /** A loose diamond in the stone's path, at x + 2.8 on the push's row, held still. */
    private static net.minecraft.world.entity.item.ItemEntity diamond(TestContext t, BlockPos piston) {
        net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(t.level(),
                piston.getX() + 2.8, piston.getY(), piston.getZ() + 0.5,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
        item.setNoGravity(true);
        item.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        item.setNeverPickUp();
        t.level().addFreshEntity(item);
        t.tick();
        return item;
    }

    /** A piston on the ground, facing east with {@code front} in front; the item path is at y + 0. */
    private static BlockPos groundPiston(TestContext t, net.minecraft.world.level.block.Block front) {
        BlockPos piston = t.pos(2, 0, 0);
        Fixtures.awaitTicking(t, piston);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), front);
        return piston;
    }

    /** A refused push is never made: the item never touches the pressure plate it would have been pushed onto. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedPushTouchesNothingOnTheWay(TestContext t) {
        t.player("alice");
        BlockPos piston = groundPiston(t, Blocks.STONE);
        BlockPos plate = piston.east(3);
        t.setBlock(plate.below(), Blocks.STONE);
        t.setBlock(plate, Blocks.OAK_PRESSURE_PLATE);
        net.minecraft.world.entity.item.ItemEntity item = diamond(t, piston);
        List<EntityMoveEvent> refused = hearAny(t, item);
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick(5);
        t.assertTrue(!refused.isEmpty(), "the item's push should have been asked about");
        t.assertTrue(!t.getBlock(plate).getValue(net.minecraft.world.level.block.PressurePlateBlock.POWERED),
                "the plate should never have been pressed; the item is at " + item.position());
    }

    /** A refused slime push doesn't launch what it would have pushed. */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedSlimePushLaunchesNothing(TestContext t) {
        t.player("alice");
        BlockPos piston = groundPiston(t, Blocks.SLIME_BLOCK);
        net.minecraft.world.entity.item.ItemEntity item = diamond(t, piston);
        BlockPos was = item.blockPosition();
        List<EntityMoveEvent> refused = hearAny(t, item);
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick(5);
        t.assertTrue(!refused.isEmpty(), "the item's push should have been asked about");
        t.assertEquals(was, item.blockPosition(), "the item's block; its velocity is " + item.getDeltaMovement());
    }

    /**
     * Refused, the item stays where it was, which the arriving stone then fills: vanilla would nudge it out toward
     * the nearest open face, east into the space it was refused; core keeps it pinned while nothing else moves it.
     */
    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRefusedItemTheStoneArrivesOnIsntNudgedIntoTheRefusedSpace(TestContext t) {
        t.player("alice");
        BlockPos piston = groundPiston(t, Blocks.STONE);
        net.minecraft.world.entity.item.ItemEntity item = diamond(t, piston);
        BlockPos was = item.blockPosition();
        List<EntityMoveEvent> refused = hearAny(t, item);
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick(10);
        t.assertTrue(!refused.isEmpty(), "the item's push should have been asked about");
        t.assertBlock(was, Blocks.STONE);
        t.assertEquals(was, item.blockPosition(), "the item's block, ten ticks after the stone arrived on it");
    }

    /** Every move of {@code entity} heard, each refused. */
    private static List<EntityMoveEvent> hearAny(TestContext t, Entity entity) {
        List<EntityMoveEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != entity) return;
                heard.add(e);
                e.setCancelled(true);
            }
        });
        return heard;
    }
}
