package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.StructureMoveEvent;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;

/** Entry into what a move crosses on the way, as its event states it, as well as where it ends. */
public final class CrossingTests {
    /** A wall one block thick at x 4 across the lane players arrive in, which outsiders may not enter. */
    private static TestPlayer wall(TestContext t) {
        TestPlayer alice = RegionsTests.admin(t);
        RegionsTests.define(t, alice, "thin-wall", t.pos(4, 0, -2), t.pos(4, 2, 2));
        alice.command("rg flag thin-wall entry deny");
        return alice;
    }

    /** Six blocks east in one move, as a fast flight's packet says it. */
    private static void dash(TestPlayer p) {
        Vec3 at = p.player().position();
        p.player().connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(at.x + 6, at.y, at.z, true));
    }

    @HadronTest
    void aFastMoveCantCrossARegionItNeverEndsIn(TestContext t) {
        wall(t);
        TestPlayer bob = t.player("bob");
        Vec3 before = bob.player().position();
        dash(bob);
        t.assertEquals(before, bob.player().position(), "where bob stood, his move across the wall refused");
        t.tick(2);
        t.assertEquals(t.pos(0, 0, 0), bob.player().blockPosition(), "bob's block, put back before the wall");
        // The control: the same move along a lane the wall doesn't reach goes ahead.
        TestPlayer carol = t.player("carol");
        carol.moveTo(t.pos(0, 0, -4));
        t.tick(2);
        dash(carol);
        t.tick(2);
        t.assertEquals(t.pos(6, 0, -4), carol.player().blockPosition(), "carol's block, past nothing denied");
    }

    @HadronTest
    void aTeleportCrossesNothing(TestContext t) {
        wall(t);
        TestPlayer bob = t.player("bob");
        BlockPos beyond = t.pos(6, 0, 0);
        bob.teleport(beyond.getX() + 0.5, beyond.getY(), beyond.getZ() + 0.5);
        t.tick(3);
        t.assertEquals(beyond, bob.player().blockPosition(), "bob's block, teleported over the wall");
    }

    @HadronTest
    void aRiderCantBeCarriedAcrossARegion(TestContext t) {
        BlockPos start = t.pos(1, 0, 0);
        Boat boat = EntityType.BOAT.create(t.level());
        boat.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, -90f, 0f);
        boat.setNoGravity(true);
        t.level().addFreshEntity(boat);
        TestPlayer alice = wall(t);
        TestPlayer carol = t.player("carol");
        alice.command("rg addmember thin-wall carol");
        TestPlayer bob = t.player("bob");
        carol.interact(boat);
        bob.interact(boat);
        t.assertTrue(carol.player().getVehicle() == boat && bob.player().getVehicle() == boat,
                "carol and bob should be in the boat");
        t.tick(2);
        Vec3 bobWas = bob.player().position();
        // Carol, a member, drives six blocks east across the wall with bob, an outsider, aboard; both ends outside.
        drive(carol, boat);
        t.assertEquals(bobWas, bob.player().position(), "bob's seat, the crossing refused");
        t.tick(2);
        t.assertTrue(bob.player().getX() < t.pos(4, 0, 0).getX(), "bob should still be west of the wall, but sits at "
                + bob.player().position());
        // The control: once bob is a member, the same drive carries him across.
        alice.command("rg addmember thin-wall bob");
        drive(carol, boat);
        t.assertTrue(bob.player().getX() >= t.pos(5, 0, 0).getX(), "the drive should carry a member across the "
                + "wall, but bob sits at " + bob.player().position());
    }

    /** Carol's client says her boat has gone six blocks east. */
    private static void drive(TestPlayer carol, Boat boat) {
        Boat client = new Boat(EntityType.BOAT, boat.level());
        client.moveTo(boat.getX() + 6, boat.getY(), boat.getZ(), boat.getYRot(), 0f);
        carol.player().connection.handleMoveVehicle(new ServerboundMoveVehiclePacket(client));
    }

    /**
     * A ship whose deck is at {@code (0, 0, z)}, moving six blocks east with {@code crew} aboard, steered by {@code
     * pilot}, sweeping the blocks from there to {@code (6, 0, z)}, as a bridge fires it. Whether it was refused.
     */
    private static boolean sail(TestContext t, TestPlayer pilot, int z, List<Player> crew) {
        BlockPos deck = t.pos(0, 0, z);
        List<BlockPos> swept = new ArrayList<>();
        for (int x = 1; x <= 6; x++) swept.add(t.pos(x, 0, z));
        StructureMoveEvent e = new StructureMoveEvent(t.level(), deck, Blocks.OAK_PLANKS.defaultBlockState(),
                StructureMoveEvent.Phase.MOVE, new Matrix4d(), new Matrix4d().translation(6, 0, 0), List.of(), swept,
                List.of(BlockChange.unchanged(t.level(), deck, Blocks.OAK_PLANKS.defaultBlockState())),
                List.copyOf(crew), new Actor.Player(pilot.player()));
        t.plugin().getServer().getPluginManager().callEvent(e);
        return e.isCancelled();
    }

    @HadronTest
    void aShipCantCarryAnOutsiderAcrossARegion(TestContext t) {
        TestPlayer alice = wall(t);
        alice.command("rg addmember thin-wall alice");
        TestPlayer bob = t.player("bob");
        // Alice, a member, sails across the wall with bob, an outsider, aboard; both of bob's ends are outside.
        t.assertTrue(sail(t, alice, 0, List.of(bob.player())), "a ship carrying bob across the wall is refused");
        // The control: the same voyage along a lane the wall doesn't reach goes ahead.
        t.assertFalse(sail(t, alice, 4, List.of(bob.player())), "a ship carrying bob past nothing denied sails");
        // A member's crew crosses.
        t.assertFalse(sail(t, alice, 0, List.of(alice.player())), "alice, a member, sails her crew across the wall");
    }
}
