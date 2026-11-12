package dev.iredsc.hadron.selftest.players;

import static dev.iredsc.hadron.selftest.entities.Fixtures.console;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.player.PlayerAbilitiesChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerAdvancementDoneEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedEnterEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedLeaveEvent;
import dev.iredsc.hadron.api.event.player.PlayerChangedWorldEvent;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.player.PlayerExpChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerFlightRevokeEvent;
import dev.iredsc.hadron.api.event.player.PlayerGameModeChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerLevelChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerSwapHandItemsEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleFlightEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSneakEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSprintEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * The player's own state: sneaking, sprinting, flight, abilities, game mode, experience, advancements, hands, beds,
 * worlds and moves. Where the harness has no action for a client packet (sneak, sprint, fly, swap hands, leave a bed),
 * the test hands the server that packet directly, as the client would send it.
 */
public final class PlayerStateTests {
    private static void command(TestPlayer p, ServerboundPlayerCommandPacket.Action action) {
        p.player().connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(p.player(), action));
    }

    @HadronTest
    @Covers(PlayerToggleSneakEvent.class)
    void sneakingFiresAndCancelledStaysStanding(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<Boolean> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerToggleSneakEvent e) {
                asked.add(e.isSneaking());
                e.setCancelled(true);
            }
        });
        command(alice, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY);
        t.assertEquals(List.of(true), asked, "the sneak states asked about");
        t.assertFalse(alice.player().isShiftKeyDown(), "alice should not be sneaking");
    }

    @HadronTest
    @Covers(PlayerToggleSneakEvent.class)
    void anUncancelledSneakSneaks(TestContext t) {
        TestPlayer alice = t.player("alice");
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerToggleSneakEvent e) {
                asked[0]++;
            }
        });
        command(alice, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY);
        t.assertEquals(1, asked[0], "the number of PlayerToggleSneakEvents");
        t.assertTrue(alice.player().isShiftKeyDown(), "alice should be sneaking");
    }

    @HadronTest
    @Covers(PlayerToggleSprintEvent.class)
    void sprintingFiresAndCancelledStaysWalking(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<Boolean> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerToggleSprintEvent e) {
                asked.add(e.isSprinting());
                e.setCancelled(true);
            }
        });
        command(alice, ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        t.assertEquals(List.of(true), asked, "the sprint states asked about");
        t.assertFalse(alice.player().isSprinting(), "alice should not be sprinting");
    }

    private static void allowFlight(TestPlayer p, boolean allowed) {
        p.player().getAbilities().mayfly = allowed;
        if (!allowed) p.player().getAbilities().flying = false;
        p.player().onUpdateAbilities();
    }

    @HadronTest
    @Covers(PlayerToggleFlightEvent.class)
    void startingToFlyFiresAndCancelledStaysGrounded(TestContext t) {
        TestPlayer alice = t.player("alice");
        allowFlight(alice, true);
        List<Boolean> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerToggleFlightEvent e) {
                asked.add(e.isFlying());
                e.setCancelled(true);
            }
        });
        Abilities flying = new Abilities();
        flying.flying = true;
        alice.player().connection.handlePlayerAbilities(new ServerboundPlayerAbilitiesPacket(flying));
        t.assertEquals(List.of(true), asked, "the flight states asked about");
        t.assertFalse(alice.player().getAbilities().flying, "alice should not be flying");
    }

    @HadronTest
    @Covers(PlayerAbilitiesChangeEvent.class)
    void grantingFlightFiresAndCancelledTakesItBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<PlayerAbilitiesChangeEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerAbilitiesChangeEvent e) {
                events.add(e);
                e.setCancelled(true);
            }
        });
        allowFlight(alice, true);
        t.assertEquals(1, events.size(), "the number of PlayerAbilitiesChangeEvents");
        t.assertTrue(events.get(0).getAllowFlight() && !events.get(0).getOldAllowFlight(),
                "the event should be flight going from refused to allowed");
        t.assertFalse(alice.player().getAbilities().mayfly, "the refused grant should leave alice unable to fly");
    }

    @HadronTest
    @Covers(PlayerFlightRevokeEvent.class)
    void revokingFlightIsReported(TestContext t) {
        TestPlayer alice = t.player("alice");
        allowFlight(alice, true);
        int[] revoked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerFlightRevokeEvent e) {
                revoked[0]++;
            }
        });
        allowFlight(alice, false);
        t.assertEquals(1, revoked[0], "the number of PlayerFlightRevokeEvents");
        t.assertFalse(alice.player().getAbilities().mayfly, "alice should no longer be able to fly");
    }

    @HadronTest
    @Covers(PlayerGameModeChangeEvent.class)
    void aGameModeCommandFiresAndCancelledKeepsTheMode(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.setOp(true);
        List<PlayerGameModeChangeEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerGameModeChangeEvent e) {
                events.add(e);
                e.setCancelled(true);
            }
        });
        alice.command("gamemode creative");
        t.assertEquals(1, events.size(), "the number of PlayerGameModeChangeEvents");
        t.assertEquals(GameType.SURVIVAL, events.get(0).getOldGameMode(), "the old mode");
        t.assertEquals(GameType.CREATIVE, events.get(0).getNewGameMode(), "the new mode");
        t.assertEquals(GameType.SURVIVAL, alice.player().gameMode.getGameModeForPlayer(), "alice's game mode");
    }

    @HadronTest
    @Covers(PlayerExpChangeEvent.class)
    void aChangedExperienceGainIsWhatTheyGet(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<Integer> asked = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerExpChangeEvent e) {
                asked.add(e.getAmount());
                e.setAmount(3);
            }
        });
        console(t, "xp add alice 10 points");
        t.assertEquals(List.of(10), asked, "the amounts asked about");
        t.assertEquals(3, alice.player().totalExperience, "alice's total experience");
    }

    @HadronTest
    @Covers(PlayerExpChangeEvent.class)
    void aCancelledExperienceGainGivesNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerExpChangeEvent e) {
                e.setCancelled(true);
            }
        });
        console(t, "xp add alice 10 points");
        t.assertEquals(0, alice.player().totalExperience, "alice's total experience");
    }

    @HadronTest
    @Covers(PlayerLevelChangeEvent.class)
    void gainingLevelsIsReportedOnce(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<int[]> changes = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerLevelChangeEvent e) {
                changes.add(new int[] {e.getOldLevel(), e.getNewLevel()});
            }
        });
        console(t, "xp add alice 5 levels");
        t.tick(2);
        t.assertEquals(1, changes.size(), "the number of PlayerLevelChangeEvents");
        t.assertEquals(0, changes.get(0)[0], "the old level");
        t.assertEquals(5, changes.get(0)[1], "the new level");
    }

    @HadronTest
    @Covers(PlayerAdvancementDoneEvent.class)
    void anAdvancementDoneIsReported(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<String> done = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerAdvancementDoneEvent e) {
                if (e.getPlayer() == alice.player()) done.add(e.getAdvancement().id().toString());
            }
        });
        console(t, "advancement grant alice only minecraft:story/root");
        t.assertEquals(List.of("minecraft:story/root"), done, "the advancements reported");
    }

    private static void swapHands(TestPlayer p) {
        p.player().connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    @HadronTest
    @Covers(PlayerSwapHandItemsEvent.class)
    void aCancelledHandSwapLeavesBothHands(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIAMOND));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerSwapHandItemsEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        swapHands(alice);
        t.assertEquals(1, asked[0], "the number of PlayerSwapHandItemsEvents");
        t.assertHolding(alice, Items.DIAMOND, 1);
        t.assertTrue(alice.player().getOffhandItem().isEmpty(), "the off hand should stay empty");
    }

    @HadronTest
    @Covers(PlayerSwapHandItemsEvent.class)
    void aChangedHandSwapPutsTheChosenItems(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.hold(new ItemStack(Items.DIAMOND));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerSwapHandItemsEvent e) {
                t.assertTrue(e.getOffHandItem().is(Items.DIAMOND), "the diamond should be headed for the off hand");
                e.setMainHandItem(new ItemStack(Items.EMERALD));
            }
        });
        swapHands(alice);
        t.assertHolding(alice, Items.EMERALD, 1);
        t.assertTrue(alice.player().getItemInHand(InteractionHand.OFF_HAND).is(Items.DIAMOND),
                "the off hand should hold the diamond");
    }

    /** A bed east of alice, foot at pos(1, 0, 0), at night. Returns the overworld's time to put back. */
    private static long bedAtNight(TestContext t) {
        ServerLevel overworld = t.server().overworld(); // test worlds take their time from it
        long time = overworld.getDayTime();
        overworld.setDayTime(18000);
        t.setBlock(t.pos(1, 0, 0), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT));
        t.setBlock(t.pos(2, 0, 0), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.HEAD));
        t.tick(); // the sky darkens on the world's tick
        return time;
    }

    @HadronTest
    @Covers(PlayerBedEnterEvent.class)
    void goingToBedFiresAndCancelledStaysAwake(TestContext t) {
        TestPlayer alice = t.player("alice");
        long time = bedAtNight(t);
        try {
            List<BlockPos> beds = new ArrayList<>();
            t.registerEvents(new Listener() {
                @EventHandler
                void on(PlayerBedEnterEvent e) {
                    beds.add(e.getBed());
                    e.setCancelled(true);
                }
            });
            alice.useBlock(t.pos(1, 0, 0));
            t.assertEquals(1, beds.size(), "the number of PlayerBedEnterEvents (0 means vanilla refused the bed)");
            t.assertTrue(beds.get(0).equals(t.pos(1, 0, 0)) || beds.get(0).equals(t.pos(2, 0, 0)),
                    "the event's bed should be the one clicked, but was " + t.describe(beds.get(0)));
            t.assertFalse(alice.player().isSleeping(), "alice should stay awake");
        } finally {
            t.server().overworld().setDayTime(time);
        }
    }

    @HadronTest
    @Covers(PlayerBedLeaveEvent.class)
    void leavingABedIsReported(TestContext t) {
        TestPlayer alice = t.player("alice");
        long time = bedAtNight(t);
        try {
            alice.useBlock(t.pos(1, 0, 0));
            t.assertTrue(alice.player().isSleeping(), "alice should be asleep");
            List<Optional<BlockPos>> beds = new ArrayList<>();
            t.registerEvents(new Listener() {
                @EventHandler
                void on(PlayerBedLeaveEvent e) {
                    beds.add(e.getBed());
                }
            });
            command(alice, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING);
            t.assertEquals(1, beds.size(), "the number of PlayerBedLeaveEvents");
            t.assertTrue(beds.get(0).isPresent(), "the event should say which bed");
            t.assertFalse(alice.player().isSleeping(), "alice should be awake");
        } finally {
            t.server().overworld().setDayTime(time);
        }
    }

    @HadronTest
    @Covers(PlayerChangedWorldEvent.class)
    void changingWorldsIsReportedWithTheOldWorld(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stood = t.pos(3, 0, -2);
        alice.teleport(stood.getX() + 0.5, stood.getY(), stood.getZ() + 0.5);
        List<ServerLevel> from = new ArrayList<>();
        List<Place> left = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerChangedWorldEvent e) {
                if (e.getPlayer() != alice.player()) return;
                from.add(e.getFrom());
                left.add(Place.Spot.at(e.getFrom(), e.getFromPosition()));
                left.addAll(e.getPlaces().subList(1, e.getPlaces().size()));
            }
        });
        alice.player().teleportTo(t.server().overworld(), 0.5, 120, 0.5, 0.0F, 0.0F);
        t.tick(2);
        t.assertEquals(List.of(t.level()), from, "the worlds reported as left");
        t.assertEquals(List.of(Place.Spot.at(t.level(), stood), Place.Spot.at(t.level(), stood)), left,
                "where she stood when she left, as getFromPosition() and as the event's place after her own");
        t.assertEquals(t.server().overworld(), alice.player().serverLevel(), "alice's world");
    }

    @HadronTest
    @Covers(PlayerMoveEvent.class)
    void walkingIntoTheNextBlockIsAMove(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<PlayerMoveEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerMoveEvent e) {
                events.add(e);
            }
        });
        alice.moveTo(t.pos(1, 0, 0));
        t.tick();
        t.assertEquals(1, events.size(), "the number of PlayerMoveEvents for one block");
        t.assertEquals(t.pos(0, 0, 0), events.get(0).getFromBlock(), "the block moved from");
        t.assertEquals(t.pos(1, 0, 0), events.get(0).getToBlock(), "the block moved to");
    }

    @HadronTest
    @Covers(PlayerMoveEvent.class)
    void aCancelledMovePutsThePlayerBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerMoveEvent e) {
                e.setCancelled(true);
            }
        });
        // Refused where the server applies her movement packet, so the step never lands: moveTo says so.
        AssertionError refused = null;
        try {
            alice.moveTo(t.pos(1, 0, 0));
        } catch (AssertionError e) {
            refused = e;
        }
        t.assertTrue(refused != null && refused.getMessage().contains("cancelled PlayerMoveEvents"),
                "moveTo should report the step refused, but " + (refused == null ? "it went through" : refused));
        t.tick(3);
        ServerPlayer p = alice.player();
        t.assertEquals(t.pos(0, 0, 0), p.blockPosition(), "the block alice is in");
    }
}
