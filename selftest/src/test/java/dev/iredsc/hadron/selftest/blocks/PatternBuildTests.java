package dev.iredsc.hadron.selftest.blocks;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Golems and the wither: a pattern of blocks consumed to make a creature is one action. Refusing any block's break
 * builds nothing and keeps every block; refusing the creature's spawn puts every block back.
 */
public final class PatternBuildTests {
    /** An iron golem's body along the z axis at pos(2, *, 0), missing only its head. Returns where the head goes. */
    private static BlockPos ironBody(TestContext t) {
        BlockPos base = t.pos(2, 0, 0);
        t.setBlock(base, Blocks.IRON_BLOCK);
        t.setBlock(base.above(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().north(), Blocks.IRON_BLOCK);
        t.setBlock(base.above().south(), Blocks.IRON_BLOCK);
        return base.above(2);
    }

    private static void assertIronBody(TestContext t, BlockPos head) {
        BlockPos body = head.below();
        for (BlockPos pos : List.of(body.below(), body, body.north(), body.south())) t.assertBlock(pos, Blocks.IRON_BLOCK);
    }

    private static int golems(TestContext t, BlockPos head) {
        return Fixtures.entities(t, IronGolem.class, head, 4);
    }

    @HadronTest
    @Covers(BlockBreakEvent.class)
    void refusingOneIronBlocksBreakBuildsNoGolemAndKeepsTheIron(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos head = ironBody(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                if (e.getBlocks().get(0).getBefore().is(Blocks.IRON_BLOCK)) e.setCancelled(true);
            }
        });
        alice.hold(new ItemStack(Items.CARVED_PUMPKIN));
        alice.placeBlock(head);
        t.tick(2);
        t.assertEquals(0, golems(t, head), "iron golems built from iron a plugin refused to break");
        assertIronBody(t, head);
        t.assertBlock(head, Blocks.CARVED_PUMPKIN);
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void refusingTheGolemsSpawnPutsItsBlocksBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos head = ironBody(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof IronGolem) e.setCancelled(true);
            }
        });
        alice.hold(new ItemStack(Items.CARVED_PUMPKIN));
        alice.placeBlock(head);
        t.tick(2);
        t.assertEquals(0, golems(t, head), "iron golems after their spawn was refused");
        assertIronBody(t, head);
        t.assertBlock(head, Blocks.CARVED_PUMPKIN);
    }

    @HadronTest
    @Covers(EntitySpawnEvent.class)
    void refusingTheWithersSpawnPutsItsBlocksBack(TestContext t) {
        Difficulty difficulty = t.level().getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) t.server().setDifficulty(Difficulty.NORMAL, true);
        try {
            TestPlayer alice = t.player("alice");
            BlockPos base = t.pos(3, 0, 0);
            BlockPos body = base.above();
            BlockPos head = body.above();
            t.setBlock(base, Blocks.SOUL_SAND);
            t.setBlock(body, Blocks.SOUL_SAND);
            t.setBlock(body.north(), Blocks.SOUL_SAND);
            t.setBlock(body.south(), Blocks.SOUL_SAND);
            t.setBlock(head.north(), Blocks.WITHER_SKELETON_SKULL);
            t.setBlock(head.south(), Blocks.WITHER_SKELETON_SKULL);
            t.registerEvents(new Listener() {
                @EventHandler
                void on(EntitySpawnEvent e) {
                    if (e.getEntity() instanceof WitherBoss) e.setCancelled(true);
                }
            });
            alice.hold(new ItemStack(Items.WITHER_SKELETON_SKULL));
            alice.placeBlock(head);
            t.tick(2);
            t.assertEquals(0, Fixtures.entities(t, WitherBoss.class, head, 6), "withers after the spawn was refused");
            for (BlockPos pos : List.of(base, body, body.north(), body.south())) t.assertBlock(pos, Blocks.SOUL_SAND);
            for (BlockPos pos : List.of(head, head.north(), head.south())) {
                t.assertBlock(pos, Blocks.WITHER_SKELETON_SKULL);
            }
        } finally {
            t.server().setDifficulty(difficulty, true);
        }
    }
}
