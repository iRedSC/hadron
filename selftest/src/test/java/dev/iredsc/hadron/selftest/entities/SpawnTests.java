package dev.iredsc.hadron.selftest.entities;

import static dev.iredsc.hadron.selftest.entities.Fixtures.assertPlayer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.assertServer;
import static dev.iredsc.hadron.selftest.entities.Fixtures.console;
import static dev.iredsc.hadron.selftest.entities.Fixtures.near;
import static dev.iredsc.hadron.selftest.entities.Fixtures.ready;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** EntitySpawnEvent, ProjectileLaunchEvent and HangingPlaceEvent: who spawned it, and a refusal costs nothing. */
public final class SpawnTests {
    @HadronTest
    void aConsoleSummonIsTheServersCommandSpawn(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        ready(t);
        List<EntitySpawnEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) events.add(e);
            }
        });
        BlockPos at = t.pos(2, 0, 0);
        console(t, "summon minecraft:pig " + at.getX() + " " + at.getY() + " " + at.getZ());
        t.assertEquals(1, events.size(), "the number of EntitySpawnEvents for the summoned pig");
        assertServer(t, events.get(0).getActor(), "A console /summon");
        t.assertEquals(Optional.of(MobSpawnType.COMMAND), events.get(0).getSpawnReason(), "the spawn reason");
        t.assertEquals(1, near(t, Pig.class).size(), "the number of pigs in the world");
    }

    @HadronTest
    void aRefusedSummonLeavesNoEntity(TestContext t) {
        t.player("alice"); // entities only tick, and commands only find them, near a player
        ready(t);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) e.setCancelled(true);
            }
        });
        BlockPos at = t.pos(2, 0, 0);
        console(t, "summon minecraft:pig " + at.getX() + " " + at.getY() + " " + at.getZ());
        t.tick();
        t.assertEquals(0, near(t, Pig.class).size(), "the number of pigs after a refused summon");
    }

    @HadronTest
    void aSpawnEggIsThePlayersSpawn(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.PIG_SPAWN_EGG, 2));
        List<EntitySpawnEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) events.add(e);
            }
        });
        alice.useBlock(t.pos(1, -1, 0));
        t.assertEquals(1, events.size(), "the number of EntitySpawnEvents for the egg's pig");
        assertPlayer(t, events.get(0).getActor(), alice, "A spawn egg's pig");
        t.assertEquals(Optional.of(MobSpawnType.SPAWN_EGG), events.get(0).getSpawnReason(), "the spawn reason");
        t.assertHolding(alice, Items.PIG_SPAWN_EGG, 1);
    }

    @HadronTest
    void aRefusedSpawnEggIsNotSpent(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.PIG_SPAWN_EGG, 2));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Pig) {
                    asked[0]++;
                    e.setCancelled(true);
                }
            }
        });
        alice.useBlock(t.pos(1, -1, 0));
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntitySpawnEvents for the egg's pig");
        t.assertEquals(0, near(t, Pig.class).size(), "the number of pigs after a refused egg");
        t.assertHolding(alice, Items.PIG_SPAWN_EGG, 2);
    }

    @HadronTest
    void aThrownSnowballIsLaunchedByItsThrower(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.SNOWBALL, 4));
        List<ProjectileLaunchEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ProjectileLaunchEvent e) {
                events.add(e);
            }
        });
        alice.useItem();
        t.assertEquals(1, events.size(), "the number of ProjectileLaunchEvents");
        ProjectileLaunchEvent e = events.get(0);
        t.assertTrue(e.getEntity() instanceof Snowball, "the projectile should be a snowball, but was " + e.getEntity());
        t.assertEquals(Optional.of(alice.player()), e.getShooter(), "the shooter");
        assertPlayer(t, e.getActor(), alice, "A thrown snowball");
        t.assertHolding(alice, Items.SNOWBALL, 3);
    }

    @HadronTest
    void aRefusedSnowballIsGivenBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.SNOWBALL, 4));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ProjectileLaunchEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.useItem();
        t.tick();
        t.assertEquals(1, asked[0], "the number of ProjectileLaunchEvents");
        t.assertEquals(0, near(t, Snowball.class).size(), "the number of snowballs in flight");
        t.assertHolding(alice, Items.SNOWBALL, 4);
    }

    @HadronTest
    void aRefusedArrowFromABowKeepsTheArrowAndTheBow(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.hold(new ItemStack(Items.BOW));
        alice.give(new ItemStack(Items.ARROW, 5));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ProjectileLaunchEvent e) {
                if (e.getEntity() instanceof AbstractArrow) {
                    asked[0]++;
                    e.setCancelled(true);
                }
            }
        });
        alice.useItem(); // starts drawing
        t.tick(25);
        // The harness has no release action; this is the packet a client sends on letting go of the button.
        alice.player().connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        t.tick();
        t.assertEquals(1, asked[0], "the number of ProjectileLaunchEvents for the arrow (0 means the bow never "
                + "fired: it wasn't drawn long enough)");
        t.assertEquals(0, near(t, AbstractArrow.class).size(), "the number of arrows in flight");
        t.assertInventory(alice, Items.ARROW, 5);
        t.assertEquals(0, alice.player().getMainHandItem().getDamageValue(), "the bow's damage");
    }

    @HadronTest
    void aRefusedBoatIsGivenBack(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        alice.player().setXRot(40.0F); // at the floor ahead of her: a boat can't go where she stands
        alice.hold(new ItemStack(Items.OAK_BOAT));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntitySpawnEvent e) {
                if (e.getEntity() instanceof Boat) {
                    asked[0]++;
                    e.setCancelled(true);
                }
            }
        });
        alice.useItem();
        t.tick();
        t.assertEquals(1, asked[0], "the number of EntitySpawnEvents for the boat");
        t.assertEquals(0, near(t, Boat.class).size(), "the number of boats in the world");
        t.assertHolding(alice, Items.OAK_BOAT, 1);
    }

    @HadronTest
    void aHungItemFrameIsThePlayersOnTheClickedFace(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos wall = t.pos(2, 0, 0);
        t.setBlock(wall, Blocks.STONE);
        alice.hold(new ItemStack(Items.ITEM_FRAME, 2));
        List<HangingPlaceEvent> events = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(HangingPlaceEvent e) {
                events.add(e);
            }
        });
        alice.useBlock(wall, Direction.WEST);
        t.assertEquals(1, events.size(), "the number of HangingPlaceEvents");
        HangingPlaceEvent e = events.get(0);
        assertPlayer(t, e.getActor(), alice, "Hanging an item frame");
        t.assertEquals(wall, e.getBlock(), "the block it hangs on");
        t.assertEquals(Direction.WEST, e.getBlockFace(), "the face it hangs on");
        t.assertEquals(1, near(t, ItemFrame.class).size(), "the number of item frames");
        t.assertHolding(alice, Items.ITEM_FRAME, 1);
    }

    @HadronTest
    void aRefusedItemFrameIsNotSpent(TestContext t) {
        TestPlayer alice = t.player("alice");
        ready(t);
        BlockPos wall = t.pos(2, 0, 0);
        t.setBlock(wall, Blocks.STONE);
        alice.hold(new ItemStack(Items.ITEM_FRAME, 2));
        int[] asked = {0};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(HangingPlaceEvent e) {
                asked[0]++;
                e.setCancelled(true);
            }
        });
        alice.useBlock(wall, Direction.WEST);
        t.tick();
        t.assertEquals(1, asked[0], "the number of HangingPlaceEvents");
        t.assertEquals(0, near(t, ItemFrame.class).size(), "the number of item frames");
        t.assertHolding(alice, Items.ITEM_FRAME, 2);
    }
}
