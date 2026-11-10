package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.reach;
import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashEvent;
import dev.iredsc.hadron.api.event.entity.EntityShearEvent;
import dev.iredsc.hadron.api.event.entity.EntityUnleashEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriterionProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Leads, shears and armor stands: each asked once, before anything changes, and fully undone when refused. */
public final class HandlingTests {
    private static <E> E one(TestContext t, List<E> heard, String what) {
        t.assertEquals(1, heard.size(), "the number of " + what + "s fired");
        return heard.get(0);
    }

    // ---- Leads ---------------------------------------------------------------------------------------------------

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void puttingALeadOnACowIsThePlayersLeash(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.LEAD));
        List<EntityLeashEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                heard.add(e);
            }
        });
        String before = reach(t, alice, cow);
        alice.interact(cow);
        t.assertTrue(heard.size() == 1, "one click should fire one EntityLeashEvent, but fired " + heard.size()
                + "; before the click " + before + ", after " + reach(t, alice, cow));
        EntityLeashEvent e = heard.get(0);
        t.assertEquals(cow, e.getEntity(), "the entity leashed");
        t.assertEquals(alice.player(), e.getHolder(), "the holder");
        assertPlayer(t, e.getActor(), alice, "Leashing the cow");
        t.assertEquals(alice.player(), cow.getLeashHolder(), "the cow's leash holder");
        t.assertInventory(alice, Items.LEAD, 0);
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedLeashKeepsTheLeadInHand(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.LEAD));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(cow);
        t.assertEquals(1, asked[0], "the number of EntityLeashEvents for one click");
        t.assertFalse(cow.isLeashed(), "the cow should not be leashed, but is held by " + cow.getLeashHolder());
        t.assertHolding(alice, Items.LEAD, 1);
    }

    @HadronTest
    @Covers(EntityUnleashEvent.class)
    void takingALeadOffIsThePlayersUnleash(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        List<EntityUnleashEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUnleashEvent e) {
                heard.add(e);
            }
        });
        alice.interact(cow);
        EntityUnleashEvent e = one(t, heard, "EntityUnleashEvent");
        t.assertEquals(cow, e.getEntity(), "the entity unleashed");
        t.assertEquals(alice.player(), e.getHolder(), "the holder it is taken from");
        assertPlayer(t, e.getActor(), alice, "Unleashing the cow");
        t.assertFalse(cow.isLeashed(), "the cow should be free");
    }

    /** A click the server counts as done makes the player swing, as it counts it for advancements. */
    private static void assertNoSwing(TestContext t, TestPlayer player, String what) {
        t.assertEquals(List.of(), player.received(ClientboundAnimatePacket.class), what + " should not swing "
                + "the arm or count as an interaction");
    }

    /** Whether leashing a temperate frog counted toward vanilla's "leash every frog" advancement. */
    private static boolean frogLeashCounted(TestContext t, TestPlayer player) {
        AdvancementHolder frogs = t.server().getAdvancements()
                .get(ResourceLocation.withDefaultNamespace("husbandry/leash_all_frog_variants"));
        if (frogs == null) throw new AssertionError("vanilla's husbandry/leash_all_frog_variants advancement is missing");
        CriterionProgress temperate = player.player().getAdvancements().getOrStartProgress(frogs)
                .getCriterion("minecraft:temperate");
        if (temperate == null) throw new AssertionError("the frog advancement has no minecraft:temperate criterion");
        return temperate.isDone();
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedLeashIsNotCountedAsAnInteraction(TestContext t) {
        TestPlayer alice = t.player("alice");
        Frog frog = spawn(t, EntityType.FROG, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.LEAD));
        boolean[] refuse = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(refuse[0]);
            }
        });
        t.tick();
        alice.clearReceived();
        alice.interact(frog);
        t.tick();
        assertNoSwing(t, alice, "A refused leash");
        t.assertFalse(frogLeashCounted(t, alice), "a refused leash should not count toward an advancement");
        refuse[0] = false;
        alice.interact(frog);
        t.assertTrue(frogLeashCounted(t, alice), "a leash should count toward the frog advancement");
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aTieRefusedInsideAnAllowedLeashDoesNotFailIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Frog frog = spawn(t, EntityType.FROG, t.pos(2, 0, 0));
        BlockPos fence = t.pos(0, 0, 2);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(0, 0, 3));
        cow.setLeashedTo(alice.player(), true);
        alice.hold(new ItemStack(Items.LEAD));
        UseOnContext tie = new UseOnContext(alice.player(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fence), Direction.UP, fence, false));
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                if (e.getEntity() == cow) {
                    e.setCancelled(true);
                } else {
                    alice.player().getMainHandItem().useOn(tie); // a plugin tying the player's other mobs meanwhile
                }
            }
        });
        alice.interact(frog);
        t.assertEquals(alice.player(), frog.getLeashHolder(), "the frog's leash holder");
        t.assertTrue(frogLeashCounted(t, alice), "a leash should count even when a tie refused during it did not");
    }

    @HadronTest
    @Covers(EntityUnleashEvent.class)
    void aRefusedUnleashWithShearsInHandDoesNotShear(TestContext t) {
        TestPlayer alice = t.player("alice");
        Sheep sheep = spawn(t, EntityType.SHEEP, t.pos(2, 0, 0));
        sheep.setLeashedTo(alice.player(), true);
        alice.hold(new ItemStack(Items.SHEARS));
        int[] sheared = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUnleashEvent e) {
                e.setCancelled(true);
            }

            @EventHandler
            void on(EntityShearEvent e) {
                sheared[0]++;
            }
        });
        t.tick();
        alice.clearReceived();
        alice.interact(sheep);
        t.tick();
        t.assertEquals(alice.player(), sheep.getLeashHolder(), "the sheep's leash holder");
        t.assertFalse(sheep.isSheared(), "the refused click should not fall through to shearing");
        t.assertEquals(0, sheared[0], "EntityShearEvents fired");
        assertNoSwing(t, alice, "A refused unleash");
    }

    @HadronTest
    @Covers(EntityUnleashEvent.class)
    void aRefusedUnleashKeepsTheCowOnTheLead(TestContext t) {
        TestPlayer alice = t.player("alice");
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 0));
        cow.setLeashedTo(alice.player(), true);
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityUnleashEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(cow);
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntityUnleashEvents for one click");
        t.assertEquals(alice.player(), cow.getLeashHolder(), "the cow's leash holder");
        t.assertTrue(near(t, ItemEntity.class).isEmpty(), "no lead should drop, but found " + near(t, ItemEntity.class));
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void tyingToAFenceIsALeashToTheKnot(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        List<EntityLeashEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                heard.add(e);
            }
        });
        alice.useBlock(fence, Direction.UP);
        EntityLeashEvent e = one(t, heard, "EntityLeashEvent");
        t.assertTrue(e.getHolder() instanceof LeashFenceKnotEntity knot && knot.getPos().equals(fence),
                "the holder should be the knot on the fence, but was " + e.getHolder());
        assertPlayer(t, e.getActor(), alice, "Tying the cow");
        t.assertEquals(e.getHolder(), cow.getLeashHolder(), "the cow's leash holder");
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieLeavesNoKnot(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.useBlock(fence, Direction.UP);
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntityLeashEvents for one click");
        t.assertEquals(alice.player(), cow.getLeashHolder(), "the cow's leash holder");
        t.assertTrue(near(t, LeashFenceKnotEntity.class).isEmpty(), "no knot should be left on the fence, but found "
                + near(t, LeashFenceKnotEntity.class));
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieKeepsTheKnotIfAListenerTiedSomethingElseToIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        Cow bobs = spawn(t, EntityType.COW, t.pos(0, 0, 2));
        bobs.setLeashedTo(bob.player(), true);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                if (e.getEntity() != cow) return;
                bobs.setLeashedTo(e.getHolder(), true); // a plugin moving bob's cow to the same knot
                e.setCancelled(true);
            }
        });
        alice.useBlock(fence, Direction.UP);
        t.tick();
        t.assertEquals(alice.player(), cow.getLeashHolder(), "the refused cow's leash holder");
        t.assertTrue(bobs.getLeashHolder() instanceof LeashFenceKnotEntity knot && !knot.isRemoved(),
                "bob's cow should stay tied to a knot still on the fence, but is held by " + bobs.getLeashHolder());
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieKeepsAKnotThatWasAlreadyThere(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow tied = spawn(t, EntityType.COW, t.pos(2, 0, 9)); // past the seven blocks a lead click looks
        Cow held = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(t.level(), fence); // not ticked yet
        tied.setLeashedTo(knot, true);
        held.setLeashedTo(alice.player(), true);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(true);
            }
        });
        alice.useBlock(fence, Direction.UP);
        t.tick();
        t.assertFalse(knot.isRemoved(), "the knot that was already on the fence should stay");
        t.assertEquals(knot, tied.getLeashHolder(), "the tied cow's leash holder");
        t.assertEquals(alice.player(), held.getLeashHolder(), "the held cow's leash holder");
    }

    /** A sculk sensor beside the fence, settled, so only what happens next can set it off. */
    private static BlockPos sensor(TestContext t) {
        BlockPos sensor = t.pos(4, 0, 0);
        t.setBlock(sensor, Blocks.SCULK_SENSOR.defaultBlockState());
        t.tick(10);
        t.assertFalse(sensed(t, sensor, 1), "the sensor should be quiet before the click");
        return sensor;
    }

    private static boolean sensed(TestContext t, BlockPos sensor, int ticks) {
        for (int i = 0; i < ticks; i++) {
            if (SculkSensorBlock.getPhase(t.level().getBlockState(sensor)) != SculkSensorPhase.INACTIVE) return true;
            t.tick();
        }
        return SculkSensorBlock.getPhase(t.level().getBlockState(sensor)) != SculkSensorPhase.INACTIVE;
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aTieSetsOffASculkSensorAndARefusedOneDoesNot(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        boolean[] refuse = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(refuse[0]);
            }
        });
        BlockPos sensor = sensor(t);
        alice.useBlock(fence, Direction.UP);
        t.assertFalse(sensed(t, sensor, 20), "a refused tie should make no vibration");
        refuse[0] = false;
        alice.useBlock(fence, Direction.UP);
        t.assertTrue(sensed(t, sensor, 20), "a tie should make the attach vibration");
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieToAKnotMakesNoVibration(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(t.level(), fence);
        Cow held = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        held.setLeashedTo(alice.player(), true);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(true);
            }
        });
        BlockPos sensor = sensor(t);
        alice.interact(knot);
        t.assertFalse(sensed(t, sensor, 20), "a refused tie to a knot should make no vibration");
        t.assertFalse(knot.isRemoved(), "the knot should stay");
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieAtAFenceCountsForNothingAndIsAskedOnce(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        alice.hold(new ItemStack(Items.LEAD)); // vanilla tries the lead on the fence too if the fence's use fails
        boolean[] refuse = {true};
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                asked[0]++;
                e.setCancelled(refuse[0]);
            }
        });
        try (CriterionWatch<?> blockUses = CriterionWatch.blockUses(alice);
                CriterionWatch<?> itemUses = CriterionWatch.itemsOnBlocks(alice)) {
            alice.useBlock(fence, Direction.UP);
            t.assertEquals(1, asked[0], "EntityLeashEvents for one refused click");
            t.assertFalse(blockUses.counted() || itemUses.counted(), "a refused tie should count for no advancement");
            refuse[0] = false;
            alice.useBlock(fence, Direction.UP);
            t.assertTrue(blockUses.counted() || itemUses.counted(), "a tie should count as a use of the fence");
        }
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedSneakingTieIsNotAUseOfTheLead(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        alice.hold(new ItemStack(Items.LEAD));
        alice.player().setShiftKeyDown(true); // sneaking skips the fence and uses the lead on it instead
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        try (CriterionWatch<?> itemUses = CriterionWatch.itemsOnBlocks(alice)) {
            alice.useBlock(fence, Direction.UP);
            t.assertEquals(1, asked[0], "EntityLeashEvents for one refused click");
            t.assertEquals(0, alice.player().getStats().getValue(Stats.ITEM_USED.get(Items.LEAD)),
                    "times the lead counts as used");
            t.assertFalse(itemUses.counted(), "a refused tie should count for no advancement");
            t.assertEquals(alice.player(), cow.getLeashHolder(), "the cow's leash holder");
        }
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedDirectTieDoesNotFailTheNextOne(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        ItemStack lead = new ItemStack(Items.LEAD);
        alice.hold(lead);
        boolean[] refuse = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(refuse[0]);
            }
        });
        // How a fake player (a turtle, a drone) uses an item: straight on the stack, not through a click.
        UseOnContext use = new UseOnContext(alice.player(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(fence), Direction.UP, fence, false));
        t.assertEquals(InteractionResult.FAIL, lead.useOn(use), "the refused direct use");
        refuse[0] = false;
        t.assertTrue(lead.useOn(use).consumesAction(), "an allowed direct use after a refused one should succeed");
        t.assertTrue(cow.getLeashHolder() instanceof LeashFenceKnotEntity, "the cow should be tied to the fence");
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieToAKnotCountsForNothing(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(t.level(), fence);
        Cow cow = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        cow.setLeashedTo(alice.player(), true);
        boolean[] refuse = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(refuse[0]);
            }
        });
        try (CriterionWatch<?> clicks = CriterionWatch.entityClicks(alice)) {
            alice.interact(knot);
            t.assertEquals(alice.player(), cow.getLeashHolder(), "the cow's leash holder after the refused tie");
            t.assertFalse(clicks.counted(), "a refused tie should count for no advancement");
            refuse[0] = false;
            alice.interact(knot);
            t.assertEquals(knot, cow.getLeashHolder(), "the cow's leash holder after the tie");
            t.assertTrue(clicks.counted(), "a tie should count as an interaction");
        }
    }

    @HadronTest
    @Covers(EntityLeashEvent.class)
    void aRefusedTieToAKnotKeepsTheKnotAndItsMobs(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos fence = t.pos(2, 0, 0);
        t.setBlock(fence, Blocks.OAK_FENCE.defaultBlockState());
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(t.level(), fence);
        Cow tied = spawn(t, EntityType.COW, t.pos(0, 0, 2));
        tied.setLeashedTo(knot, true);
        Cow held = spawn(t, EntityType.COW, t.pos(2, 0, 2));
        held.setLeashedTo(alice.player(), true);
        t.tick();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityLeashEvent e) {
                e.setCancelled(true);
            }
        });
        alice.interact(knot);
        t.tick();
        t.assertFalse(knot.isRemoved(), "the knot should stay on the fence");
        t.assertEquals(knot, tied.getLeashHolder(), "the tied cow's leash holder");
        t.assertEquals(alice.player(), held.getLeashHolder(), "the held cow's leash holder");
    }

    // ---- Shears --------------------------------------------------------------------------------------------------

    @HadronTest
    @Covers(EntityShearEvent.class)
    void shearingASheepIsThePlayersShear(TestContext t) {
        TestPlayer alice = t.player("alice");
        Sheep sheep = spawn(t, EntityType.SHEEP, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.SHEARS));
        List<EntityShearEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityShearEvent e) {
                heard.add(e);
            }
        });
        alice.interact(sheep);
        EntityShearEvent e = one(t, heard, "EntityShearEvent");
        t.assertEquals(sheep, e.getEntity(), "the entity sheared");
        t.assertTrue(e.getItem().is(Items.SHEARS), "the tool should be the shears, but was " + e.getItem());
        assertPlayer(t, e.getActor(), alice, "Shearing the sheep");
        t.assertTrue(sheep.isSheared(), "the sheep should be sheared");
        alice.interact(sheep);
        t.assertEquals(1, heard.size(), "EntityShearEvents after clicking a sheared sheep again");
    }

    @HadronTest
    @Covers(EntityShearEvent.class)
    void aRefusedShearLeavesTheWoolAndTheShears(TestContext t) {
        TestPlayer alice = t.player("alice");
        Sheep sheep = spawn(t, EntityType.SHEEP, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.SHEARS));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityShearEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(sheep);
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntityShearEvents for one click");
        t.assertFalse(sheep.isSheared(), "the sheep should keep its wool");
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the shears' wear");
        t.assertTrue(near(t, ItemEntity.class).isEmpty(), "no wool should drop, but found " + near(t, ItemEntity.class));
    }

    @HadronTest
    @Covers(EntityShearEvent.class)
    void aRefusedDispenserShearFailsTheDispenser(TestContext t) {
        t.player("watcher");
        BlockPos dispenser = t.pos(2, 0, 0);
        t.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        DispenserBlockEntity be = (DispenserBlockEntity) t.level().getBlockEntity(dispenser);
        be.setItem(0, new ItemStack(Items.SHEARS));
        Sheep sheep = spawn(t, EntityType.SHEEP, t.pos(3, 0, 0));
        List<EntityShearEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityShearEvent e) {
                heard.add(e);
                e.setCancelled(true);
            }
        });
        t.await(sheep.getName().getString() + " to be loaded in the world",
                () -> t.level().getEntity(sheep.getId()) == sheep, 60);
        t.setBlock(t.pos(2, 0, 1), Blocks.REDSTONE_BLOCK);
        t.await("the dispenser to fire", () -> !heard.isEmpty(), 60);
        t.tick(2);
        EntityShearEvent e = one(t, heard, "EntityShearEvent");
        t.assertTrue(e.getActor() instanceof Actor.Machine m && m.pos().equals(dispenser),
                "the actor should be the dispenser's Actor.Machine, but was " + e.getActor());
        t.assertTrue(e.getPlayer().isEmpty(), "no player is behind a bare dispenser, but got " + e.getPlayer());
        t.assertFalse(sheep.isSheared(), "the sheep should keep its wool");
        t.assertEquals(0, be.getItem(0).getDamageValue(), "the dispenser's shears' wear");
    }

    // ---- Armor stands --------------------------------------------------------------------------------------------

    @HadronTest
    @Covers(ArmorStandManipulateEvent.class)
    void puttingAHelmetOnAStandIsAManipulation(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.IRON_HELMET));
        List<ArmorStandManipulateEvent> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ArmorStandManipulateEvent e) {
                heard.add(e);
            }
        });
        alice.interact(stand);
        ArmorStandManipulateEvent e = one(t, heard, "ArmorStandManipulateEvent");
        t.assertEquals(stand, e.getEntity(), "the stand");
        t.assertEquals(EquipmentSlot.HEAD, e.getSlot(), "the slot");
        t.assertEquals(InteractionHand.MAIN_HAND, e.getHand(), "the hand");
        t.assertTrue(e.getPlayerItem().is(Items.IRON_HELMET), "the item put on should be the helmet, but was "
                + e.getPlayerItem());
        t.assertTrue(e.getArmorStandItem().isEmpty(), "the stand's slot should have been empty, but held "
                + e.getArmorStandItem());
        assertPlayer(t, e.getActor(), alice, "Dressing the stand");
        t.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the stand should wear the helmet");
    }

    @HadronTest
    @Covers(ArmorStandManipulateEvent.class)
    void aRefusedManipulationLeavesStandAndHand(TestContext t) {
        TestPlayer alice = t.player("alice");
        ArmorStand stand = spawn(t, EntityType.ARMOR_STAND, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.IRON_HELMET));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ArmorStandManipulateEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.interact(stand);
        t.tick();
        t.assertEquals(1, asked[0], "the number of ArmorStandManipulateEvents for one click");
        t.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the stand should wear nothing");
        t.assertHolding(alice, Items.IRON_HELMET, 1);
    }
}
