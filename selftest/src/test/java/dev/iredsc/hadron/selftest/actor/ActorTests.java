package dev.iredsc.hadron.selftest.actor;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.actor.Actors;
import dev.iredsc.hadron.api.actor.Attribution;
import dev.iredsc.hadron.api.actor.Operation;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * The actor stack, attributions and operations as plugins and bridges use them: what is pushed is what events carry,
 * scopes close in order, and remembered attributions outlast the code that recorded them.
 */
public final class ActorTests {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    @HadronTest
    @Covers(value = Actors.class, method = "push")
    @Covers(value = Actors.class, method = "current")
    @Covers(value = Actors.class, method = "currentOr")
    @Covers(value = Actors.class, method = "isEmpty")
    @Covers(value = Actors.Scope.class, method = "close")
    void aPushedActorIsOnTopUntilItsScopeCloses(TestContext t) {
        t.assertTrue(Actors.isEmpty(), "a test starts with nothing pushed on the server thread");
        t.assertEquals(Optional.empty(), Actors.current(), "the current actor with nothing pushed");
        t.assertEquals(Actor.Unknown.INSTANCE, Actors.currentOr(Actor.Unknown.INSTANCE), "currentOr with nothing pushed");
        Actor outer = new Actor.Server("selftest");
        Actor inner = Actor.Nature.INSTANCE;
        try (Actors.Scope a = Actors.push(outer)) {
            t.assertEquals(Optional.of(outer), Actors.current(), "the current actor after one push");
            try (Actors.Scope b = Actors.push(inner)) {
                t.assertEquals(inner, Actors.currentOr(Actor.Unknown.INSTANCE), "the innermost push");
            }
            t.assertEquals(Optional.of(outer), Actors.current(), "the actor once the inner scope closed");
            Actors.Scope leaked = Actors.push(inner);
            a.close(); // closes the leaked inner push with it
            leaked.close(); // already closed: does nothing
        }
        t.assertTrue(Actors.isEmpty(), "every scope should be closed after the try blocks");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "acting")
    @Covers(BlockPlaceEvent.class)
    void eventsFiredWhileActingCarryThatActor(TestContext t) {
        BlockPos at = t.pos(1, 0, 0);
        List<Actor> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPlaceEvent e) {
                seen.add(e.getActor());
            }
        });
        Actor machine = new Actor.Machine(t.level(), t.pos(5, 0, 5), Attribution.of(ALICE));
        Actors.acting(machine, () -> t.setBlock(at, Blocks.STONE));
        String result = Actors.acting(Actor.Nature.INSTANCE, () -> "returned");
        t.assertEquals("returned", result, "acting's supplier result");
        t.assertEquals(List.of(machine), seen, "the actors the placement events carried");
        t.assertTrue(Actors.isEmpty(), "acting should pop its actor afterwards");
    }

    @HadronTest
    @Covers(value = Attribution.class, method = "none")
    @Covers(value = Attribution.class, method = "of")
    @Covers(value = Attribution.class, method = "withResponsible")
    @Covers(value = Attribution.class, method = "withController")
    @Covers(value = Attribution.class, method = "withAuthority")
    @Covers(value = Attribution.class, method = "responsible")
    @Covers(value = Attribution.class, method = "controller")
    @Covers(value = Attribution.class, method = "authority")
    @Covers(value = Attribution.class, method = "actingFor")
    @Covers(value = Attribution.class, method = "isEmpty")
    @Covers(value = Actor.class, method = "actingFor")
    void attributionActsForTheControllerFirst(TestContext t) {
        t.assertTrue(Attribution.none().isEmpty(), "none() should be empty");
        t.assertEquals(Optional.empty(), Attribution.none().actingFor(), "none() acts for nobody");
        Attribution owned = Attribution.of(ALICE);
        t.assertFalse(owned.isEmpty(), "of(alice) isn't empty");
        t.assertEquals(Optional.of(ALICE), owned.responsible(), "the responsible player");
        t.assertEquals(Optional.of(ALICE), owned.actingFor(), "an owned thing acts for its owner");
        Attribution charmed = owned.withController(BOB);
        t.assertEquals(Optional.of(BOB), charmed.controller(), "the controller");
        t.assertEquals(Optional.of(BOB), charmed.actingFor(), "a controlled thing acts for its controller");
        t.assertEquals(Optional.of(ALICE), charmed.responsible(), "withController keeps the responsible player");
        t.assertEquals(Optional.empty(), owned.controller(), "withController returns a copy, leaving the original");
        Attribution ranAs = Attribution.none().withAuthority(BOB);
        t.assertEquals(Optional.of(BOB), ranAs.authority(), "the authority");
        t.assertFalse(ranAs.isEmpty(), "an authority alone isn't empty");
        t.assertEquals(Optional.empty(), ranAs.actingFor(), "an authority alone acts for nobody");
        t.assertEquals(Optional.of(BOB), owned.withResponsible(BOB).responsible(), "withResponsible");
        Actor drone = new Actor.Machine(t.level(), t.pos(0, 0, 0), charmed);
        t.assertEquals(Optional.of(BOB), drone.actingFor(), "an actor acts for its attribution's actingFor");
        t.assertEquals(Optional.empty(), Actor.Nature.INSTANCE.actingFor(), "nature acts for nobody");
    }

    @HadronTest
    @Covers(value = Operation.class, method = "begin")
    @Covers(value = Operation.class, method = "current")
    @Covers(value = Operation.class, method = "single")
    @Covers(value = Operation.class, method = "id")
    @Covers(value = Operation.class, method = "actor")
    @Covers(BlockPlaceEvent.class)
    void anOperationGroupsEveryEventFiredInsideIt(TestContext t) {
        List<Operation> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(BlockPlaceEvent e) {
                seen.add(e.getOperation());
            }
        });
        t.assertEquals(null, Operation.current(), "the open operation outside any");
        Actor gadget = new Actor.Machine(t.level(), t.pos(5, 0, 5), Attribution.of(ALICE));
        Operation open;
        try (Actors.Scope scope = Operation.begin(gadget)) {
            open = Operation.current();
            t.assertTrue(open != null && open.actor() == gadget, "begin should open an operation for the gadget, "
                    + "but the current one is " + open);
            t.setBlock(t.pos(1, 0, 0), Blocks.STONE);
            try (Actors.Scope inner = Actors.push(Actor.Nature.INSTANCE)) {
                t.setBlock(t.pos(2, 0, 0), Blocks.STONE);
            }
        }
        t.assertEquals(null, Operation.current(), "the open operation after the scope closed");
        // With nothing pushed, a bare setBlock is nobody's and fires nothing; an actor outside any operation does.
        Actors.acting(Actor.Nature.INSTANCE, () -> t.setBlock(t.pos(3, 0, 0), Blocks.STONE));
        t.assertEquals(3, seen.size(), "the placements heard");
        t.assertTrue(seen.get(0) == open && seen.get(1) == open, "both placements inside the operation should "
                + "carry it, but carried " + seen);
        t.assertTrue(seen.get(2) != open && seen.get(2).id() != open.id(), "a placement outside should get an "
                + "operation of its own");
        Operation single = Operation.single(gadget);
        t.assertTrue(single.id() != open.id() && single.actor() == gadget, "single makes a new operation for the actor");
    }

    @HadronTest
    @Covers(value = Actors.class, method = "remember")
    @Covers(value = Actors.class, method = "remembered")
    @Covers(EntityDamageEvent.class)
    void aRememberedShooterIsBehindTheArrowsHit(TestContext t) {
        TestPlayer alice = t.player("alice");
        Pig pig = EntityType.PIG.create(t.level());
        pig.setNoAi(true);
        pig.moveTo(t.pos(3, 0, 4).getX() + 0.5, t.pos(3, 0, 4).getY(), t.pos(3, 0, 4).getZ() + 0.5);
        t.level().addFreshEntity(pig);
        List<Actor> actors = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(EntityDamageEvent e) {
                if (e.getEntity() == pig) actors.add(e.getActor());
            }
        });
        // An arrow with no owner, as a mod's turret would spawn it; the bridge remembers who the turret belongs to.
        Arrow arrow = new Arrow(EntityType.ARROW, t.level());
        arrow.moveTo(pig.getX(), pig.getY() + 0.45, pig.getZ() - 6, 0, 0);
        arrow.setNoGravity(true);
        arrow.shoot(0, 0, 1, 0.4F, 0);
        t.assertEquals(Attribution.none(), Actors.remembered(arrow), "an arrow nobody remembered");
        Actors.remember(arrow, Attribution.of(alice.uuid()));
        t.assertEquals(Attribution.of(alice.uuid()), Actors.remembered(arrow), "the remembered attribution");
        t.level().addFreshEntity(arrow);
        t.await("the arrow to hit the pig", () -> !actors.isEmpty(), 80);
        t.assertEquals(Optional.of(alice.uuid()), actors.get(0).attribution().responsible(),
                "who is responsible for the remembered arrow's hit");
    }

    @HadronTest(timeout = 800)
    @Covers(value = Actors.class, method = "remember")
    @Covers(value = Actors.class, method = "remembered")
    @Covers(ItemSmeltEvent.class)
    void aRememberedMachineOwnerIsBehindItsWork(TestContext t) {
        dev.iredsc.hadron.selftest.items.Support.watch(t);
        BlockPos furnace = t.pos(2, 0, 0);
        t.setBlock(furnace, Blocks.FURNACE);
        AbstractFurnaceBlockEntity be = (AbstractFurnaceBlockEntity) t.level().getBlockEntity(furnace);
        Actors.remember(be, Attribution.of(ALICE));
        t.assertEquals(Attribution.of(ALICE), Actors.remembered(be), "the furnace's remembered owner");
        List<Actor> seen = new ArrayList<>();
        t.registerEvents(new Listener() {
            @EventHandler
            void on(ItemSmeltEvent e) {
                seen.add(e.getActor());
            }
        });
        be.setItem(0, new ItemStack(Items.RAW_IRON));
        be.setItem(1, new ItemStack(Items.COAL));
        t.await("the furnace to smelt", () -> !seen.isEmpty(), 320);
        t.assertTrue(seen.get(0) instanceof Actor.Machine m && m.pos().equals(furnace)
                && m.attribution().responsible().equals(Optional.of(ALICE)), "the smelt should be the furnace's, "
                + "owned by alice, but was " + seen.get(0));
    }
}
