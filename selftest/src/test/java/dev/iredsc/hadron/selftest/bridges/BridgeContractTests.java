package dev.iredsc.hadron.selftest.bridges;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockDamageEvent;
import dev.iredsc.hadron.api.event.block.BlockDataChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockRedstoneEvent;
import dev.iredsc.hadron.api.event.block.StructureMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityControlChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityControlExpireEvent;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryDestroyEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryGenerateEvent;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventorySortEvent;
import dev.iredsc.hadron.api.event.resource.Resource;
import dev.iredsc.hadron.api.event.resource.ResourceChangeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceConvertEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.server.AccessControlChangeEvent;
import dev.iredsc.hadron.api.event.server.DataRevealEvent;
import dev.iredsc.hadron.api.event.world.TickAccelerateEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import org.joml.Matrix4d;

/**
 * Events only bridges fire ({@code docs/bridge-hooks.md}), fired here the way a bridge must: with its machine's actor
 * pushed, before the change, through its claim ({@code t.bridge()}), and the change made only if no listener
 * cancelled. Each test checks that a listener receives the event as built, that a listener on its parent hears it too, and that a cancel
 * reaches the bridge, so a plugin written against these events works the day a bridge fires them.
 */
public final class BridgeContractTests {
    /** A machine at pos(5, 0, 5) owned by alice, as a bridge would push it around the machine's work. */
    private static Actor machine(TestContext t, TestPlayer owner) {
        return new Actor.Machine(t.level(), t.pos(5, 0, 5), Attribution.of(owner.uuid()));
    }

    /** What a bridge does: push the actor, fire, and make the change only if nobody cancelled. */
    private static <E extends Event> boolean fire(TestContext t, Actor actor, java.util.function.Function<Actor, E> build,
            Runnable change) {
        try (Actors.Scope scope = Actors.push(actor)) {
            E event = build.apply(Actors.currentOr(Actor.Unknown.INSTANCE));
            boolean cancelled = t.bridge().fire(event) instanceof Cancellable c && c.isCancelled();
            if (!cancelled) change.run();
            return cancelled;
        }
    }

    /** Records every event of a type, cancelling them while {@code cancel[0]} is set. */
    private static <E extends Event> List<E> record(TestContext t, Class<E> type, boolean[] cancel) {
        List<E> seen = new ArrayList<>();
        t.registerEvents(new Recorder<>(type, seen, cancel));
        return seen;
    }

    /** A listener for one event type and its subclasses, through the event families' parents. */
    private static final class Recorder<E extends Event> implements Listener {
        private final Class<E> type;
        private final List<E> seen;
        private final boolean[] cancel;

        Recorder(Class<E> type, List<E> seen, boolean[] cancel) {
            this.type = type;
            this.seen = seen;
            this.cancel = cancel;
        }

        @EventHandler
        void on(BlockEvent e) {
            take(e);
        }

        @EventHandler
        void on(dev.iredsc.hadron.api.event.entity.EntityEvent e) {
            take(e);
        }

        @EventHandler
        void on(dev.iredsc.hadron.api.event.inventory.InventoryEvent e) {
            take(e);
        }

        @EventHandler
        void on(ResourceEvent e) {
            take(e);
        }

        @EventHandler
        void on(dev.iredsc.hadron.api.event.server.ServerEvent e) {
            take(e);
        }

        @EventHandler
        void on(dev.iredsc.hadron.api.event.world.WorldEvent e) {
            take(e);
        }

        private void take(Event e) {
            if (!type.isInstance(e)) return;
            seen.add(type.cast(e));
            if (cancel[0] && e instanceof dev.iredsc.hadron.api.event.Cancellable c) c.setCancelled(true);
        }
    }

    /** Checks that building something is refused with a message saying the fix. */
    private static void refuses(TestContext t, String fix, Runnable build) {
        String said = "nothing";
        try {
            build.run();
        } catch (IllegalArgumentException expected) {
            said = String.valueOf(expected.getMessage());
        }
        t.assertTrue(said.contains(fix), "should be refused, saying \"" + fix + "\", but said: " + said);
    }

    private static <E extends Event> E only(TestContext t, List<E> seen, String what) {
        t.assertEquals(1, seen.size(), "the " + what + "s a listener received");
        return seen.get(0);
    }

    // ---- Blocks

    @HadronTest
    @Covers(BlockDataChangeEvent.class)
    void aBlockEntityDataChangeIsABlockChangeAndACancelKeepsTheData(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) t.level().getBlockEntity(pos);
        CompoundTag before = chest.saveWithoutMetadata(t.level().registryAccess());
        CompoundTag after = before.copy();
        after.putString("Lock", "secret"); // a drawer lock, a terminal binding: data, same block state
        boolean[] cancel = {false};
        List<BlockChangeEvent> changes = record(t, BlockChangeEvent.class, cancel);
        Runnable apply = () -> chest.loadWithComponents(after, t.level().registryAccess());
        fire(t, machine(t, alice), a -> new BlockDataChangeEvent(t.level(), pos, chest.getBlockState(), before, after, a),
                apply);
        BlockDataChangeEvent e = (BlockDataChangeEvent) only(t, changes, "BlockChangeEvent");
        t.assertEquals("secret", e.getAfterData().getString("Lock"), "the data after");
        e.getAfterData().putString("Lock", "other");
        e.getBlocks().get(0).getAfterData().orElseThrow().putString("Lock", "other");
        t.assertEquals("secret", e.getBlocks().get(0).getAfterData().orElseThrow().getString("Lock"),
                "a listener's copies shouldn't change the block's data");
        t.assertEquals("secret", e.getAfterData().getString("Lock"), "the event and its block agree");
        after.putString("Lock", "changed"); // the bridge reusing its tag after firing
        t.assertEquals("secret", e.getAfterData().getString("Lock"), "the bridge's tag shouldn't change the event");
        after.putString("Lock", "secret");
        t.assertFalse(e.getBeforeData().contains("Lock"), "the data before");
        t.assertTrue(e.getActingPlayer().orElse(null) == alice.player(), "the machine's owner is the acting player");
        t.assertTrue(chest.saveWithoutMetadata(t.level().registryAccess()).contains("Lock"), "the uncancelled change");
        cancel[0] = true;
        CompoundTag unlocked = before.copy();
        boolean refused = fire(t, machine(t, alice), a -> new BlockDataChangeEvent(t.level(), pos,
                chest.getBlockState(), after, unlocked, a), () -> chest.loadWithComponents(unlocked,
                t.level().registryAccess()));
        t.assertTrue(refused, "fire should hand the cancel back to the bridge");
        BlockDataChangeEvent unlock = (BlockDataChangeEvent) changes.get(1);
        after.putString("Lock", "changed");
        unlock.getBeforeData().putString("Lock", "changed");
        t.assertEquals("secret", unlock.getBeforeData().getString("Lock"), "the data before, kept from the bridge's "
                + "tag and a listener's copy");
        t.assertTrue(chest.saveWithoutMetadata(t.level().registryAccess()).contains("Lock"), "a cancelled data change "
                + "should leave the lock");
    }

    @HadronTest
    @Covers(EntityDataChangeEvent.class)
    void aModsLastingEntityDataIsAnEntityEventAndACancelKeepsIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 2).getX() + 0.5, t.pos(2, 0, 2).getY(), t.pos(2, 0, 2).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        ResourceLocation job = ResourceLocation.fromNamespaceAndPath("hadron_test_target", "job");
        CompoundTag builder = new CompoundTag();
        builder.putString("job", "builder");
        CompoundTag[] stored = {new CompoundTag()};
        boolean[] cancel = {false};
        List<dev.iredsc.hadron.api.event.entity.EntityEvent> seen = record(t,
                dev.iredsc.hadron.api.event.entity.EntityEvent.class, cancel);
        fire(t, machine(t, alice), a -> new EntityDataChangeEvent(pig, job, stored[0], builder, a),
                () -> stored[0] = builder);
        EntityDataChangeEvent e = (EntityDataChangeEvent) only(t, seen, "EntityEvent");
        t.assertTrue(e.getEntity() == pig, "the entity");
        t.assertTrue(e.getActor() instanceof Actor.Machine, "the machine that changed it, but was " + e.getActor());
        t.assertTrue(e.getActingPlayer().orElse(null) == alice.player(), "the machine's owner is the acting player");
        t.assertEquals(job, e.getKind(), "whose data");
        t.assertEquals("builder", e.getAfterData().getString("job"), "the data after");
        e.getAfterData().putString("job", "miner");
        t.assertEquals("builder", e.getAfterData().getString("job"), "a listener's copy shouldn't change the data");
        builder.putString("job", "changed"); // the bridge reusing its tag after firing
        t.assertEquals("builder", e.getAfterData().getString("job"), "the bridge's tag shouldn't change the event");
        builder.putString("job", "builder");
        t.assertTrue(e.getBeforeData().isEmpty(), "the data before");
        cancel[0] = true;
        CompoundTag farmer = new CompoundTag();
        farmer.putString("job", "farmer");
        boolean refused = fire(t, machine(t, alice), a -> new EntityDataChangeEvent(pig, job, builder, farmer, a),
                () -> stored[0] = farmer);
        t.assertTrue(refused, "fire should hand the cancel back to the bridge");
        t.assertEquals("builder", stored[0].getString("job"), "a refused change keeps the old job");
        EntityDataChangeEvent change = (EntityDataChangeEvent) seen.get(1);
        builder.putString("job", "changed");
        change.getBeforeData().putString("job", "changed");
        t.assertEquals("builder", change.getBeforeData().getString("job"), "the data before, kept from the bridge's "
                + "tag and a listener's copy");
        builder.putString("job", "builder");
        refuses(t, "isn't a mod's id", () -> new EntityDataChangeEvent(pig,
                ResourceLocation.fromNamespaceAndPath("hadron", "job"), builder, farmer, machine(t, alice)));
        refuses(t, "isn't a mod's id", () -> new EntityDataChangeEvent(pig,
                ResourceLocation.withDefaultNamespace("job"), builder, farmer, machine(t, alice)));
        refuses(t, "nothing changes", () -> new EntityDataChangeEvent(pig, job, builder, builder.copy(),
                machine(t, alice)));
    }

    @HadronTest
    @Covers(BlockRedstoneEvent.class)
    void aRedstoneSignalChangeCanBeChangedOrRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        BlockState state = Blocks.STONE.defaultBlockState(); // a mod's emitter whose state never changes
        int[] signal = {0};
        boolean[] cancel = {false};
        List<BlockRedstoneEvent> seen = record(t, BlockRedstoneEvent.class, cancel);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockRedstoneEvent e) {
                if (e.getNewCurrent() > 10) e.setNewCurrent(10);
            }
        });
        BlockRedstoneEvent[] fired = new BlockRedstoneEvent[1];
        fire(t, machine(t, alice), a -> fired[0] = new BlockRedstoneEvent(t.level(), pos, state, 0, 15, a),
                () -> signal[0] = fired[0].getNewCurrent());
        BlockRedstoneEvent e = only(t, seen, "BlockRedstoneEvent");
        t.assertEquals(0, e.getOldCurrent(), "the old signal");
        t.assertEquals(10, signal[0], "the signal a listener capped, as the bridge emits it");
        cancel[0] = true;
        fire(t, machine(t, alice), a -> new BlockRedstoneEvent(t.level(), pos, state, 10, 0, a), () -> signal[0] = 0);
        t.assertEquals(10, signal[0], "a cancelled change keeps the old signal");
    }

    @HadronTest
    @Covers(BlockDamageEvent.class)
    void lastingBlockDamageCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.STONE);
        float[] damage = {0.0f};
        boolean[] cancel = {false};
        List<BlockDamageEvent> seen = record(t, BlockDamageEvent.class, cancel);
        fire(t, machine(t, alice), a -> new BlockDamageEvent(t.level(), pos, t.getBlock(pos), 0.0f, 0.4f, a),
                () -> damage[0] = 0.4f);
        BlockDamageEvent e = only(t, seen, "BlockDamageEvent");
        t.assertEquals(0.0f, e.getOldDamage(), "the damage before");
        t.assertEquals(0.4f, e.getNewDamage(), "the damage after");
        t.assertEquals(pos, e.getPos(), "where the block is");
        cancel[0] = true;
        fire(t, machine(t, alice), a -> new BlockDamageEvent(t.level(), pos, t.getBlock(pos), 0.4f, 0.8f, a),
                () -> damage[0] = 0.8f);
        t.assertEquals(0.4f, damage[0], "a refused hit leaves the damage where it was");
    }

    private static List<BlockChange> moveChanges(TestContext t, BlockPos from, BlockPos to, BlockState state) {
        return List.of(BlockChange.of(t.level(), from, state, Blocks.AIR.defaultBlockState()),
                BlockChange.of(t.level(), to, t.getBlock(to), state));
    }

    @HadronTest
    @Covers(BlockMoveEvent.class)
    void aFrameMovingABlockIsABlockMoveThatCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos from = t.pos(1, 0, 0);
        BlockPos to = t.pos(2, 0, 0);
        t.setBlock(from, Blocks.DIRT);
        BlockState dirt = t.getBlock(from);
        Runnable move = () -> {
            t.setBlock(to, dirt);
            t.setBlock(from, Blocks.AIR);
        };
        boolean[] cancel = {true};
        List<BlockMoveEvent> seen = record(t, BlockMoveEvent.class, cancel);
        fire(t, machine(t, alice), a -> new BlockMoveEvent(t.level(), from, dirt,
                List.of(new BlockMoveEvent.Move(from, to, dirt)), List.of(to), moveChanges(t, from, to, dirt), a), move);
        t.assertBlock(from, Blocks.DIRT);
        t.assertBlock(to, Blocks.AIR);
        cancel[0] = false;
        fire(t, machine(t, alice), a -> new BlockMoveEvent(t.level(), from, dirt,
                List.of(new BlockMoveEvent.Move(from, to, dirt)), List.of(to), moveChanges(t, from, to, dirt), a), move);
        t.assertBlock(to, Blocks.DIRT);
        t.assertEquals(2, seen.size(), "the BlockMoveEvents received");
        t.assertEquals(List.of(new BlockMoveEvent.Move(from, to, dirt)), seen.get(1).getMoves(), "the moves");
        t.assertEquals(List.of(to), seen.get(1).getSweptSpace(), "the swept space");
    }

    @HadronTest
    @Covers(StructureMoveEvent.class)
    void aContraptionMoveIsAStructureMoveHeardAsABlockMove(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos from = t.pos(1, 0, 0);
        BlockPos to = t.pos(1, 0, 3);
        t.setBlock(from, Blocks.OAK_PLANKS);
        BlockState planks = t.getBlock(from);
        boolean[] cancel = {false};
        List<BlockMoveEvent> seen = record(t, BlockMoveEvent.class, cancel);
        Matrix4d start = new Matrix4d();
        Matrix4d end = new Matrix4d().translation(0, 0, 3);
        fire(t, machine(t, alice), a -> new StructureMoveEvent(t.level(), from, planks, StructureMoveEvent.Phase.MOVE,
                start, end, List.of(new BlockMoveEvent.Move(from, to, planks)), List.of(t.pos(1, 0, 1), t.pos(1, 0, 2), to),
                moveChanges(t, from, to, planks), a), () -> {
                    t.setBlock(to, planks);
                    t.setBlock(from, Blocks.AIR);
                });
        t.assertTrue(only(t, seen, "BlockMoveEvent") instanceof StructureMoveEvent s
                && s.getPhase() == StructureMoveEvent.Phase.MOVE && s.getTo().equals(end) && s.getFrom().equals(start),
                "a BlockMoveEvent listener should hear the StructureMoveEvent with its phase and transforms, but "
                        + "heard " + seen.get(0));
        t.assertBlock(to, Blocks.OAK_PLANKS);
    }

    // ---- Entities

    @HadronTest
    @Covers(EntityControlChangeEvent.class)
    @Covers(EntityControlExpireEvent.class)
    void aCharmChangesWhoControlsAMobAndItsExpiryIsReported(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Pig pig = EntityType.PIG.create(t.level());
        pig.moveTo(t.pos(2, 0, 0).getX() + 0.5, t.pos(2, 0, 0).getY(), t.pos(2, 0, 0).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        UUID[] controller = {null};
        boolean[] cancel = {false};
        List<EntityControlChangeEvent> changes = record(t, EntityControlChangeEvent.class, cancel);
        List<EntityControlExpireEvent> expiries = record(t, EntityControlExpireEvent.class, new boolean[] {false});
        Actor charm = new Actor.Player(bob.player());
        fire(t, charm, a -> new EntityControlChangeEvent(pig, null, bob.uuid(), a), () -> controller[0] = bob.uuid());
        EntityControlChangeEvent e = only(t, changes, "EntityControlChangeEvent");
        t.assertEquals(Optional.empty(), e.getOldController(), "the old controller");
        t.assertEquals(Optional.of(bob.uuid()), e.getNewController(), "the new controller");
        t.assertTrue(e.getEntity() == pig, "the charmed mob");
        cancel[0] = true;
        fire(t, new Actor.Player(alice.player()), a -> new EntityControlChangeEvent(pig, bob.uuid(), alice.uuid(), a),
                () -> controller[0] = alice.uuid());
        t.assertEquals(bob.uuid(), controller[0], "a refused takeover keeps bob in control");
        fire(t, Actor.Nature.INSTANCE, a -> new EntityControlExpireEvent(pig, bob.uuid(), a), () -> controller[0] = null);
        EntityControlExpireEvent expired = only(t, expiries, "EntityControlExpireEvent");
        t.assertEquals(Optional.of(bob.uuid()), expired.getController(), "whose control expired");
        t.assertEquals(null, controller[0], "an expiry is after the fact and can't be refused");
    }

    // ---- Inventories

    private static InventoryChangeEvent.Change slot(TestContext t, BlockPos chest, int slot, ItemStack before,
            ItemStack after) {
        return new InventoryChangeEvent.Change(new InventoryHolder.Block(t.level(), chest), slot, before, after);
    }

    @HadronTest
    @Covers(InventoryChangeEvent.class)
    @Covers(InventoryGenerateEvent.class)
    @Covers(InventoryDestroyEvent.class)
    @Covers(InventorySortEvent.class)
    void aMachinesSlotWritesAreInventoryChangesThatCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) t.level().getBlockEntity(pos);
        boolean[] cancel = {false};
        List<InventoryChangeEvent> seen = record(t, InventoryChangeEvent.class, cancel);
        Actor machine = machine(t, alice);

        ItemStack ingot = new ItemStack(Items.IRON_INGOT, 2);
        fire(t, machine, a -> new InventoryChangeEvent(List.of(slot(t, pos, 0, ItemStack.EMPTY, ingot)), a),
                () -> chest.setItem(0, ingot.copy()));
        fire(t, machine, a -> new InventoryGenerateEvent(List.of(slot(t, pos, 1, ItemStack.EMPTY,
                new ItemStack(Items.DIAMOND))), a), () -> chest.setItem(1, new ItemStack(Items.DIAMOND)));
        fire(t, machine, a -> new InventorySortEvent(List.of(slot(t, pos, 0, ingot, new ItemStack(Items.DIAMOND)),
                slot(t, pos, 1, new ItemStack(Items.DIAMOND), ingot)), a), () -> {
                    chest.setItem(0, new ItemStack(Items.DIAMOND));
                    chest.setItem(1, ingot.copy());
                });
        cancel[0] = true;
        fire(t, machine, a -> new InventoryDestroyEvent(List.of(slot(t, pos, 0, new ItemStack(Items.DIAMOND),
                ItemStack.EMPTY)), a), () -> chest.setItem(0, ItemStack.EMPTY));

        t.assertEquals(List.of("InventoryChangeEvent", "InventoryGenerateEvent", "InventorySortEvent",
                "InventoryDestroyEvent"), seen.stream().map(e -> e.getClass().getSimpleName()).toList(),
                "the inventory changes an InventoryChangeEvent listener heard");
        InventoryHolder holder = new InventoryHolder.Block(t.level(), pos);
        t.assertEquals(Set.of(holder), seen.get(2).getHolders(), "the sort's holders");
        t.assertEquals(2, seen.get(2).getChanges(holder).size(), "the sort's changes in the chest");
        t.assertTrue(seen.get(0).getChanges().get(0).after().is(Items.IRON_INGOT), "the first change's item");
        t.assertTrue(chest.getItem(0).is(Items.DIAMOND), "a refused destroy keeps the diamond, but slot 0 holds "
                + chest.getItem(0));
        t.assertTrue(chest.getItem(1).is(Items.IRON_INGOT), "the sort's result in slot 1");
    }

    @HadronTest
    @Covers(InventoryGenerateEvent.class)
    @Covers(value = Place.class, method = "of")
    void eachPlayersCopyOfALootChestIsItsOwnInventoryAtTheChest(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        InventoryHolder chest = new InventoryHolder.Block(t.level(), pos);
        InventoryHolder alices = new InventoryHolder.PerPlayer(chest, alice.uuid());
        InventoryHolder bobs = new InventoryHolder.PerPlayer(chest, bob.uuid());
        boolean[] cancel = {false};
        List<InventoryChangeEvent> seen = record(t, InventoryChangeEvent.class, cancel);
        ItemStack loot = new ItemStack(Items.DIAMOND);
        fire(t, machine(t, alice), a -> new InventoryGenerateEvent(List.of(
                new InventoryChangeEvent.Change(alices, 0, ItemStack.EMPTY, loot),
                new InventoryChangeEvent.Change(bobs, 0, ItemStack.EMPTY, loot)), a), () -> {});
        InventoryChangeEvent e = only(t, seen, "InventoryChangeEvent");
        t.assertEquals(Set.of(alices, bobs), e.getHolders(), "two copies, never mistaken for one inventory");
        t.assertEquals(1, e.getChanges(alices).size(), "alice's copy's own changes");
        InventoryHolder.PerPlayer copy = (InventoryHolder.PerPlayer) e.getChanges().get(0).holder();
        t.assertEquals(chest, copy.at(), "where the copy is, for protection");
        t.assertEquals(alice.uuid(), copy.owner(), "whose copy");
        Place chestSpot = Place.Spot.at(t.level(), pos);
        t.assertEquals(chestSpot, Place.of(chest), "a chest is at its block");
        t.assertEquals(chestSpot, Place.of(alices), "a copy is at its chest");
        InventoryHolder cart = new InventoryHolder.Entity(EntityType.CHEST_MINECART.create(t.level()));
        t.assertEquals(Place.of(cart), Place.of(new InventoryHolder.Item(cart, loot)), "a backpack on an entity");
        t.assertEquals(chestSpot, Place.of(new InventoryHolder.Item(alices, loot)), "a backpack in a copy is at its "
                + "chest");
        InventoryHolder ender = new InventoryHolder.Linked(InventoryHolder.Linked.ENDER_CHEST, alice.uuid().toString());
        t.assertEquals(new Place.Nowhere(InventoryHolder.Linked.ENDER_CHEST, alice.uuid().toString(),
                java.util.Optional.of(alice.uuid())), Place.of(new InventoryHolder.Item(ender, loot)),
                "a backpack in an ender chest has no place: its frequency, owned by alice");
        InventoryHolder unplaced = new InventoryHolder.PerPlayer(InventoryHolder.Unknown.INSTANCE, alice.uuid());
        t.assertEquals(Place.Unknown.anywhere(Place.Unknown.Reason.UNIDENTIFIED_INVENTORY), Place.of(unplaced),
                "whose copy, but not where, is Unknown");
        t.assertFalse(unplaced.equals(new InventoryHolder.PerPlayer(InventoryHolder.Unknown.INSTANCE, bob.uuid())),
                "and still never merges two players' copies");
        refuses(t, "keyed by its frequency", () -> new InventoryHolder.PerPlayer(ender, alice.uuid()));
        refuses(t, "its at()", () -> new InventoryHolder.PerPlayer(alices, bob.uuid()));
    }

    // ---- Resources

    @HadronTest
    @Covers(ResourceChangeEvent.class)
    @Covers(ResourceConvertEvent.class)
    void energyAndConversionsAreResourceEventsThatCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        ResourceHolder battery = new ResourceHolder.Block(t.level(), t.pos(5, 0, 5));
        long[] stored = {100};
        boolean[] cancel = {false};
        List<ResourceEvent> seen = record(t, ResourceEvent.class, cancel);
        fire(t, machine(t, alice), a -> new ResourceChangeEvent(a, Resource.ENERGY, battery, 100, 160),
                () -> stored[0] = 160);
        ResourceChangeEvent change = (ResourceChangeEvent) only(t, seen, "ResourceEvent");
        t.assertEquals(battery, change.getHolder(), "the holder");
        t.assertEquals(100L, change.getOldAmount(), "the old amount");
        t.assertEquals(160L, change.getNewAmount(), "the new amount");
        t.assertEquals(60L, change.getAmount(), "the amount changed");
        t.assertEquals(List.of(battery), change.getHolders(), "every holder involved");
        cancel[0] = true;
        Resource water = Resource.fluid(Fluids.WATER);
        long[] steam = {0};
        boolean refused = fire(t, machine(t, alice), a -> new ResourceConvertEvent(a, battery, Resource.ENERGY, 160, 60,
                water, 250), () -> {
                    stored[0] = 60;
                    steam[0] = 250;
                });
        t.assertTrue(refused, "the bridge learns the conversion was refused");
        ResourceConvertEvent convert = (ResourceConvertEvent) seen.get(1);
        t.assertEquals(water, convert.getOutput(), "what it converts into");
        t.assertEquals(250L, convert.getOutputAmount(), "how much it makes");
        t.assertTrue(stored[0] == 160 && steam[0] == 0, "a refused conversion spends and makes nothing");
    }

    // ---- Server and world

    @HadronTest
    @Covers(AccessControlChangeEvent.class)
    void aClaimsTrustChangeCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        Set<UUID> trusted = new HashSet<>();
        boolean[] cancel = {false};
        List<AccessControlChangeEvent> seen = record(t, AccessControlChangeEvent.class, cancel);
        Actor who = new Actor.Player(alice.player());
        fire(t, who, a -> new AccessControlChangeEvent(a, "ftbchunks", "alice's base",
                AccessControlChangeEvent.Change.MEMBERS, Set.of(bob.uuid())), () -> trusted.add(bob.uuid()));
        AccessControlChangeEvent e = only(t, seen, "AccessControlChangeEvent");
        t.assertEquals("ftbchunks", e.getSystem(), "the system");
        t.assertEquals("alice's base", e.getGroup(), "the group");
        t.assertEquals(AccessControlChangeEvent.Change.MEMBERS, e.getChange(), "what changed");
        t.assertEquals(Set.of(bob.uuid()), e.getAffectedPlayers(), "who it affects");
        t.assertTrue(e.getActor() == who, "who changed it");
        cancel[0] = true;
        fire(t, who, a -> new AccessControlChangeEvent(a, "ftbchunks", "alice's base",
                AccessControlChangeEvent.Change.MEMBERS, Set.of(bob.uuid())), () -> trusted.remove(bob.uuid()));
        t.assertEquals(Set.of(bob.uuid()), trusted, "a refused untrust leaves bob trusted");
    }

    @HadronTest
    @Covers(DataRevealEvent.class)
    void revealedDataCanBeTrimmedOrWithheld(TestContext t) {
        TestPlayer alice = t.player("alice");
        BlockPos pos = t.pos(1, 0, 0);
        t.setBlock(pos, Blocks.CHEST);
        CompoundTag data = new CompoundTag();
        data.putString("owner", "bob");
        data.putInt("items", 12);
        List<CompoundTag> sent = new ArrayList<>();
        boolean[] cancel = {false};
        List<DataRevealEvent> seen = record(t, DataRevealEvent.class, cancel);
        t.registerEvents(new Listener() {
            @EventHandler
            void on(DataRevealEvent e) {
                e.getData().remove("owner"); // a plugin keeping owners private from Jade
            }
        });
        DataRevealEvent[] fired = new DataRevealEvent[1];
        fire(t, new Actor.Player(alice.player()), a -> fired[0] = new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Block(t.level(), pos), data.copy()), () -> sent.add(fired[0].getData()));
        DataRevealEvent e = only(t, seen, "DataRevealEvent");
        t.assertTrue(e.getViewer() == alice.player(), "the viewer");
        t.assertEquals(new DataRevealEvent.Target.Block(t.level(), pos), e.getTarget(), "the block revealed");
        t.assertFalse(sent.get(0).contains("owner"), "the field a listener removed isn't sent");
        t.assertEquals(12, sent.get(0).getInt("items"), "the rest is sent");
        cancel[0] = true;
        Pig pig = EntityType.PIG.create(t.level());
        fire(t, new Actor.Player(alice.player()), a -> new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Entity(pig), data.copy()),
                () -> sent.add(data));
        t.assertEquals(1, sent.size(), "a withheld reveal sends nothing");
        t.assertEquals(new DataRevealEvent.Target.Entity(pig), seen.get(1).getTarget(), "the entity revealed");
        cancel[0] = false;
        ResourceHolder.Account bobs = ResourceHolder.Account.player(new UUID(0, 42)); // bob, who may be offline
        fire(t, new Actor.Player(alice.player()), a -> fired[0] = new DataRevealEvent(alice.player(),
                new DataRevealEvent.Target.Account(bobs), data.copy()), () -> sent.add(fired[0].getData()));
        // A guard written as a switch must name every kind of target, so it can't let accounts through unseen.
        String kind = switch (seen.get(2).getTarget()) {
            case DataRevealEvent.Target.Block b -> "block";
            case DataRevealEvent.Target.Entity en -> "entity";
            case DataRevealEvent.Target.Account acc -> acc.account().equals(bobs) ? "bob's account" : "another account";
        };
        t.assertEquals("bob's account", kind, "the account revealed");
        t.assertFalse(sent.get(1).contains("owner"), "the field a listener removed isn't sent for an account either");
        t.assertEquals(12, sent.get(1).getInt("items"), "the rest of an account's data is sent");
    }

    @HadronTest
    @Covers(TickAccelerateEvent.class)
    void anAcceleratorSpeedingAnAreaCanBeRefused(TestContext t) {
        TestPlayer alice = t.player("alice");
        BoundingBox area = BoundingBox.fromCorners(t.pos(-2, 0, -2), t.pos(2, 2, 2));
        double[] rate = {1};
        boolean[] cancel = {false};
        List<TickAccelerateEvent> seen = record(t, TickAccelerateEvent.class, cancel);
        fire(t, machine(t, alice), a -> new TickAccelerateEvent(a, t.level(), area, 4), () -> rate[0] = 4);
        TickAccelerateEvent e = only(t, seen, "TickAccelerateEvent");
        t.assertEquals(4.0, e.getMultiplier(), "the new speed");
        t.assertEquals(Optional.of(area), e.getArea(), "the area");
        cancel[0] = true;
        fire(t, machine(t, alice), a -> new TickAccelerateEvent(a, t.level(), area, 16), () -> rate[0] = 16);
        t.assertEquals(4.0, rate[0], "a refused speed-up keeps the old speed");
        try {
            new TickAccelerateEvent(machine(t, alice), t.level(), area, Double.NaN);
            t.fail("a NaN multiplier should be refused");
        } catch (IllegalArgumentException refusedNaN) {
            t.assertTrue(refusedNaN.getMessage().contains("pass 1 when the accelerator stops"),
                    "the refusal should say the fix, but said: " + refusedNaN.getMessage());
        }
    }
}
