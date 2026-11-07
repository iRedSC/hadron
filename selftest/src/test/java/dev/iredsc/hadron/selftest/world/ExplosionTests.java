package dev.iredsc.hadron.selftest.world;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** TNT exploding: the prime (radius and fire) and the explosion (its block and entity lists). */
public final class ExplosionTests {
    /** Primed TNT at pos(0, 0, 0) that goes off on the next tick. */
    private static PrimedTnt tnt(TestContext t) {
        BlockPos at = t.pos(0, 0, 0);
        Console.awaitTicking(t, at);
        PrimedTnt tnt = new PrimedTnt(t.level(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, null);
        tnt.setFuse(1);
        t.level().addFreshEntity(tnt);
        return tnt;
    }

    private static void explode(TestContext t, PrimedTnt tnt) {
        t.await("the TNT to go off", () -> !tnt.isAlive(), 20);
        t.tick();
    }

    private static void assertTheTnt(TestContext t, Actor actor, PrimedTnt tnt) {
        t.assertTrue(actor instanceof Actor.EntityActor e && e.entity() == tnt,
                "The explosion's actor should be Actor.EntityActor for the TNT, but was " + actor);
    }

    // ---- ExplosionPrimeEvent

    @HadronTest
    void tntGoingOffPrimesOnceWithTheTntAsActor(TestContext t) {
        List<ExplosionPrimeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionPrimeEvent e) {
                seen.add(e);
            }
        });
        PrimedTnt tnt = tnt(t);
        explode(t, tnt);
        t.assertEquals(1, seen.size(), "the number of ExplosionPrimeEvents for one TNT");
        assertTheTnt(t, seen.get(0).getActor(), tnt);
        t.assertEquals(4.0f, seen.get(0).getRadius(), "TNT's radius");
        t.assertFalse(seen.get(0).getFire(), "TNT shouldn't start fires");
    }

    @HadronTest
    void cancelledPrimeDestroysNothing(TestContext t) {
        BlockPos dirt = t.pos(2, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        List<ExplosionPrimeEvent> tried = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionPrimeEvent e) {
                tried.add(e);
                e.setCancelled(true);
            }
        });
        explode(t, tnt(t));
        t.assertEquals(1, tried.size(), "the TNT's attempts to explode");
        t.assertBlock(dirt, Blocks.DIRT);
    }

    @HadronTest
    void changedRadiusIsTheBlastsSize(TestContext t) {
        // TNT's radius of 4 reaches at most 6.9 blocks, so it can't touch a wall whose near face is 7.5 blocks away; a
        // radius of 8 does. A wall rather than one block, so enough of the blast's rays hit it.
        List<BlockPos> wall = new ArrayList<>();
        for (int y = 0; y <= 2; y++) for (int z = -1; z <= 1; z++) wall.add(t.pos(8, y, z));
        for (BlockPos pos : wall) t.setBlock(pos, Blocks.DIRT);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionPrimeEvent e) {
                e.setRadius(8);
            }
        });
        explode(t, tnt(t));
        long left = wall.stream().filter(pos -> t.getBlock(pos).is(Blocks.DIRT)).count();
        t.assertTrue(left < wall.size(), "a radius-8 blast should break some of the wall 7.5 blocks away, but all "
                + wall.size() + " blocks are still there");
    }

    @HadronTest
    void changedFireSetsFires(TestContext t) {
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionPrimeEvent e) {
                e.setFire(true);
            }
        });
        explode(t, tnt(t));
        boolean fire = false;
        for (BlockPos pos : BlockPos.betweenClosed(t.pos(-5, 0, -5), t.pos(5, 0, 5))) {
            if (t.getBlock(pos).is(Blocks.FIRE)) fire = true;
        }
        t.assertTrue(fire, "a blast with fire turned on should have lit fires on the floor around it");
    }

    // ---- ExplosionEvent

    @HadronTest
    void explosionListsItsBlocksOnceAndBreaksNoneAsABlockBreak(TestContext t) {
        BlockPos dirt = t.pos(2, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        List<ExplosionEvent> seen = new ArrayList<>();
        List<BlockBreakEvent> breaks = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionEvent e) {
                seen.add(e);
            }

            @EventHandler
            void on(BlockBreakEvent e) {
                breaks.add(e);
            }
        });
        PrimedTnt tnt = tnt(t);
        explode(t, tnt);
        t.assertBlock(dirt, Blocks.AIR);
        t.assertEquals(1, seen.size(), "the number of ExplosionEvents for one TNT");
        assertTheTnt(t, seen.get(0).getActor(), tnt);
        t.assertTrue(seen.get(0).blockList().contains(dirt), "the explosion's block list should have the dirt");
        t.assertEquals(0, breaks.size(), "BlockBreakEvents during an explosion (its blocks are reported once, in "
                + "ExplosionEvent)");
    }

    @HadronTest
    void cancelledExplosionBreaksNothingAndHurtsNobody(TestContext t) {
        BlockPos dirt = t.pos(2, 0, 0);
        t.setBlock(dirt, Blocks.DIRT);
        Console.awaitTicking(t, dirt);
        Pig pig = EntityType.PIG.create(t.level());
        pig.setNoAi(true);
        pig.moveTo(t.pos(0, 0, 2).getX() + 0.5, t.pos(0, 0, 2).getY(), t.pos(0, 0, 2).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        List<ExplosionEvent> tried = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionEvent e) {
                tried.add(e);
                e.setCancelled(true);
            }
        });
        explode(t, tnt(t));
        t.assertEquals(1, tried.size(), "the explosions the TNT tried");
        t.assertBlock(dirt, Blocks.DIRT);
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "the pig's health");
    }

    @HadronTest
    void aBlockTakenOffTheListSurvives(TestContext t) {
        BlockPos kept = t.pos(2, 0, 0);
        BlockPos lost = t.pos(-2, 0, 0);
        t.setBlock(kept, Blocks.DIRT);
        t.setBlock(lost, Blocks.DIRT);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionEvent e) {
                e.blockList().remove(kept);
            }
        });
        explode(t, tnt(t));
        t.assertBlock(kept, Blocks.DIRT);
        t.assertBlock(lost, Blocks.AIR);
    }

    @HadronTest(timeout = 900)
    void tntAPlayerLitExplodesOnTheirBehalf(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos at = t.pos(2, 0, 0);
        t.setBlock(at, Blocks.TNT);
        alice.hold(new ItemStack(Items.FLINT_AND_STEEL));
        List<ExplosionPrimeEvent> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ExplosionPrimeEvent e) {
                seen.add(e);
            }
        });
        alice.useBlock(at);
        alice.player().setGameMode(GameType.CREATIVE); // so the blast doesn't kill her
        t.await("alice's TNT to go off", () -> !seen.isEmpty(), 200);
        Actor actor = seen.get(0).getActor();
        t.assertTrue(actor instanceof Actor.EntityActor e && e.entity() instanceof PrimedTnt,
                "The blast's actor should be the primed TNT, but was " + actor);
        t.assertEquals(Optional.of(alice.uuid()), actor.attribution().responsible(),
                "who is responsible for the TNT alice lit");
    }
}
