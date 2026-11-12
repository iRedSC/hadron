package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The acceptance kit's check of what block moves carry: each move in the box is read with the entities touching its
 * blocks as it fired, and judged by what moved between it and the next move. A refused move whose entities moved
 * anyway is a change; a bridge's move let through must name each entity it moved, itself.
 */
final class AcceptanceCarriage {
    /**
     * A block move, where each entity touching its blocks stood when it fired, and where each entity an earlier move
     * stood beside was then: so each move is judged by what moved between it and the next, never by another's.
     */
    private record Beside(BlockMoveEvent event, Map<UUID, Vec3> stood, Map<UUID, Vec3> others) {
        @Nullable Vec3 at(UUID id) {
            Vec3 p = stood.get(id);
            return p != null ? p : others.get(id);
        }
    }

    /** Finds an entity by id now, wherever it is; null when it is gone. */
    private final Function<UUID, @Nullable Entity> live;
    private final List<Beside> beside = new ArrayList<>();

    AcceptanceCarriage(Function<UUID, @Nullable Entity> live) {
        this.live = live;
    }

    /** Forgets the moves so far: the start of the next step. */
    void clear() {
        beside.clear();
    }

    /** A block move heard in the box, as it fires. */
    void heard(BlockMoveEvent move) {
        beside.add(beside(move));
    }

    private @Nullable Entity live(UUID id) {
        return live.apply(id);
    }

    /**
     * Where every entity touching a move's blocks stands now, by id: one riding it, standing on it, or stuck to its
     * side. Players too, since a ship's crew is often a player.
     */
    private static Map<UUID, Vec3> standingBeside(BlockMoveEvent move) {
        List<AABB> blocks = new ArrayList<>();
        for (BlockMoveEvent.Move m : move.getMoves()) {
            blocks.add(new AABB(m.from()).inflate(0.5));
            blocks.add(new AABB(m.to()).inflate(0.5));
        }
        for (BlockPos swept : move.getSweptSpace()) blocks.add(new AABB(swept).inflate(0.5));
        Map<UUID, Vec3> stood = new LinkedHashMap<>();
        if (blocks.isEmpty()) return stood;
        AABB all = blocks.get(0);
        for (AABB b : blocks) all = all.minmax(b);
        for (Entity e : move.getLevel().getEntities((Entity) null, all,
                e -> blocks.stream().anyMatch(b -> b.intersects(e.getBoundingBox())))) {
            stood.put(e.getUUID(), e.position());
        }
        return stood;
    }

    /** A move as it fires: who stands beside it, and where everyone an earlier move stood beside is now. */
    private Beside beside(BlockMoveEvent move) {
        Map<UUID, Vec3> stood = standingBeside(move);
        Map<UUID, Vec3> others = new LinkedHashMap<>();
        for (Beside earlier : beside) {
            for (UUID id : earlier.stood().keySet()) {
                if (stood.containsKey(id) || others.containsKey(id)) continue;
                Entity e = live(id);
                if (e != null) others.put(id, e.position());
            }
        }
        return new Beside(move, stood, others);
    }

    /**
     * Whether the entity that stood at {@code was} beside move {@code i} moved before the next move fired, or, after the
     * last, by now: what that move alone could have carried.
     */
    private boolean movedDuring(int i, UUID id, Vec3 was) {
        for (int j = i + 1; j < beside.size(); j++) {
            Vec3 then = beside.get(j).at(id);
            if (then != null) return then.distanceToSqr(was) > 1.0E-6;
        }
        Entity now = live(id);
        return now != null && moved(now, was, beside.get(i).event().getLevel());
    }

    /** Whether it no longer stands where it did, or has gone to another world. */
    private static boolean moved(Entity now, Vec3 was, ServerLevel wasIn) {
        return now.level() != wasIn || now.position().distanceToSqr(was) > 1.0E-6;
    }

    /** Every entity beside a refused block move that moved anyway: the move carried it though it was refused. */
    List<String> movedDespiteRefusal() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < beside.size(); i++) {
            Beside b = beside.get(i);
            if (!b.event().isCancelled()) continue;
            for (Map.Entry<UUID, Vec3> stood : b.stood().entrySet()) {
                Entity now = live(stood.getKey());
                if (now == null || !movedDuring(i, stood.getKey(), stood.getValue())) continue;
                out.add("the " + now.getName().getString() + " beside " + b.event().getEventName() + "'s blocks moved "
                        + "from " + BlockPos.containing(stood.getValue()).toShortString() + " to "
                        + now.blockPosition().toShortString() + ", though the move was refused: whatever moves the "
                        + "structure must leave what rides it where it was when the event comes back cancelled");
            }
        }
        return out;
    }

    /**
     * Each bridge's block move in the box that doesn't name what it carried: {@link Carried.Unknown}, which protection
     * denies, or, let through, an entity beside its blocks that moved before the next move but isn't in that move's
     * own {@code getCarried()}: another move naming it doesn't cover this one. A piston's carriage is unknown by design
     * (it picks entities up as it travels), so it is left out.
     */
    List<String> carriedUnnamed() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < beside.size(); i++) {
            Beside b = beside.get(i);
            BlockMoveEvent move = b.event();
            if (move instanceof BlockPistonEvent) continue;
            switch (move.getCarried()) {
                case Carried.Unknown u -> out.add(move.getEventName() + ": getCarried() is Unknown (" + u.reason()
                        + "), so protection denies it wherever its bound reaches. Name every entity riding along: "
                        + "StructureMoveEvent takes the crew, a BlockMoveEvent a Carried.ride for each with where it "
                        + "ends up, or Carried.none() when nothing is");
                case Carried.Known k -> {
                    if (move.isCancelled()) continue;
                    Set<UUID> named = new HashSet<>();
                    k.entities().forEach(e -> named.add(e.getUUID()));
                    for (Map.Entry<UUID, Vec3> stood : b.stood().entrySet()) {
                        Entity now = live(stood.getKey());
                        if (now == null || named.contains(stood.getKey())
                                || !movedDuring(i, stood.getKey(), stood.getValue())) {
                            continue;
                        }
                        out.add(move.getEventName() + " moved the " + now.getName().getString() + " from "
                                + BlockPos.containing(stood.getValue()).toShortString() + " to "
                                + now.blockPosition().toShortString() + ", but its getCarried() doesn't name it: "
                                + "pass every entity riding along to the event");
                    }
                }
            }
        }
        return out;
    }
}
