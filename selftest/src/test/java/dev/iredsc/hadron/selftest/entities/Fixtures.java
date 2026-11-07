package dev.iredsc.hadron.selftest.entities;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Setting up entities and reading actors, shared by the entity and player tests. */
public final class Fixtures {
    private Fixtures() {}

    /**
     * Waits until the test world's centre ticks entities. Entities only tick, and clicks and commands only find them,
     * in a chunk a player keeps loaded; {@code t.tick()} runs ticks as fast as it can and doesn't wait for the chunk
     * workers that get it there, so on a busy machine a test that acts at once can find nothing to act on.
     */
    @Nondeterministic(reason = "yields wall time to work on other threads (chunk workers, async tasks) between ticks, which run faster than it")
    public static void ready(TestContext t) {
        if (t.players().isEmpty()) {
            throw new AssertionError("Join a player before spawning: entities only tick near one");
        }
        t.await("the test world's centre to tick entities", () -> {
            if (t.level().isPositionEntityTicking(t.pos(0, 0, 0))) return true;
            try {
                Thread.sleep(10); // give the chunk workers wall time; ticks alone run too fast
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }, 300);
    }

    /** A mob or entity standing at the bottom centre of {@code at}, AI off so it stays put. Join a player first. */
    public static <T extends Entity> T spawn(TestContext t, EntityType<T> type, BlockPos at) {
        ready(t);
        T entity = create(t, type, at);
        if (entity instanceof Mob mob) mob.setNoAi(true);
        t.level().addFreshEntity(entity);
        return entity;
    }

    /** As {@link #spawn}, with its AI on. */
    public static <T extends Entity> T spawnWithAi(TestContext t, EntityType<T> type, BlockPos at) {
        ready(t);
        T entity = create(t, type, at);
        t.level().addFreshEntity(entity);
        return entity;
    }

    private static <T extends Entity> T create(TestContext t, EntityType<T> type, BlockPos at) {
        T entity = type.create(t.level());
        if (entity == null) throw new AssertionError(type + " can't be created in the test world");
        entity.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        return entity;
    }

    /** The bottom centre of a block. */
    public static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** Runs a command from the server console, in this test's world (the console is otherwise in the overworld). */
    public static void console(TestContext t, String command) {
        t.server().getCommands().performPrefixedCommand(t.server().createCommandSourceStack().withLevel(t.level()),
                command);
    }

    /** What the server would make of a player's click on an entity, for a failure message. */
    public static String reach(TestContext t, TestPlayer player, Entity target) {
        return "[server finds it by id: " + (t.level().getEntity(target.getId()) == target) + ", in reach: "
                + player.player().canInteractWithEntity(target.getBoundingBox(), 1.0) + ", within the border: "
                + t.level().getWorldBorder().isWithinBounds(target.blockPosition()) + ", sneaking: "
                + player.player().isShiftKeyDown() + ", target removed: " + target.isRemoved() + ", ticks spent: "
                + t.ticksSpent() + "]";
    }

    /** Entities of a class within 16 blocks of the test's centre. */
    public static <T extends Entity> List<T> near(TestContext t, Class<T> type) {
        return t.level().getEntitiesOfClass(type, new AABB(t.pos(0, 0, 0)).inflate(16));
    }

    public static boolean isPlayer(Actor actor, TestPlayer player) {
        return actor instanceof Actor.Player p && p.player() == player.player();
    }

    public static void assertPlayer(TestContext t, Actor actor, TestPlayer player, String what) {
        t.assertTrue(isPlayer(actor, player), what + " should be Actor.Player for " + player.name() + ", but was "
                + actor);
    }

    public static void assertServer(TestContext t, Actor actor, String what) {
        t.assertTrue(actor instanceof Actor.Server, what + " should be Actor.Server (the console), but was " + actor);
    }

    /** An EntityActor for exactly this entity, answering to this person. */
    public static void assertEntityFor(TestContext t, Actor actor, Entity entity, UUID responsible, String what) {
        t.assertTrue(actor instanceof Actor.EntityActor e && e.entity() == entity, what + " should be the EntityActor "
                + "for " + entity.getName().getString() + ", but was " + actor);
        t.assertEquals(java.util.Optional.of(responsible), actor.attribution().responsible(),
                what + "'s responsible player");
    }

    public static double horizontalSpeed(Entity entity) {
        return entity.getDeltaMovement().horizontalDistance();
    }
}
