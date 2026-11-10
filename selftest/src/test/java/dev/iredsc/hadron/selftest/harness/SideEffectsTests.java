package dev.iredsc.hadron.selftest.harness;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawnWithAi;

import dev.iredsc.hadron.api.net.PacketRegistration;
import dev.iredsc.hadron.api.net.Packets;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.SideEffects;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundRecipePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * {@link TestContext#watchSideEffects}: each kind of side effect, planted by the test itself, fails
 * {@link SideEffects#assertNone} naming what fired, for whom, where and from which line; what every tick makes passes.
 */
public final class SideEffectsTests {
    /** The failure of {@code assertNone}, which a planted effect must cause. */
    private static String failure(SideEffects effects, String step) {
        try {
            effects.assertNone(step);
        } catch (AssertionError e) {
            return e.getMessage();
        }
        throw new AssertionError("assertNone(\"" + step + "\") passed, but the test planted a side effect before it");
    }

    private static void assertNames(TestContext t, String message, String... parts) {
        for (String part : parts) {
            t.assertTrue(message.contains(part), "the failure should name \"" + part + "\", but read:\n" + message);
        }
    }

    @HadronTest
    void aWatchNothingHappenedToPasses(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawnWithAi(t, EntityType.COW, t.pos(3, 0, 3));
        t.tick();
        alice.hold(new ItemStack(Items.SHEARS)); // setup the next tick would sync, firing inventory_changed
        SideEffects effects = t.watchSideEffects();
        t.tick(45); // past the 20-tick location trigger twice, with every tick's stats and the cow's AI running
        cow.gameEvent(GameEvent.STEP); // a mob's own footsteps are motion, not an action's
        cow.playAmbientSound(); // nor is its moo, which its tick plays now and then
        // nor the sound of a hit that did nothing, which is what a refused hit is
        t.level().playSound(null, t.pos(1, 0, 0), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 1.0F, 1.0F);
        t.level().levelEvent(LevelEvent.PARTICLES_SHOOT_SMOKE, t.pos(1, 0, 0), 0); // a silent numbered event
        effects.assertNone("45 idle ticks beside a cow, shears in hand");
        t.assertEquals(0, effects.seen().size(), "side effects seen");
    }

    @HadronTest
    void aSoundIsNamedWithWhereAndWhoWasSentIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        BlockPos at = t.pos(2, 0, 0);
        t.level().playSound(null, at, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1.0F, 1.0F);
        t.level().playSound(alice.player(), at, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
        alice.player().playNotifySound(SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1.0F, 1.0F);
        t.level().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, at, 0);
        String where = at.getX() + " " + at.getY() + " " + at.getZ() + " in " + t.level().dimension().location();
        assertNames(t, failure(effects, "planted sounds"), "left 4:",
                "sound minecraft:block.lever.click (block) at " + where + ", sent to all near",
                "sound minecraft:block.wooden_door.open (block) at " + where
                        + ", sent to all near but alice, whose client plays it itself",
                "sound minecraft:block.chest.locked (block) at ", "sent to alice only",
                "level event 1000 SOUND_DISPENSER_DISPENSE at " + where + ", sent to all near",
                "from SideEffectsTests.aSoundIsNamedWithWhereAndWhoWasSentIt(SideEffectsTests.java:");
        SideEffects.SoundPlayed click = effects.seen(SideEffects.SoundPlayed.class).get(0);
        t.assertEquals(SoundEvents.LEVER_CLICK, click.sound().value(), "the first sound recorded");
        t.assertEquals(t.level(), click.level(), "the click's world");
    }

    @HadronTest
    void aSoundSentStraightToAPlayerIsRecordedOnce(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        t.server().getCommands().performPrefixedCommand(t.server().createCommandSourceStack().withLevel(t.level()),
                "execute at alice run playsound minecraft:block.lever.click master alice");
        // A portal's whoosh, sent to the player who went through it alone.
        alice.player().connection.send(new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, t.pos(0, 0, 0),
                0, false));
        t.tick(); // the packets reach alice's client
        assertNames(t, failure(effects, "sounds sent to alice alone"), "left 2:",
                "sound minecraft:block.lever.click (master) at ", "level event 1032 SOUND_PORTAL_TRAVEL at ",
                "sent to alice only", "from SideEffectsTests.aSoundSentStraightToAPlayerIsRecordedOnce(");
        t.assertEquals(1L, alice.received(ClientboundSoundPacket.class).stream()
                // /playsound sends a sound made from its name, so compare names
                .filter(p -> p.getSound().value().getLocation().equals(SoundEvents.LEVER_CLICK.getLocation())).count(),
                "lever clicks alice was sent");
    }

    @HadronTest
    void aBroadcastTwoPlayersHearIsRecordedOnce(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        alice.clearReceived();
        bob.clearReceived();
        SideEffects effects = t.watchSideEffects();
        t.level().playSound(null, t.pos(1, 0, 0), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1.0F, 1.0F);
        t.tick();
        t.assertEquals(1, alice.received(ClientboundSoundPacket.class).size(), "sound packets alice was sent");
        t.assertEquals(1, bob.received(ClientboundSoundPacket.class).size(), "sound packets bob was sent");
        t.assertEquals(1, effects.seen(SideEffects.SoundPlayed.class).size(), "sounds recorded for one broadcast "
                + "both heard; seen: " + effects.seen());
    }

    @HadronTest
    void aSoundAHandlerSendsDuringABroadcastIsRecorded(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.player("bob");
        t.tick();
        alice.clearReceived();
        // While the click goes to bob (alice's own client plays it), a handler sends alice a portal's whoosh.
        PacketRegistration handler = Packets.onSend(t.plugin(), ClientboundSoundPacket.class, e -> {
            if (e.getPacket().getSound().value() == SoundEvents.LEVER_CLICK) alice.player().connection.send(whoosh(t));
        });
        try {
            SideEffects effects = t.watchSideEffects();
            t.level().playSound(alice.player(), t.pos(0, 0, 0), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1, 1);
            t.tick();
            t.assertEquals(1, alice.received(ClientboundLevelEventPacket.class).size(), "whooshes alice was sent");
            t.assertEquals(1, effects.seen(SideEffects.SoundPlayed.class).size(), "the click, recorded once");
            t.assertEquals(1, effects.seen(SideEffects.LevelEventPlayed.class).size(), "the whoosh sent during it");
        } finally {
            handler.unregister();
        }
    }

    @HadronTest
    void aSoundAnotherThreadSendsDuringABroadcastIsRecorded(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.player("bob");
        t.tick();
        alice.clearReceived();
        PacketRegistration handler = Packets.onSend(t.plugin(), ClientboundSoundPacket.class, e -> {
            if (e.getPacket().getSound().value() == SoundEvents.LEVER_CLICK) {
                CompletableFuture.runAsync(() -> alice.player().connection.send(whoosh(t))).join();
            }
        });
        try {
            SideEffects effects = t.watchSideEffects();
            t.level().playSound(alice.player(), t.pos(0, 0, 0), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1, 1);
            t.tick();
            t.assertEquals(1, alice.received(ClientboundLevelEventPacket.class).size(), "whooshes alice was sent");
            t.assertEquals(1, effects.seen(SideEffects.LevelEventPlayed.class).size(), "the other thread's whoosh");
        } finally {
            handler.unregister();
        }
    }

    @HadronTest
    void soundsInABundleAreRecorded(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        alice.player().connection.send(new ClientboundBundlePacket(List.of(whoosh(t), new ClientboundSoundEntityPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.LEVER_CLICK), SoundSource.BLOCKS, alice.player(),
                1, 1, 1))));
        t.tick();
        t.assertEquals(1, effects.seen(SideEffects.SoundPlayed.class).size(), "entity sounds in the bundle");
        t.assertEquals(1, effects.seen(SideEffects.LevelEventPlayed.class).size(), "whooshes in the bundle");
    }

    @HadronTest
    void aSoundAnotherThreadSendsDuringAnAmbientTriggerIsRecorded(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.tick();
        SideEffects effects = t.watchSideEffects();
        // A worker thread's own action, held at a gate only so that it overlaps the server thread's ambient trigger.
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
            gate.join();
            alice.player().connection.send(whoosh(t));
        });
        boolean[] released = {false};
        // Entering water unlocks the boat recipes, sending alice their recipe packet from inside the trigger.
        PacketRegistration handler = Packets.onSend(t.plugin(), ClientboundRecipePacket.class, e -> {
            if (released[0]) return;
            released[0] = true;
            gate.complete(null);
            worker.join();
        });
        try {
            CriteriaTriggers.ENTER_BLOCK.trigger(alice.player(), Blocks.WATER.defaultBlockState());
            t.tick();
            t.assertTrue(released[0], "entering water should send a recipe packet, releasing the worker inside the "
                    + "trigger; without that this test proves nothing");
            t.assertEquals(1, alice.received(ClientboundLevelEventPacket.class).size(), "whooshes alice was sent");
            t.assertEquals(1, effects.seen(SideEffects.LevelEventPlayed.class).size(), "the worker's whoosh, sent "
                    + "while the server thread was inside an ambient trigger");
        } finally {
            handler.unregister();
            gate.complete(null);
        }
    }

    /** A portal's whoosh, which the server sends straight to the player who went through. */
    private static ClientboundLevelEventPacket whoosh(TestContext t) {
        return new ClientboundLevelEventPacket(LevelEvent.SOUND_PORTAL_TRAVEL, t.pos(0, 0, 0), 0, false);
    }

    @HadronTest
    void aSoundNobodyIsNearIsStillRecorded(TestContext t) {
        t.player("alice");
        SideEffects effects = t.watchSideEffects();
        t.level().playSound(null, t.pos(0, 0, 200), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 1.0F, 1.0F);
        assertNames(t, failure(effects, "a sound far from everyone"), "sound minecraft:block.lever.click");
    }

    @HadronTest
    void whatAnAmbientTriggerCausesIsAmbientToo(TestContext t) {
        TestPlayer alice = t.player("alice");
        ResourceLocation boat = ResourceLocation.withDefaultNamespace("oak_boat");
        t.assertFalse(alice.player().getRecipeBook().contains(boat), "a fresh player shouldn't know the oak boat yet");
        SideEffects effects = t.watchSideEffects();
        // What standing in water fires every tick; it completes the boat recipes' advancements, unlocking them.
        CriteriaTriggers.ENTER_BLOCK.trigger(alice.player(), Blocks.WATER.defaultBlockState());
        t.assertTrue(alice.player().getRecipeBook().contains(boat), "entering water should unlock the oak boat");
        effects.assertNone("an enter_block trigger and the recipes it unlocked");
    }

    @HadronTest
    void aStatIsNamedWithItsPlayerAndLine(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        alice.player().awardStat(Stats.ITEM_USED.get(Items.LEAD));
        assertNames(t, failure(effects, "a planted stat"), "a planted stat should leave no side effect",
                "alice's stat minecraft:used minecraft:lead went 0 -> 1",
                "from SideEffectsTests.aStatIsNamedWithItsPlayerAndLine(SideEffectsTests.java:");
        SideEffects.StatChanged stat = effects.seen(SideEffects.StatChanged.class).get(0);
        t.assertEquals(Stats.ITEM_USED.get(Items.LEAD), stat.stat(), "the stat recorded");
        t.assertEquals(alice, stat.player(), "whose stat");
    }

    @HadronTest
    void aCriterionTriggerIsNamedEvenWithNoAdvancementListening(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        CriteriaTriggers.CONSUME_ITEM.trigger(alice.player(), new ItemStack(Items.APPLE));
        assertNames(t, failure(effects, "a planted trigger"), "trigger minecraft:consume_item fired for alice",
                "from SideEffectsTests.aCriterionTriggerIsNamedEvenWithNoAdvancementListening(SideEffectsTests.java:");
        t.assertEquals(CriteriaTriggers.CONSUME_ITEM,
                effects.seen(SideEffects.CriterionTriggered.class).get(0).trigger(), "the trigger recorded");
    }

    @HadronTest
    void aGameEventIsNamedWithWhereAndWho(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        t.level().gameEvent(GameEvent.SHEAR, t.pos(2, 0, 0), GameEvent.Context.of(alice.player()));
        t.level().gameEvent(GameEvent.BLOCK_CHANGE, t.pos(-1, 1, 0), GameEvent.Context.of((Entity) null));
        alice.player().gameEvent(GameEvent.STEP); // a test player's own motion counts: the test moved them
        String at = t.pos(2, 0, 0).getX() + " " + t.pos(2, 0, 0).getY() + " " + t.pos(2, 0, 0).getZ();
        String nobody = t.pos(-1, 1, 0).getX() + " " + t.pos(-1, 1, 0).getY() + " " + t.pos(-1, 1, 0).getZ();
        assertNames(t, failure(effects, "planted game events"), "left 3:",
                "game event minecraft:shear at " + at + " by alice",
                "game event minecraft:block_change at " + nobody + ", by nobody",
                "game event minecraft:step at ",
                "from SideEffectsTests.aGameEventIsNamedWithWhereAndWho(SideEffectsTests.java:");
    }

    @HadronTest
    void experienceAndHungerAreNamed(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        alice.player().giveExperiencePoints(7);
        alice.player().giveExperienceLevels(-1);
        alice.player().causeFoodExhaustion(0.5F);
        assertNames(t, failure(effects, "planted experience and hunger"),
                "alice's experience changed by 7 points", "alice's experience level changed by -1",
                "alice was exhausted by 0.5",
                "from SideEffectsTests.experienceAndHungerAreNamed(SideEffectsTests.java:");
    }

    @HadronTest
    void markForgetsWhatCameBefore(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects effects = t.watchSideEffects();
        alice.player().awardStat(Stats.JUMP);
        effects.mark();
        effects.assertNone("nothing after the mark");
        alice.player().awardStat(Stats.JUMP);
        assertNames(t, failure(effects, "a stat after the mark"), "left 1:",
                "alice's stat minecraft:custom minecraft:jump went 1 -> 2");
    }

    @HadronTest
    void eachWatchStartsWhenItIsMade(TestContext t) {
        TestPlayer alice = t.player("alice");
        SideEffects first = t.watchSideEffects();
        alice.player().awardStat(Stats.JUMP);
        SideEffects second = t.watchSideEffects();
        second.assertNone("nothing since the second watch");
        t.assertEquals(1, first.seen().size(), "side effects the first watch saw");
    }
}
