package dev.iredsc.hadron.selftest.items;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Shared checks for the item, inventory and platform self-tests. */
public final class Support {
    private Support() {}

    /** That exactly one event was heard; {@code what} names it. */
    public static <E> E one(TestContext t, List<E> heard, String what) {
        t.assertEquals(1, heard.size(), "the number of " + what + "s fired");
        return heard.get(0);
    }

    public static void assertPlayerActor(TestContext t, Actor actor, TestPlayer who) {
        t.assertTrue(actor instanceof Actor.Player p && p.player() == who.player(),
                "The actor should be Actor.Player for " + who.name() + ", but was " + actor);
    }

    public static void assertMachineActor(TestContext t, Actor actor, BlockPos pos) {
        t.assertTrue(actor instanceof Actor.Machine m && m.pos().equals(pos) && m.level() == t.level(),
                "The actor should be Actor.Machine at " + t.describe(pos) + ", but was " + actor);
    }

    public static void assertNatureActor(TestContext t, Actor actor) {
        t.assertTrue(actor instanceof Actor.Nature, "The actor should be Actor.Nature, but was " + actor);
    }

    /**
     * Joins a player who just stands at {@code pos(0, 0, 0)}. A test world with nobody in it never loads its chunks'
     * entities, so its item entities, hoppers, droppers, dispensers and pistons don't run (a gap in the harness);
     * a player there makes the world tick as a played one does.
     */
    public static TestPlayer watch(TestContext t) {
        return t.player("watcher");
    }

    /**
     * Waits until the world can find the entity: one added right after a join is only reachable (by packets, hoppers,
     * dispensers) once its chunk's entities have loaded, which takes a few ticks.
     */
    public static void awaitLoaded(TestContext t, Entity entity) {
        t.await(entity.getName().getString() + " to be loaded in the world",
                () -> t.level().getEntity(entity.getId()) == entity, 60);
    }

    /** Item entities within {@code radius} blocks of the position. */
    public static List<ItemEntity> itemsNear(TestContext t, BlockPos pos, double radius) {
        return t.level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(radius));
    }

    /** An item entity lying still at the bottom centre of {@code pos}, ready to be picked up. */
    public static ItemEntity dropAt(TestContext t, BlockPos pos, ItemStack stack) {
        ItemEntity item = new ItemEntity(t.level(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, stack);
        item.setDeltaMovement(0, 0, 0);
        item.setPickUpDelay(0);
        t.level().addFreshEntity(item);
        return item;
    }

    /** Places an entity at the bottom centre of {@code pos} and adds it to the test's world. */
    public static <T extends Entity> T spawn(TestContext t, T entity, BlockPos pos) {
        entity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        t.level().addFreshEntity(entity);
        return entity;
    }

    public static String describe(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : stack.getCount() + " " + stack.getItem();
    }
}
