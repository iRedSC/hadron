package dev.iredsc.hadron.selftest.facts;

import static dev.iredsc.hadron.selftest.entities.Fixtures.spawn;

import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockDamageEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.KnownGap;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Facts an event should state and doesn't yet, each found by the regions plugin, which had to guess them
 * (docs/design.md, "Core states facts"). Each test is written as it should pass once its event carries the fact, so
 * a plugin needs no Minecraft knowledge to read it; the assertion's shape may change with the design that closes it.
 * Setup that goes wrong throws, so it fails the run instead of passing as the gap.
 */
public final class MissingFactTests {
    private static void require(boolean ok, String what) {
        if (!ok) throw new IllegalStateException("Setup went wrong: " + what);
    }

    /** Every event of {@code kind} heard from now on. */
    private static <E extends Event> List<E> hear(TestContext t, Class<E> kind) {
        List<E> heard = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(Event e) {
                if (kind.isInstance(e)) heard.add(kind.cast(e));
            }
        });
        return heard;
    }

    private static String shown(List<? extends Event> events) {
        return events.isEmpty() ? "none" : events.stream().map(e -> e.getClass().getSimpleName())
                .collect(Collectors.joining(", "));
    }

    @HadronTest
    @Covers(EntityTeleportEvent.class)
    void aTeleportStatesWhereItSettles(TestContext t) {
        TestPlayer alice = t.player("alice");
        List<EntityTeleportEvent> teleports = hear(t, EntityTeleportEvent.class);
        BlockPos above = t.pos(2, 6, 0);
        require(alice.player().randomTeleport(above.getX() + 0.5, above.getY(), above.getZ() + 0.5, false),
                "the random teleport found no ground");
        t.tick();
        BlockPos landed = alice.player().blockPosition();
        require(!landed.equals(above), "alice stayed where asked, so the teleport didn't settle");
        t.assertTrue(teleports.stream().anyMatch(e -> e.getEntity() == alice.player()
                        && BlockPos.containing(e.getTo()).equals(landed)),
                "an event should state that alice landed at " + landed + ", but the teleports heard went to "
                        + teleports.stream().map(e -> BlockPos.containing(e.getTo()).toShortString()).toList());
    }

    @HadronTest
    @Covers(EntityMoveEvent.class)
    void aRiderIsNamedWhenTheirVehicleCarriesThemIntoANewBlock(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        bob.interact(boat);
        require(alice.player().getVehicle() == boat && bob.player().getVehicle() == boat, "both should ride the boat");
        t.tick(2);
        List<EntityMoveEvent> moves = hear(t, EntityMoveEvent.class);
        boat.setPos(boat.getX() + 3, boat.getY(), boat.getZ());
        t.tick();
        BlockPos now = bob.player().blockPosition();
        require(moves.stream().anyMatch(e -> e.getEntity() == boat), "the boat's own move should fire");
        t.assertTrue(moves.stream().anyMatch(e -> e.getEntity() == boat
                        && e.getCarried() instanceof Carried.Known k && k.entities().contains(bob.player())
                        && e.getPlaces().contains(Place.Spot.at(t.level(), now))),
                "the boat's move should name bob as carried, in " + now + ", but carried "
                        + moves.stream().map(EntityMoveEvent::getCarried).toList());
    }

    @HadronTest
    @Covers(EntityDismountEvent.class)
    void aDismountStatesWhereTheRiderIsSetDown(TestContext t) {
        TestPlayer alice = t.player("alice");
        Boat boat = spawn(t, EntityType.BOAT, t.pos(2, 0, 0));
        alice.interact(boat);
        require(alice.player().getVehicle() == boat, "alice should ride the boat");
        t.tick(2);
        List<EntityEvent> heard = hear(t, EntityEvent.class);
        alice.player().stopRiding();
        t.tick();
        BlockPos down = alice.player().blockPosition();
        t.assertTrue(heard.stream().anyMatch(e -> e.getEntity() == alice.player() && arrivesAt(e, down)),
                "an event should state that alice was set down at " + down + ", but heard " + shown(heard));
    }

    @HadronTest
    @KnownGap("A move refused after it happens (a client step, a boat packet, a piston landing an entity elsewhere than asked) keeps what it pressed, tripped or counted")
    void aRefusedStepLeavesNoTrace(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos start = t.pos(2, 0, 0);
        BlockPos plate = start.east(2);
        t.setBlock(plate, Blocks.OAK_PRESSURE_PLATE);
        alice.teleport(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        t.tick(3);
        t.registerEvents(new dev.iredsc.hadron.api.event.Listener() {
            @dev.iredsc.hadron.api.event.EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() == alice.player()) e.setCancelled(true);
            }
        });
        net.minecraft.world.phys.Vec3 was = alice.player().position();
        int walked = alice.player().getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
                net.minecraft.stats.Stats.WALK_ONE_CM));
        alice.player().connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
                .Pos(was.x + 2, was.y, was.z, true));
        require(alice.player().position().distanceTo(was) < 1.0E-6, "alice's step should be refused");
        t.assertTrue(!t.getBlock(plate).getValue(net.minecraft.world.level.block.PressurePlateBlock.POWERED),
                "a refused step shouldn't press the plate it would have crossed");
        t.assertEquals(walked, alice.player().getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
                net.minecraft.stats.Stats.WALK_ONE_CM)), "alice's walked distance after a refused step");
    }

    /** The same gap reached by a piston: a shulker snaps to a whole block, lands elsewhere than asked, is refused. */
    @HadronTest
    @KnownGap("A move refused after it happens (a client step, a boat packet, a piston landing an entity elsewhere than asked) keeps what it pressed, tripped or counted")
    void aRefusedPistonLandingLeavesNoTrace(TestContext t) {
        t.player("alice");
        BlockPos piston = t.pos(2, 0, 0);
        t.level().setChunkForced(piston.getX() >> 4, piston.getZ() >> 4, true);
        t.awaitAsync("the test world's chunk to start ticking", () -> t.level().isPositionEntityTicking(piston), 300);
        t.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(
                net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, Direction.UP));
        t.setBlock(piston.above(), Blocks.STONE);
        net.minecraft.world.entity.monster.Shulker shulker = spawn(t, EntityType.SHULKER, piston.above(2));
        t.tick(2);
        BlockPos was = shulker.blockPosition();
        BlockPos wire = was.above();
        t.setBlock(wire, Blocks.TRIPWIRE);
        List<EntityMoveEvent> refused = new ArrayList<>();
        t.registerEvents(new dev.iredsc.hadron.api.event.Listener() {
            @dev.iredsc.hadron.api.event.EventHandler
            void on(EntityMoveEvent e) {
                if (e.getEntity() != shulker) return;
                refused.add(e);
                e.setCancelled(true);
            }
        });
        require(!t.getBlock(wire).getValue(net.minecraft.world.level.block.TripWireBlock.POWERED),
                "the wire should start unpressed");
        t.setBlock(piston.west(), Blocks.REDSTONE_BLOCK);
        t.tick(5);
        require(!refused.isEmpty() && was.equals(shulker.blockPosition()), "the shulker's landing should be refused "
                + "and it put back");
        t.assertTrue(!t.getBlock(wire).getValue(net.minecraft.world.level.block.TripWireBlock.POWERED),
                "a refused landing shouldn't power the wire it landed in");
    }

    private static boolean arrivesAt(EntityEvent e, BlockPos pos) {
        if (e instanceof EntityMoveEvent m) return m.getToBlock().equals(pos);
        if (e instanceof EntityTeleportEvent tp) return BlockPos.containing(tp.getTo()).equals(pos);
        if (e instanceof EntityDismountEvent off) return BlockPos.containing(off.getTo()).equals(pos);
        return false;
    }

    @HadronTest
    @Covers(InventoryClickEvent.class)
    void aShiftClickNamesTheInventoryItMovesInto(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos chest = t.pos(2, 0, 0);
        t.setBlock(chest, Blocks.CHEST);
        alice.useBlock(chest);
        t.tick();
        require(alice.player().containerMenu != alice.player().inventoryMenu, "alice should have the chest open");
        alice.hold(new ItemStack(Items.DIRT, 16));
        List<InventoryEvent> heard = hear(t, InventoryEvent.class);
        // Her hotbar's first slot, after the chest's 27 and her 27 others.
        alice.clickSlot(54, 0, ClickType.QUICK_MOVE);
        t.tick();
        require(!t.level().getBlockEntity(chest, BlockEntityType.CHEST).orElseThrow().isEmpty(),
                "the dirt should have moved into the chest");
        Place theChest = Place.Spot.at(t.level(), chest);
        t.assertTrue(heard.stream().anyMatch(e -> e instanceof InventoryClickEvent && names(e, theChest)),
                "the shift-click should name the chest the dirt moved into, but its places were " + places(heard));
    }

    @HadronTest
    @Covers(InventoryOpenEvent.class)
    void openingADoubleChestNamesBothHalves(TestContext t) {
        TestPlayer alice = t.player("alice");
        // A double chest facing north: the left half's other half is to its east.
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        t.setBlock(t.pos(2, 0, 0), chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
        t.setBlock(t.pos(3, 0, 0), chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        require(t.pos(2, 0, 0).relative(ChestBlock.getConnectedDirection(t.level().getBlockState(t.pos(2, 0, 0))))
                .equals(t.pos(3, 0, 0)), "the halves should join");
        alice.hold(ItemStack.EMPTY);
        List<InventoryEvent> heard = hear(t, InventoryEvent.class);
        alice.useBlock(t.pos(2, 0, 0));
        t.tick();
        require(alice.player().containerMenu != alice.player().inventoryMenu, "alice should have the chest open");
        Place clicked = Place.Spot.at(t.level(), t.pos(2, 0, 0));
        Place otherHalf = Place.Spot.at(t.level(), t.pos(3, 0, 0));
        t.assertTrue(heard.stream().anyMatch(e -> e instanceof InventoryOpenEvent && names(e, clicked)
                        && names(e, otherHalf)),
                "opening it should name both halves, " + t.pos(2, 0, 0).toShortString() + " and "
                        + t.pos(3, 0, 0).toShortString() + ", but its places were " + places(heard));
    }

    private static boolean names(InventoryEvent e, Place place) {
        return e.getPlaces().contains(place);
    }

    private static String places(List<InventoryEvent> heard) {
        return heard.stream().map(e -> e.getEventName() + " " + e.getPlaces()).toList().toString();
    }

    @HadronTest
    @KnownGap("Poison's damage comes from nobody, though someone poisoned the victim")
    void poisonActsForWhoeverPoisoned(TestContext t) {
        TestPlayer bob = t.player("bob");
        TestPlayer carol = t.player("carol");
        List<EntityDamageEvent> hurts = hear(t, EntityDamageEvent.class);
        carol.player().addEffect(new MobEffectInstance(MobEffects.POISON, 200), bob.player());
        t.await("carol to take poison damage", () -> hurts.stream().anyMatch(e -> e.getEntity() == carol.player()), 100);
        EntityDamageEvent tick = hurts.stream().filter(e -> e.getEntity() == carol.player()).findFirst().orElseThrow();
        t.assertEquals(Optional.of(bob.uuid()), tick.getActor().actingFor(), "who the poison's damage acts for");
    }

    @HadronTest
    @KnownGap("Burning's damage comes from nobody, though someone set the victim alight")
    void burningActsForWhoeverSetItAlight(TestContext t) {
        TestPlayer bob = t.player("bob");
        Pig pig = spawn(t, EntityType.PIG, t.pos(2, 0, 0));
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(t.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.FIRE_ASPECT), 2);
        bob.hold(sword);
        List<EntityDamageEvent> hurts = hear(t, EntityDamageEvent.class);
        bob.attack(pig);
        require(pig.isOnFire(), "bob's fire aspect should set the pig alight");
        t.await("the pig to take burning damage", () -> hurts.stream().anyMatch(e -> e.getEntity() == pig
                && e.getDamageSource().is(DamageTypeTags.IS_FIRE)), 100);
        EntityDamageEvent burn = hurts.stream().filter(e -> e.getEntity() == pig
                && e.getDamageSource().is(DamageTypeTags.IS_FIRE)).findFirst().orElseThrow();
        t.assertEquals(Optional.of(bob.uuid()), burn.getActor().actingFor(), "who the burning acts for");
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void startingToMineABlockFiresAnEvent(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos stone = t.pos(2, 0, 0);
        t.setBlock(stone, Blocks.OBSIDIAN);
        List<BlockEvent> heard = hear(t, BlockEvent.class);
        alice.startDigging(stone);
        t.tick();
        require(t.level().getBlockState(stone).is(Blocks.OBSIDIAN), "the obsidian shouldn't break from one click");
        t.assertTrue(heard.stream().anyMatch(e -> e instanceof Cancellable && e.getPos().equals(stone)),
                "a cancellable event should say alice started mining at " + stone.toShortString() + ", but heard "
                        + shown(heard));
    }
}
