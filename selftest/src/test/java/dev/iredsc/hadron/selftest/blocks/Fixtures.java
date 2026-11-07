package dev.iredsc.hadron.selftest.blocks;

import dev.iredsc.hadron.checks.Nondeterministic;
import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Set-up and checks the block tests share. */
final class Fixtures {
    private Fixtures() {}

    /** Runs a command as the console would, at {@code at} in the test's world. */
    static void console(TestContext t, BlockPos at, String command) {
        CommandSourceStack source = t.server().createCommandSourceStack().withLevel(t.level())
                .withPosition(Vec3.atBottomCenterOf(at)).withSuppressedOutput();
        t.server().getCommands().performPrefixedCommand(source, command);
    }

    static void assertPlayer(TestContext t, Actor actor, TestPlayer player) {
        t.assertTrue(actor instanceof Actor.Player p && p.player() == player.player(),
                "The actor should be Actor.Player for " + player.name() + ", but was " + actor);
    }

    static void assertMachineAt(TestContext t, Actor actor, BlockPos pos) {
        t.assertTrue(actor instanceof Actor.Machine m && m.pos().equals(pos) && m.level() == t.level(),
                "The actor should be Actor.Machine at " + t.describe(pos) + ", but was " + actor);
    }

    static void assertNature(TestContext t, Actor actor) {
        t.assertTrue(actor instanceof Actor.Nature, "The actor should be Actor.Nature, but was " + actor);
    }

    /**
     * Waits until the test world's chunk at {@code pos} runs block, fluid and entity ticks. A fresh world's entities
     * load off the server thread, and the harness runs ticks as fast as it can, so a test with no player to wait on
     * can otherwise spend dozens of ticks before anything ticks. Each check sleeps briefly to give the loader time.
     *
     * <p>The chunk is also forced: a world with no players and no forced chunks stops ticking entities and block
     * entities (pistons, hoppers) 300 ticks after it was last occupied.
     */
    static void awaitTicking(TestContext t, BlockPos pos) {
        t.level().setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        t.await("the test world's chunk to start ticking", () -> {
            if (t.level().isPositionEntityTicking(pos)) return true;
            pause();
            return false;
        }, 300);
    }

    @Nondeterministic(reason = "yields wall time to work on other threads (chunk workers, async tasks) between ticks, which run faster than it")
    static void pause() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A dispenser at {@code pos} facing {@code facing}, holding {@code stack} in its first slot. */
    static DispenserBlockEntity dispenser(TestContext t, BlockPos pos, Direction facing, ItemStack stack) {
        awaitTicking(t, pos);
        t.setBlock(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing));
        DispenserBlockEntity dispenser = (DispenserBlockEntity) t.level().getBlockEntity(pos);
        dispenser.setItem(0, stack);
        return dispenser;
    }

    /** Powers the dispenser with a redstone block behind it and waits for it to fire. */
    static void fire(TestContext t, BlockPos dispenser, Direction facing) {
        t.setBlock(dispenser.relative(facing.getOpposite()), Blocks.REDSTONE_BLOCK);
        t.tick(6);
    }

    static <E extends Entity> int entities(TestContext t, Class<E> type, BlockPos around, int radius) {
        return t.level().getEntitiesOfClass(type, new AABB(around).inflate(radius)).size();
    }

    /** How many of the item are lying around {@code around} or already in the player's inventory. */
    static int itemsAround(TestContext t, TestPlayer player, Item item, BlockPos around) {
        int count = player.player().getInventory().countItem(item);
        for (ItemEntity e : t.level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(4))) {
            if (e.getItem().is(item)) count += e.getItem().getCount();
        }
        return count;
    }

    /** Experience lying around {@code around} as orbs plus what the player already collected. */
    static int experienceAround(TestContext t, TestPlayer player, BlockPos around) {
        int total = player.player().totalExperience;
        for (ExperienceOrb orb : t.level().getEntitiesOfClass(ExperienceOrb.class, new AABB(around).inflate(4))) {
            total += orb.getValue();
        }
        return total;
    }

    /** Runs {@code work} with random ticks sped up, so a natural change happens within a few dozen ticks. */
    static void withRandomTicks(TestContext t, Runnable work) {
        GameRules.IntegerValue rule = t.server().getGameRules().getRule(GameRules.RULE_RANDOMTICKING);
        int old = rule.get();
        rule.set(1024, t.server());
        try {
            work.run();
        } finally {
            rule.set(old, t.server());
        }
    }

    /** Clear skies, so rain doesn't put out fire mid-test (test worlds share the overworld's weather). */
    static void clearWeather(TestContext t) {
        t.server().overworld().setWeatherParameters(24000, 0, false, false);
    }
}
