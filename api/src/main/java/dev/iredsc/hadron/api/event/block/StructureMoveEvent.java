package dev.iredsc.hadron.api.event.block;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Carried;
import java.util.ArrayList;
import dev.iredsc.hadron.api.event.FiredBy;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import static dev.iredsc.hadron.api.event.FiredBy.Core.NONE;

/**
 * A group of blocks moves as one within a level: a Create contraption, a Valkyrien Skies ship. Fired when it
 * assembles out of the world, each time it moves or rotates, and when it disassembles back into blocks. Cancel it and
 * that step doesn't happen.
 *
 * <p><b>Fired by bridges</b>: vanilla has nothing that moves a group of blocks this way, so core doesn't fire it. A
 * bridge fires it before its mod changes anything, with the transforms before and after, the blocks leaving or
 * entering the world as {@link #getMoves()}, and every space the structure passes through or lands in as
 * {@link #getSweptSpace()}; while it only moves, with no blocks entering or leaving, moves may be empty. It names
 * every entity riding along (standing on it, seated in it, riders of those) as {@link #getCarried()}, each where
 * the transforms carry it, so a protection plugin sees the crew a ship carries into a region.
 */
@FiredBy(core = NONE,
        bridges = "assembles, moves, rotates or disassembles a contraption or ship within one level",
        fillIn = "source to target mapping, swept space, old and new transforms, every entity riding along")
public class StructureMoveEvent extends BlockMoveEvent {
    /** Which step of the structure's life this is. */
    public enum Phase {
        /** Blocks leave the world to become the structure. */
        ASSEMBLE,
        /** The structure moves or rotates. */
        MOVE,
        /** The structure turns back into blocks in the world. */
        DISASSEMBLE
    }

    private final Phase phase;
    private final Matrix4dc from;
    private final Matrix4dc to;

    /**
     * @param from the structure's transform from its own block grid into the world, before this step
     * @param to the same transform after this step
     * @param crew every entity riding along (standing on it, seated in it, riders of those), as they stand now; each
     *     ends up where {@code from} and {@code to} carry it. Empty for an empty ship.
     */
    public StructureMoveEvent(ServerLevel level, BlockPos pos, BlockState state, Phase phase, Matrix4dc from,
            Matrix4dc to, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, List<Entity> crew,
            Actor actor) {
        this(level, pos, state, phase, from, to, moves, swept, blocks, carry(level, from, to, crew), actor);
    }

    /**
     * A step whose crew the bridge can't see before it happens: {@code carried} bounds where any of it could be,
     * before and after, so protection denies it only where that reaches.
     */
    public StructureMoveEvent(ServerLevel level, BlockPos pos, BlockState state, Phase phase, Matrix4dc from,
            Matrix4dc to, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Carried.Unknown carried,
            Actor actor) {
        this(level, pos, state, phase, from, to, moves, swept, blocks, (Carried) carried, actor);
    }

    private StructureMoveEvent(ServerLevel level, BlockPos pos, BlockState state, Phase phase, Matrix4dc from,
            Matrix4dc to, List<Move> moves, List<BlockPos> swept, List<BlockChange> blocks, Carried carried,
            Actor actor) {
        super(level, pos, state, moves, swept, blocks, carried, actor);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.from = new Matrix4d(Objects.requireNonNull(from, "from; use new Matrix4d() for the identity"));
        this.to = new Matrix4d(Objects.requireNonNull(to, "to; use new Matrix4d() for the identity"));
    }

    /** Each of the crew and where the step carries it: from the world through {@code from}'s inverse, then {@code to}. */
    private static Carried.Known carry(ServerLevel level, Matrix4dc from, Matrix4dc to, List<Entity> crew) {
        Objects.requireNonNull(from, "from; use new Matrix4d() for the identity");
        Objects.requireNonNull(to, "to; use new Matrix4d() for the identity");
        Objects.requireNonNull(crew, "crew; use List.of() when nothing rides along");
        if (crew.isEmpty()) return Carried.none();
        if (from.determinant() == 0) {
            throw new IllegalArgumentException("from can't be inverted, so where the crew ends up can't be worked out: "
                    + "pass the structure's real transform into the world");
        }
        Matrix4d step = new Matrix4d(to).mul(new Matrix4d(from).invert());
        List<Carried.Ride> rides = new ArrayList<>();
        for (Entity e : crew) {
            Vector3d p = step.transformPosition(new Vector3d(e.getX(), e.getY(), e.getZ()));
            rides.add(Carried.ride(e, level, new Vec3(p.x, p.y, p.z)));
        }
        return Carried.of(rides);
    }

    public Phase getPhase() {
        return phase;
    }

    /** The structure's transform into the world before this step. */
    public Matrix4dc getFrom() {
        return from;
    }

    /** The structure's transform into the world after this step. */
    public Matrix4dc getTo() {
        return to;
    }
}
