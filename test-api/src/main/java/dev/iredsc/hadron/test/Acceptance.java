package dev.iredsc.hadron.test;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Event;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * The bridge acceptance test (docs/bridges.md) as a kit: a protection plugin and a logger over one area, neither of
 * which knows any mod, and a record of the area to compare against.
 *
 * <p><b>Refusal proves interception.</b> A change no event reported can't be refused, so it happens anyway; a cancel
 * that a bridge ignores happens anyway too. So a step protection refuses must leave the area as it was, and
 * {@link #assertUnchanged} checks exactly that, comparing the area itself: whether it passes never depends on
 * reading what an event meant. A machine refused at its first action never reaches the rest of its work, so a
 * trusted step can refuse one kind of event at a time with {@link #refuse}, letting the machine run while whatever
 * that kind covers must not happen:
 *
 * <pre>{@code
 * Acceptance area = t.acceptance(BoundingBox.fromCorners(t.pos(-3, 0, -3), t.pos(3, 4, 3)));
 * // bob's quarry in the area, bob not trusted: everything it tries is refused
 * t.tick(100);
 * area.assertRefused(bob, "bob's quarry where he isn't trusted");
 * area.assertUnchanged("bob's quarry where he isn't trusted");
 *
 * // trusted, but its block changes refused: it runs, yet with every dig refused nothing may change at all, not
 * // even its own buffer (a drop kept from a refused break is a dupe)
 * area.trust(bob).refuse(BlockEvent.class).mark();
 * t.tick(100);
 * area.assertRefused(bob, "bob's quarry with its block changes refused");
 * area.assertUnchanged("bob's quarry with its block changes refused");
 * area.assertActingFor(bob, "bob's quarry");
 *
 * // digging allowed, its inventory changes refused: blocks go, but no chest it reaches may change
 * area.refuseNothing().refuse(InventoryEvent.class).mark();
 * t.tick(100);
 * area.assertRefused(bob, "bob's quarry with its inventory changes refused");
 * area.assertUnchanged("bob's quarry with its inventory changes refused", Acceptance.Part.INVENTORIES);
 * // Not caught yet: a drop thrown away when its insert is refused. That is conservation, which waits for
 * // Hadron's design for running resource flows.
 * }</pre>
 *
 * <p><b>What it protects and logs:</b> every block, inventory, resource and item event at a place in the area (an
 * item event by the entity it is about, the inventory it names, or whoever is behind it), every explosion in it or
 * breaking a block in it, every world event whose area overlaps it (a tick accelerator, a portal), and every entity
 * event for an entity in it, except an entity's motion and attention (moving, teleporting, velocity, knockback,
 * targeting, air), so anyone may walk through. Protection
 * denies each that is cancellable unless the server or a trusted player is behind it and its kind isn't refused, so
 * {@link Actor.Unknown}, ownerless machines and other players are outsiders, as {@link Actor} says protection should
 * treat them. An event it can't place at all is denied too, unless the server is behind it, and
 * {@link #assertActingFor} names it: an inventory event whose holder is unknown, an item event nobody is behind, and
 * any action outside the block, entity, item, inventory, resource and world families, whose place the kit can't
 * read. Protection fails closed, so a kind the kit doesn't understand shows up as a named refusal, never a silent
 * pass. The area is watched
 * ({@code Areas.watch}), so a change with nobody behind it fires as Unknown there.
 *
 * <p><b>What it compares</b>, by {@link Part}: block states and which block entity is where; every slot of every
 * inventory a block in the area exposes, through any face; every entity that was in the area at either end, wherever
 * it went (that it came or went, a dropped item's stack, a container entity's slots, a living entity's health); every
 * test player's inventory and health. Not compared: a block entity's other data (a machine's progress, settings or
 * filters), mobs' equipment and inventories that aren't containers, and energy, fluids and other resources a machine
 * holds, which wait for Hadron's design for running resource flows. An entity that waited outside in a chunk whose
 * entities the world doesn't list yet (one still loading, far from {@code pos(0, 0, 0)}) counts as new when it walks
 * in; build the area near the test's centre.
 *
 * <p>Get one with {@link TestContext#acceptance}, after the area is built and every player the test needs has joined:
 * once the area is watched, the test's own setup would be refused as Unknown, and a player who joins after a
 * {@link #mark} has nothing to be compared against, so the assertions refuse that step. It lasts until the test ends.
 */
public interface Acceptance {
    /** The parts of the area {@link #assertUnchanged} can compare. */
    enum Part {
        /** Block states, and which block entity is at each position. */
        BLOCKS,
        /** Every slot of every inventory a block in the area exposes. */
        INVENTORIES,
        /** Entities that were in the area at either end: arriving, leaving, their items, slots and health. */
        ENTITIES,
        /** Every test player's inventory and health. */
        PLAYERS
    }

    /**
     * Lets actions whose {@link Actor#actingFor()} is one of these players through, unless their kind is refused;
     * everyone else stays denied. Returns this, for {@code trust(bob).mark()}.
     */
    Acceptance trust(TestPlayer... players);

    /** Denies everyone but the server again. Returns this. */
    Acceptance trustNobody();

    /**
     * Refuses events of this kind (its subclasses too) from trusted players as well, on top of any refused before:
     * {@code refuse(BlockEvent.class).refuse(InventoryEvent.class)}. The server is never refused. Returns this.
     *
     * @throws IllegalArgumentException for a kind protection can't deny: player events, motion, and events reported
     *         after the fact, which can't be cancelled.
     */
    Acceptance refuse(Class<? extends Event> kind);

    /** Refuses no kind from trusted players again. Returns this. */
    Acceptance refuseNothing();

    /** Records the area as it is now and forgets the events so far: the start of the next step. */
    void mark();

    /**
     * Every event in the area since the last {@link #mark}, with its outcome, and then every event the kit couldn't
     * place anywhere (its {@code pos} empty), which protection refused unless the server was behind it.
     */
    List<Seen> events();

    /**
     * That protection refused at least one event acting for {@code player} since the last {@link #mark}. A refused step
     * where the machine never tried anything proves nothing (it is out of power or range), and nature's or a stray
     * mob's refusals don't count, so assert this beside {@link #assertUnchanged}.
     */
    void assertRefused(TestPlayer player, String step);

    /**
     * That none of {@code parts} (every part, when none is given) changed since the last {@link #mark}: for a step
     * protection refuses. A failure lists each change with the events heard for that place or entity and whether they
     * were cancelled, so it reads as a cancel the bridge ignored or, with nothing heard, an interception gap the bridge
     * closes by firing the core event (docs/bridge-hooks.md).
     *
     * @param step what was done, for the failure: {@code "bob's quarry in alice's region"}.
     * @throws AssertionError also when the test's players changed since the last mark.
     */
    void assertUnchanged(String step, Part... parts);

    /**
     * That every event in the area since the last {@link #mark} acts for {@code player}, leaving out the server's and
     * nature's (water flowing, gravel falling), which are nobody's to attribute, and that at least one does. Events
     * that couldn't be placed fail it too, unless the server or nature is behind them. A failure names each: an
     * attribution gap ({@link Actor.Unknown}, or an actor with no owner), an event acting for someone else, or one that
     * couldn't be placed, with what to fix.
     */
    void assertActingFor(TestPlayer player, String step);

    /** One event the area heard, as it stood after every listener ran. */
    record Seen(Event event, Actor actor, boolean cancelled, Optional<BlockPos> pos) {
        /** Such as {@code BlockBreakEvent by Machine(...) at 1 64 0, cancelled}. */
        @Override
        public String toString() {
            return event.getEventName() + " by " + actor + pos.map(p -> " at " + p.getX() + " " + p.getY() + " "
                    + p.getZ()).orElse("") + (cancelled ? ", cancelled" : "");
        }
    }
}
