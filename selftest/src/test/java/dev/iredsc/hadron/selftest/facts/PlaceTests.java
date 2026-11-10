package dev.iredsc.hadron.selftest.facts;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonExtendEvent;
import dev.iredsc.hadron.api.event.entity.EntityBreedEvent;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashBreakEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryCloseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedEnterEvent;
import dev.iredsc.hadron.api.event.player.PlayerLoginEvent;
import dev.iredsc.hadron.api.event.player.PlayerRespawnEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import dev.iredsc.hadron.api.event.server.AccessControlChangeEvent;
import dev.iredsc.hadron.api.event.server.CommandExecuteEvent;
import dev.iredsc.hadron.api.event.server.DataRevealEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.selftest.entities.Fixtures;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

/**
 * Every action says where it happens, as a fact core states (docs/events.md, "Places"): a broad vanilla run in which
 * no event is placed nowhere and none says {@code Unknown} but a command, then each family's places checked against
 * its payload.
 */
public final class PlaceTests {
    private static void require(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("Setup went wrong: " + what);
    }

    /** Waits until {@code pos} ticks, so a piston or a hopper there runs. */
    private static void awaitTicking(TestContext t, BlockPos pos) {
        t.level().setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        t.awaitAsync("the test world's chunk to start ticking", () -> t.level().isPositionEntityTicking(pos), 300);
    }

    /** One event heard, with its places and what was wrong with them, read while its listeners ran. */
    private record Heard(Event event, List<Place> places, List<String> problems) {
        @Override
        public String toString() {
            return event.getEventName() + " " + places + (problems.isEmpty() ? "" : " " + problems);
        }
    }

    /** Getters naming who did it, not what it touches: never one of its places. */
    private static final Set<String> AGENTS = Set.of("getDamager", "getShooter", "getOwner", "getBreeder",
            "getViewer", "getActingPlayer", "getActor", "getEntity");

    /**
     * Every entity an entity event's getters name, other than its own subject and whoever did it, whose place isn't
     * among the event's places: a leaf that names an entity and forgets its place.
     */
    private static List<String> unplacedEntities(EntityEvent e, List<Place> places) {
        List<String> out = new ArrayList<>();
        for (Method m : e.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || !m.getName().startsWith("get")
                    || AGENTS.contains(m.getName())
                    || m.getName().equals("getPlayer") && m.getReturnType() == Optional.class) {
                continue;
            }
            Object value;
            try {
                value = m.invoke(e);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                continue;
            }
            for (Entity named : entities(value)) {
                if (!places.contains(Place.of(named))) {
                    out.add(m.getName() + "() names " + named.getName().getString() + " at "
                            + named.blockPosition().toShortString() + ", which isn't one of its places");
                }
            }
        }
        return out;
    }

    private static List<Entity> entities(Object value) {
        return switch (value) {
            case Entity entity -> List.of(entity);
            case Optional<?> o -> o.map(PlaceTests::entities).orElse(List.of());
            case Collection<?> c -> c.stream().flatMap(v -> entities(v).stream()).toList();
            case null, default -> List.of();
        };
    }

    /** Every placed event heard from now on, its places read while its listeners run. */
    private static List<Heard> hear(TestContext t) {
        List<Heard> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Event event) {
                if (!(event instanceof Placed placed)) return;
                List<Place> places;
                List<String> problems = new ArrayList<>();
                try {
                    places = List.copyOf(placed.getPlaces());
                } catch (RuntimeException e) {
                    heard.add(new Heard(event, List.of(), List.of("getPlaces() threw " + e)));
                    return;
                }
                if (places.isEmpty()) problems.add("no places");
                if (event instanceof EntityEvent e) problems.addAll(unplacedEntities(e, places));
                heard.add(new Heard(event, places, problems));
            }
        });
        return heard;
    }

    /**
     * Vanilla's fixtures, each through what a player does with it: a chest and a double chest opened, shift-clicked
     * into and closed, a furnace opened and smelting, a piston pushing, a boat boarded, moved and left, a teleport,
     * and a console command.
     */
    private static void vanillaRun(TestContext t, TestPlayer alice) {
        // A chest.
        BlockPos chest = t.pos(2, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        alice.hold(new ItemStack(Items.DIRT, 16));
        alice.useBlock(chest);
        t.tick();
        require(alice.player().containerMenu != alice.player().inventoryMenu, "alice should have the chest open");
        alice.clickSlot(54, 0, ClickType.QUICK_MOVE);
        alice.player().closeContainer();
        t.tick();
        // A double chest.
        BlockState half = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        t.setBlock(t.pos(2, 0, 2), half.setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(t.pos(3, 0, 2), half.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        alice.hold(new ItemStack(Items.DIRT, 16));
        alice.useBlock(t.pos(2, 0, 2));
        t.tick();
        require(alice.player().containerMenu != alice.player().inventoryMenu, "alice should have the double chest open");
        alice.clickSlot(81, 0, ClickType.QUICK_MOVE);
        alice.player().closeContainer();
        t.tick();
        // A furnace, opened, then smelting.
        BlockPos furnace = t.pos(-2, 0, 0);
        t.setBlock(furnace, Blocks.FURNACE);
        alice.useBlock(furnace);
        t.tick();
        alice.player().closeContainer();
        AbstractFurnaceBlockEntity oven = (AbstractFurnaceBlockEntity) t.level().getBlockEntity(furnace);
        require(oven != null, "the furnace's block entity");
        oven.setItem(0, new ItemStack(Items.RAW_IRON));
        oven.setItem(1, new ItemStack(Items.COAL));
        t.await("the furnace to smelt", () -> oven.getItem(2).is(Items.IRON_INGOT), 320);
        // A piston pushing stone.
        BlockPos piston = t.pos(-2, 0, 4);
        awaitTicking(t, piston);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.await("the piston to push", () -> t.getBlock(piston.east(2)).is(Blocks.STONE), 20);
        // A boat, boarded, moved and left.
        Boat boat = spawn(t, EntityType.BOAT, t.pos(1, 0, -2));
        alice.interact(boat);
        require(alice.player().getVehicle() == boat, "alice should ride the boat");
        t.tick(2);
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick();
        alice.player().stopRiding();
        t.tick();
        // A teleport, and a console command.
        alice.teleport(t.pos(0, 0, 3).getX() + 0.5, t.pos(0, 0, 3).getY(), t.pos(0, 0, 3).getZ() + 0.5);
        t.tick();
        Fixtures.console(t, "say hello");
        t.tick();
    }

    @HadronTest(timeout = 800)
    @Covers(InventoryOpenEvent.class)
    @Covers(InventoryClickEvent.class)
    @Covers(InventoryCloseEvent.class)
    @Covers(ItemSmeltEvent.class)
    @Covers(BlockPistonExtendEvent.class)
    @Covers(EntityMountEvent.class)
    @Covers(EntityDismountEvent.class)
    @Covers(CommandExecuteEvent.class)
    void everyEventInAVanillaRunStatesWhereItHappens(TestContext t) {
        TestPlayer alice = joined(t);
        List<Heard> heard = hear(t);
        vanillaRun(t, alice);
        // A run that heard none of a family proves nothing about it.
        for (Class<?> family : List.of(BlockEvent.class, EntityEvent.class, ItemEvent.class, InventoryEvent.class,
                CommandExecuteEvent.class, InventoryClickEvent.class, ItemSmeltEvent.class,
                BlockPistonExtendEvent.class, EntityMountEvent.class, EntityDismountEvent.class)) {
            t.assertTrue(heard.stream().anyMatch(h -> family.isInstance(h.event())), "the run should fire a "
                    + family.getSimpleName() + ", but heard " + kinds(heard));
        }
        List<Heard> wrong = heard.stream().filter(h -> !h.problems().isEmpty()).toList();
        t.assertTrue(wrong.isEmpty(), wrong.size() + " event(s) don't state their places:\n  "
                + String.join("\n  ", wrong.stream().map(Heard::toString).toList()));
    }

    @HadronTest(timeout = 800)
    void vanillaFixturesNeverSayUnknownButForACommand(TestContext t) {
        TestPlayer alice = joined(t);
        List<Heard> heard = hear(t);
        vanillaRun(t, alice);
        List<Heard> unknown = heard.stream().filter(h -> h.places().stream().anyMatch(p -> p instanceof Place.Unknown u
                && u.reason() != Place.Unknown.Reason.COMMAND)).toList();
        t.assertTrue(unknown.isEmpty(), "vanilla knows where all of these happen, but " + unknown.size()
                + " said Unknown:\n  " + String.join("\n  ", unknown.stream().map(Heard::toString).toList()));
        t.assertTrue(heard.stream().anyMatch(h -> h.event() instanceof CommandExecuteEvent
                && h.places().equals(List.of(Place.Unknown.anywhere(Place.Unknown.Reason.COMMAND)))),
                "the console's command should be Unknown, anywhere, for a command: " + kinds(heard));
    }

    /**
     * Alice, joined before anything is heard: joining moves her from the overworld into the test's world, whose
     * {@code PlayerChangedWorldEvent} honestly can't say where in the overworld she left from. Not a fixture.
     */
    private static TestPlayer joined(TestContext t) {
        TestPlayer alice = t.player("alice");
        Fixtures.ready(t);
        return alice;
    }

    private static String kinds(List<Heard> heard) {
        Set<String> out = new LinkedHashSet<>();
        for (Heard h : heard) out.add(h.event().getEventName());
        return out.toString();
    }

    @HadronTest
    @Covers(EntityBreedEvent.class)
    @Covers(EntityTargetEvent.class)
    @Covers(EntityLeashBreakEvent.class)
    @Covers(EntityCaptureEvent.class)
    @Covers(EntityLeashEvent.class)
    @Covers(EntityDamageEvent.class)
    @Covers(ProjectileHitEvent.class)
    void everyEntityAnEntityEventNamesIsOneOfItsPlaces(TestContext t) {
        TestPlayer alice = t.player("alice");
        Fixtures.ready(t);
        List<Heard> heard = hear(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        alice.hold(new ItemStack(Items.LEAD));
        alice.interact(pig);
        alice.hold(ItemStack.EMPTY);
        alice.attack(pig);
        Arrow arrow = EntityType.ARROW.create(t.level());
        require(arrow != null, "an arrow");
        arrow.moveTo(pig.getX(), pig.getY() + 3, pig.getZ());
        arrow.setDeltaMovement(0, -1.5, 0);
        t.level().addFreshEntity(arrow);
        t.await("the arrow to hit", () -> heard.stream().anyMatch(h -> h.event() instanceof ProjectileHitEvent), 40);
        // Leaves core fires only in longer runs, built as a bridge would, with each entity somewhere of its own.
        Cow mother = spawn(t, EntityType.COW, t.pos(-3, 0, 2));
        Cow father = spawn(t, EntityType.COW, t.pos(-3, 0, -2));
        Cow calf = spawn(t, EntityType.COW, t.pos(-1, 0, 0));
        Pig carrier = spawn(t, EntityType.PIG, t.pos(4, 0, 3));
        List<EntityEvent> built = List.of(
                new EntityBreedEvent(calf, mother, father, alice.player(), new Actor.Player(alice.player())),
                new EntityTargetEvent(calf, pig, Actor.Unknown.INSTANCE),
                new EntityLeashBreakEvent(pig, carrier, EntityLeashBreakEvent.Reason.DISTANCE, Actor.Nature.INSTANCE),
                new EntityCaptureEvent(calf, new EntityCaptureEvent.Storage.Carried(carrier), Actor.Unknown.INSTANCE));
        List<String> problems = new ArrayList<>();
        for (EntityEvent e : built) {
            for (String p : unplacedEntities(e, e.getPlaces())) problems.add(e.getEventName() + ": " + p);
        }
        for (Heard h : heard) {
            for (String p : h.problems()) problems.add(h.event().getEventName() + ": " + p);
        }
        for (Class<?> kind : List.of(EntityLeashEvent.class, EntityDamageEvent.class, ProjectileHitEvent.class)) {
            t.assertTrue(heard.stream().anyMatch(h -> kind.isInstance(h.event())), "the run should fire a "
                    + kind.getSimpleName() + ", but heard " + kinds(heard));
        }
        t.assertTrue(problems.isEmpty(), problems.size() + " entit(ies) named but not placed:\n  "
                + String.join("\n  ", problems));
        EntityBreedEvent breed = (EntityBreedEvent) built.get(0);
        t.assertFalse(breed.getPlaces().contains(Place.of(alice.player())), "the breeder, who fed them, is not a place "
                + "of the breeding: " + breed.getPlaces());
    }

    // ---- Each family against its payload -------------------------------------------------------------------------

    @HadronTest
    @Covers(InventoryClickEvent.class)
    void aClickInADoubleChestNamesBothHalvesAndThePlayer(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockState half = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        t.setBlock(t.pos(2, 0, 0), half.setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(t.pos(3, 0, 0), half.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        alice.hold(new ItemStack(Items.DIRT, 16));
        alice.useBlock(t.pos(2, 0, 0));
        t.tick();
        require(alice.player().containerMenu != alice.player().inventoryMenu, "alice should have the chest open");
        List<InventoryClickEvent> clicks = new ArrayList<>();
        List<List<Place>> places = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryClickEvent e) {
                clicks.add(e);
                places.add(List.copyOf(e.getPlaces()));
            }
        });
        alice.clickSlot(81, 0, ClickType.QUICK_MOVE);
        t.assertEquals(1, clicks.size(), "clicks heard");
        Set<Place> expected = Set.of(Place.Spot.at(t.level(), t.pos(2, 0, 0)), Place.Spot.at(t.level(), t.pos(3, 0, 0)),
                Place.of(alice.player()));
        t.assertEquals(expected, Set.copyOf(places.get(0)), "a shift-click's places: both halves the dirt can go into, "
                + "and alice's own inventory it leaves");
    }

    @HadronTest
    @Covers(BlockPistonExtendEvent.class)
    void aPistonPushNamesBothEndsOfEachMoveAndItsSweptSpace(TestContext t) {
        BlockPos piston = t.pos(2, 0, 0);
        awaitTicking(t, piston);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
        t.setBlock(piston.east(), Blocks.STONE);
        List<BlockMoveEvent> moves = new ArrayList<>();
        List<List<Place>> places = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockMoveEvent e) {
                moves.add(e);
                places.add(List.copyOf(e.getPlaces()));
            }
        });
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.await("the piston to push", () -> !moves.isEmpty(), 20);
        BlockMoveEvent push = moves.get(0);
        List<Place> got = places.get(0);
        t.assertEquals(Place.Spot.at(t.level(), piston), got.get(0), "the first place: the piston, its getPos()");
        for (BlockMoveEvent.Move m : push.getMoves()) {
            t.assertTrue(got.contains(Place.Spot.at(t.level(), m.from())) && got.contains(Place.Spot.at(t.level(),
                    m.to())), "both ends of the move " + m + " should be places: " + got);
        }
        for (BlockPos swept : push.getSweptSpace()) {
            t.assertTrue(got.contains(Place.Spot.at(t.level(), swept)), swept.toShortString() + ", swept, should be a "
                    + "place: " + got);
        }
        t.assertTrue(got.contains(Place.Spot.at(t.level(), piston.east(2))), "where the stone lands: " + got);
    }

    @HadronTest
    @Covers(InventoryMoveItemEvent.class)
    void aHopperMoveNamesTheChestItTakesFromAndItself(TestContext t) {
        BlockPos hopper = t.pos(2, 0, 0), chest = t.pos(2, 1, 0);
        awaitTicking(t, hopper);
        t.setBlock(hopper, Blocks.HOPPER);
        t.setBlock(chest, Blocks.CHEST);
        List<List<Place>> places = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(InventoryMoveItemEvent e) {
                places.add(List.copyOf(e.getPlaces()));
            }
        });
        ((net.minecraft.world.Container) t.level().getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND));
        t.await("the hopper to pull", () -> !places.isEmpty(), 40);
        t.assertEquals(List.of(Place.Spot.at(t.level(), chest), Place.Spot.at(t.level(), hopper)), places.get(0),
                "a pull's places: the chest it leaves, then the hopper it enters");
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aTeleportNamesBothEndsAndFollowsARedirect(TestContext t) {
        joined(t);
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        BlockPos asked = t.pos(6, 0, 0), redirected = t.pos(-6, 0, 2);
        List<List<Place>> before = new ArrayList<>(), after = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityTeleportEvent e) {
                if (e.getEntity() != pig) return;
                before.add(List.copyOf(e.getPlaces()));
                e.setTo(e.getToLevel(), Fixtures.center(redirected));
                after.add(List.copyOf(e.getPlaces()));
            }
        });
        pig.teleportTo(asked.getX() + 0.5, asked.getY(), asked.getZ() + 0.5);
        t.assertEquals(1, before.size(), "teleports heard");
        t.assertEquals(List.of(Place.Spot.at(t.level(), t.pos(2, 0, 0)), Place.Spot.at(t.level(), asked)),
                before.get(0), "where it leaves, then where it asked to go");
        t.assertEquals(List.of(Place.Spot.at(t.level(), t.pos(2, 0, 0)), Place.Spot.at(t.level(), redirected)),
                after.get(0), "after a listener redirects it, where it goes now");
    }

    @HadronTest
    @Covers(ExplosionEvent.class)
    void anExplosionsPlacesFollowWhatAListenerSpares(TestContext t) {
        joined(t);
        BlockPos centre = t.pos(2, 0, 0);
        Pig pig = spawn(t, EntityType.PIG, t.pos(3, 0, 0));
        Explosion blast = new Explosion(t.level(), null, centre.getX() + 0.5, centre.getY() + 0.5, centre.getZ() + 0.5,
                2f, false, Explosion.BlockInteraction.KEEP);
        List<BlockPos> blocks = new ArrayList<>(List.of(t.pos(2, -1, 0), t.pos(1, -1, 0)));
        List<Entity> hurt = new ArrayList<>(List.of(pig));
        ExplosionEvent e = new ExplosionEvent(Actor.Nature.INSTANCE, t.level(), blast, blocks, hurt);
        Place spared = Place.Spot.at(t.level(), t.pos(1, -1, 0));
        t.assertEquals(List.of(Place.Spot.at(t.level(), centre), Place.Spot.at(t.level(), t.pos(2, -1, 0)), spared,
                Place.of(pig)), List.copyOf(e.getPlaces()), "the centre, each block, each entity");
        e.blockList().remove(t.pos(1, -1, 0));
        e.getEntities().clear();
        t.assertEquals(List.of(Place.Spot.at(t.level(), centre), Place.Spot.at(t.level(), t.pos(2, -1, 0))),
                List.copyOf(e.getPlaces()), "once a block and the pig are spared, their places go");
    }

    @HadronTest
    @Covers(PlayerLoginEvent.class)
    void aLoginIsNowhereButThePlayersOwnAccount(TestContext t) {
        List<List<Place>> places = new ArrayList<>();
        List<UUID> who = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(PlayerLoginEvent e) {
                who.add(e.getPlayer().getUUID());
                places.add(List.copyOf(e.getPlaces()));
            }
        });
        TestPlayer alice = t.player("alice");
        t.assertEquals(List.of(alice.uuid()), who, "logins heard");
        t.assertEquals(List.of(Place.Nowhere.player(alice.uuid())), places.get(0), "a login's places: no world yet, "
                + "only alice herself");
        t.assertEquals(Optional.of(alice.uuid()), Place.Nowhere.player(alice.uuid()).owner(), "whose it is");
    }

    @HadronTest
    @Covers(PlayerRespawnEvent.class)
    @Covers(PlayerBedEnterEvent.class)
    void aRespawnNamesWhereThePlayerArrivesAndABedBothHalves(TestContext t) {
        TestPlayer alice = t.player("alice");
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        require(nether != null, "the test server has a Nether");
        Vec3 arrive = new Vec3(10.5, 70, 10.5);
        PlayerRespawnEvent respawn = new PlayerRespawnEvent(alice.player(), nether, arrive, 0, 0, false);
        t.assertEquals(List.of(Place.of(alice.player()), Place.Spot.at(nether, arrive)), List.copyOf(
                respawn.getPlaces()), "where alice is, then where she arrives");
        BlockPos foot = t.pos(2, 0, 0);
        t.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING,
                Direction.EAST));
        t.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        PlayerBedEnterEvent bed = new PlayerBedEnterEvent(alice.player(), foot);
        t.assertEquals(List.of(Place.of(alice.player()), Place.Spot.at(t.level(), foot),
                Place.Spot.at(t.level(), foot.east())), List.copyOf(bed.getPlaces()), "where alice is, then both "
                + "halves of the bed");
    }

    @HadronTest
    @Covers(ResourceTransferEvent.class)
    void aRemoteTransferNamesItsHoldersNeverItsActor(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        alice.teleport(t.pos(5, 0, 0).getX() + 0.5, t.pos(5, 0, 0).getY(), t.pos(5, 0, 0).getZ() + 0.5);
        bob.teleport(t.pos(-5, 0, 0).getX() + 0.5, t.pos(-5, 0, 0).getY(), t.pos(-5, 0, 0).getZ() + 0.5);
        BlockPos battery = t.pos(0, 0, 6);
        // Bob, from where he stands, drains alice's charged tool into a battery: three places, none of them his.
        ResourceTransferEvent e = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Item(new ItemStack(Items.IRON_PICKAXE), new InventoryHolder.Entity(alice.player())),
                new ResourceHolder.Block(t.level(), battery), 100);
        t.assertEquals(List.of(Place.of(alice.player()), Place.Spot.at(t.level(), battery)), List.copyOf(
                e.getPlaces()), "the tool, where alice holds it, then the battery");
        t.assertFalse(e.getPlaces().contains(Place.of(bob.player())), "bob's own place isn't one of its places");
        ResourceTransferEvent unheld = new ResourceTransferEvent(new Actor.Player(bob.player()), Resource.ENERGY,
                new ResourceHolder.Item(new ItemStack(Items.IRON_PICKAXE)), new ResourceHolder.Block(t.level(),
                        battery), 100);
        t.assertEquals(Place.Unknown.anywhere(Place.Unknown.Reason.UNIDENTIFIED_INVENTORY), unheld.getPlaces().get(0),
                "a tool nothing says who holds is Unknown, never where the actor stands");
    }

    @HadronTest
    @Covers(WeatherChangeEvent.class)
    void weatherIsEveryLevelThatSharesIt(TestContext t) {
        ServerLevel overworld = t.server().overworld();
        ServerLevel nether = t.server().getLevel(Level.NETHER);
        require(nether != null, "the test server has a Nether");
        WeatherChangeEvent e = new WeatherChangeEvent(Actor.Nature.INSTANCE, overworld,
                WeatherChangeEvent.Weather.CLEAR, WeatherChangeEvent.Weather.RAIN);
        List<Place> got = List.copyOf(e.getPlaces());
        t.assertEquals(new Place.Level(overworld), got.get(0), "the level whose weather changes, first");
        t.assertTrue(got.contains(new Place.Level(nether)), "the Nether, whose weather is the overworld's: " + got);
        t.assertTrue(got.contains(new Place.Level(t.level())), "the test's own world, created sharing the "
                + "overworld's weather: " + got);
        t.assertTrue(got.stream().allMatch(Place.Level.class::isInstance), "only whole levels: " + got);
    }

    @HadronTest
    @Covers(DataRevealEvent.class)
    @Covers(AccessControlChangeEvent.class)
    void serverEventsPlaceTheirTargetNotTheirViewer(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        bob.teleport(t.pos(5, 0, 0).getX() + 0.5, t.pos(5, 0, 0).getY(), t.pos(5, 0, 0).getZ() + 0.5);
        BlockPos chest = t.pos(-4, 0, 0);
        t.assertEquals(List.of(Place.Spot.at(t.level(), chest)), List.copyOf(new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Block(t.level(), chest), new CompoundTag()).getPlaces()), "a block's data");
        t.assertEquals(List.of(Place.of(bob.player())), List.copyOf(new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Entity(bob.player()), new CompoundTag()).getPlaces()), "bob's data");
        ResourceHolder.Account account = ResourceHolder.Account.player(bob.uuid());
        t.assertEquals(List.of(Place.Nowhere.account(account)), List.copyOf(new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Account(account), new CompoundTag()).getPlaces()), "an account's data");
        AccessControlChangeEvent claim = new AccessControlChangeEvent(new Actor.Player(alice.player()), "ftbchunks",
                "team-7", AccessControlChangeEvent.Change.MEMBERS, Set.of(bob.uuid()));
        Map<String, Object> got = new LinkedHashMap<>();
        Place.Nowhere group = (Place.Nowhere) claim.getPlaces().get(0);
        got.put("kind", group.kind().toString());
        got.put("id", group.id());
        t.assertEquals(Map.of("kind", "ftbchunks:access_group", "id", "team-7"), got, "a claim's group, nowhere");
    }

    @HadronTest
    @Covers(dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent.class)
    void aChunksPlaceIsTheChunkFullHeight(TestContext t) {
        dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent e = new dev.iredsc.hadron.api.event.world
                .ChunkForceLoadEvent(Actor.Unknown.INSTANCE, t.level(), new net.minecraft.world.level.ChunkPos(
                        t.pos(0, 0, 0)), true);
        t.assertEquals(List.of(Place.Spot.chunk(t.level(), new net.minecraft.world.level.ChunkPos(t.pos(0, 0, 0)))),
                List.copyOf(e.getPlaces()), "a forced chunk, its full height");
    }
}
