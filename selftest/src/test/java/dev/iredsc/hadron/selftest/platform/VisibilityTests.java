package dev.iredsc.hadron.selftest.platform;

import dev.iredsc.hadron.api.net.Visibility;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;

/** Hiding entities and players from two real clients: spawning, tab list, /list, sounds and interactions. */
public final class VisibilityTests {
    private static Pig pig(TestContext t, BlockPos pos) {
        Pig pig = EntityType.PIG.create(t.level());
        pig.setNoAi(true);
        pig.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        return pig;
    }

    private static long hurtSounds(TestPlayer viewer) {
        long positional = viewer.received(ClientboundSoundPacket.class).stream()
                .filter(p -> p.getSound().value() == SoundEvents.PLAYER_HURT).count();
        long attached = viewer.received(ClientboundSoundEntityPacket.class).stream()
                .filter(p -> p.getSound().value() == SoundEvents.PLAYER_HURT).count();
        return positional + attached;
    }

    @HadronTest
    void aHiddenEntityIsNeverSentToThatViewerOnly(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Pig pig = pig(t, t.pos(2, 0, 0));
        Visibility.hideEntity(t.plugin(), bob.player(), pig);
        t.level().addFreshEntity(pig);
        t.await("alice's client to be sent the pig", () -> alice.wasSent(pig), 60);
        t.tick(2);
        t.assertNotSent(bob, pig);
        t.assertSent(alice, pig);
        t.assertFalse(Visibility.canSee(bob.player(), pig), "canSee should say bob can't see the pig");
        Visibility.showEntity(t.plugin(), bob.player(), pig);
        t.tick(2);
        t.assertSent(bob, pig);
    }

    @HadronTest
    void hideFromAllAlsoHidesFromPlayersWhoJoinLater(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Visibility.hideFromAll(t.plugin(), alice.player());
        t.tick();
        t.assertFalse(bob.isShown(alice.player()), "hiding alice from all should remove her from bob's client");
        t.assertNotInTabList(bob, alice.player());
        TestPlayer carol = t.player("carol");
        t.tick(3);
        t.assertNotSent(carol, alice.player());
        t.assertNotInTabList(carol, alice.player());
        t.assertInTabList(carol, bob.player());
        t.assertTrue(Visibility.isHiddenFromAll(t.plugin(), alice.player()), "isHiddenFromAll should say so");
    }

    @HadronTest
    void listLeavesOutPlayersHiddenFromTheViewer(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Visibility.hideFromAll(t.plugin(), alice.player());
        t.tick();
        bob.clearReceived();
        bob.command("list");
        t.assertChat(bob, "bob");
        t.assertNoChat(bob, "alice");
        t.assertChat(bob, "There are 1 of");
    }

    @HadronTest
    void aHiddenPlayersHurtSoundIsNotSent(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        // Visible first, so this test can't pass because hurt sounds never arrive at all.
        bob.clearReceived();
        alice.player().hurt(t.level().damageSources().genericKill(), 1);
        t.tick();
        t.assertTrue(hurtSounds(bob) > 0, "bob should hear visible alice get hurt; without that this test proves "
                + "nothing");
        t.tick(25); // past alice's invulnerability after a hit
        Visibility.hidePlayer(t.plugin(), bob.player(), alice.player());
        t.tick();
        bob.clearReceived();
        alice.player().hurt(t.level().damageSources().genericKill(), 1);
        t.tick();
        t.assertEquals(0L, hurtSounds(bob), "player hurt sounds bob received from hidden alice");
    }

    @HadronTest
    void attacksOnAHiddenEntityAreIgnored(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = pig(t, t.pos(1, 0, 0));
        t.level().addFreshEntity(pig);
        t.await("bob's client to be sent the pig", () -> bob.isShown(pig), 60);
        t.tick(10); // the attack cooldown fills
        Visibility.hideEntity(t.plugin(), bob.player(), pig);
        t.tick();
        bob.attack(pig);
        t.tick();
        t.assertEquals(pig.getMaxHealth(), pig.getHealth(), "the hidden pig's health after bob attacked it");
        Visibility.showEntity(t.plugin(), bob.player(), pig);
        t.await("bob's client to be sent the pig again", () -> bob.isShown(pig), 60);
        t.tick(10);
        bob.attack(pig);
        t.assertTrue(pig.getHealth() < pig.getMaxHealth(), "once shown, bob's attack should hurt the pig; without "
                + "that this test proves nothing");
    }
}
