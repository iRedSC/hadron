package dev.iredsc.hadron.selftest;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Hadron's core events, driven by a real player's packets: cancels undo fully, changes apply, actors are right. */
public final class EventTests {
    @HadronTest
    void cancelledBreakKeepsTheBlockAndTheToolsDurability(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                e.setCancelled(true);
            }
        });
        alice.breakBlock(stone);
        t.tick(2);
        t.assertBlock(stone, Blocks.STONE);
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the pickaxe's damage");
        t.assertEquals(0, t.level().getEntitiesOfClass(ItemEntity.class, new AABB(stone).inflate(2)).size(),
                "the number of items dropped");
    }

    @HadronTest
    void uncancelledBreakRemovesTheBlockAndWearsTheTool(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(1, 0, 0);
        t.setBlock(stone, Blocks.STONE);
        alice.hold(new ItemStack(Items.IRON_PICKAXE));
        alice.breakBlock(stone);
        t.assertBlock(stone, Blocks.AIR);
        t.assertEquals(1, alice.player().getMainHandItem().getDamageValue(), "the pickaxe's damage");
    }

    @HadronTest
    void changedDamageIsTheDamageDealt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = EntityType.PIG.create(t.level());
        pig.setNoAi(true);
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) e.setDamage(5);
            }
        });
        // A fist does 1; the listener makes it 5. The attack cooldown is full by now: joining took ticks.
        t.tick(10);
        alice.attack(pig);
        t.assertEquals(pig.getMaxHealth() - 5, pig.getHealth(), "the pig's health");
    }

    @HadronTest
    void changedChatReachesTheOtherPlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerChatEvent e) {
                e.setMessage(Component.literal("rewritten by a listener"));
            }
        });
        alice.chat("as typed");
        t.assertChat(bob, "rewritten by a listener");
        t.assertNoChat(bob, "as typed");
    }

    @HadronTest
    void cancelledChatReachesNobody(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerChatEvent e) {
                e.setCancelled(true);
            }
        });
        alice.chat("never seen");
        t.assertNoChat(bob, "never seen");
        t.assertNoChat(alice, "never seen");
    }

    @HadronTest
    void aPlayersBreakIsAttributedToThatPlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos dirt = t.pos(0, 0, 1);
        t.setBlock(dirt, Blocks.DIRT);
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockBreakEvent e) {
                actors.add(e.getActor());
            }
        });
        alice.breakBlock(dirt);
        t.assertEquals(1, actors.size(), "the number of BlockBreakEvents");
        t.assertTrue(actors.get(0) instanceof Actor.Player p && p.player() == alice.player(),
                "The break should be Actor.Player for alice, but was " + actors.get(0));
    }
}
