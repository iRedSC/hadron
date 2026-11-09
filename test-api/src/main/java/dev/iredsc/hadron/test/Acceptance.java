package dev.iredsc.hadron.test;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Event;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * The bridge acceptance test (docs/bridges.md) as a kit: a protection plugin and a logger over one area, neither of
 * which knows any mod, and a record of the area to compare against. A bridge passes when they work on its mod's
 * machines, weapons and tools with nothing but core events and the actors the bridge pushes.
 *
 * <pre>{@code
 * Acceptance area = t.acceptance(BoundingBox.fromCorners(t.pos(-3, 0, -3), t.pos(3, 4, 3)));
 * // a quarry bob owns, working in the area; nobody is trusted, so everything it tries is denied
 * t.tick(100);
 * area.assertUnchanged("bob's quarry in a region he isn't trusted in");
 *
 * area.trust(bob).mark();
 * t.tick(100);
 * area.assertEveryChangeSeen("bob's quarry where he is trusted");
 * area.assertActingFor(bob, "bob's quarry where he is trusted");
 * }</pre>
 *
 * <p>What it protects and logs: every block, inventory and resource event at a place in the area, every explosion
 * in it, and every entity event for an entity in it except an entity's own motion and attention (moving, teleporting,
 * velocity, knockback, targeting, air). Protection denies each that is cancellable unless the server or a trusted
 * player is behind it, so {@link Actor.Unknown}, ownerless machines and other players are outsiders, as
 * {@link Actor} says protection should treat them. The area is watched ({@code Areas.watch}), so a change with nobody
 * behind it fires as Unknown there.
 *
 * <p>What it compares: block states and which block entity is where; the items in every inventory a block in the
 * area exposes, on any face; which entities are in it (by id and type, a dropped item's stack, a living entity's
 * health); and every test player's inventory, wherever they stand. Not compared: a block entity's other data (a
 * machine's progress, settings or filters), and energy, fluids and other resources a machine holds. Those wait for
 * Hadron's design for running resource flows, since a machine's own data also changes while it merely ticks.
 *
 * <p>Get one with {@link TestContext#acceptance}. It lasts until the test ends.
 */
public interface Acceptance {
    /**
     * Lets actions whose {@link Actor#actingFor()} is one of these players through; everyone else stays denied.
     * Returns this, for {@code trust(bob).mark()}.
     */
    Acceptance trust(TestPlayer... players);

    /** Denies everyone but the server again. Returns this. */
    Acceptance trustNobody();

    /** Records the area as it is now and forgets the events so far: the start of the next step. */
    void mark();

    /** Every event in the area since the last {@link #mark}, in the order they fired, with their outcome. */
    List<Seen> events();

    /**
     * That nothing in the area, and no test player's inventory, changed since the last {@link #mark}. For a step
     * protection denies. A failure lists each change with what explains it: a cancelled event there whose cancel
     * left it behind, an event that went through, or no event at all, which is an interception gap.
     *
     * @param step what was done, for the failure: {@code "bob's quarry in alice's region"}.
     */
    void assertUnchanged(String step);

    /**
     * That every change in the area since the last {@link #mark} was reported by an event that went through and says
     * it changed that thing: each block by a block event whose change there isn't empty (a use or a crack changes
     * nothing), each block's inventory by an inventory change for it, each entity that appeared by its spawn or
     * transformation, one that left by its removal, death or transformation, and one whose health changed by its
     * damage or healing. Dropped items, experience and falling blocks are exempt, since their contents come from a
     * change reported elsewhere; an entity walking in or out isn't a change. A failure names each unreported change: an
     * interception gap the bridge closes by firing the core event (docs/bridge-hooks.md). A block event explains its
     * block's inventory only when the block itself changed. Fails too when nothing changed, since then the step
     * proves nothing: the machine never ran.
     */
    void assertEveryChangeSeen(String step);

    /**
     * That every event in the area since the last {@link #mark} acts for {@code player}, leaving out the server's and
     * nature's (water flowing, gravel falling), which are nobody's to attribute. A failure names each event that
     * doesn't, and whether it is an attribution gap ({@link Actor.Unknown}, or an actor with no owner) or acts
     * for someone else.
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
