package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemDespawnEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.WorldEvent;
import dev.iredsc.hadron.test.Acceptance.Part;
import dev.iredsc.hadron.test.Acceptance.Seen;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What changed between two snapshots of an acceptance area. Whether anything changed decides the assertion; the
 * events heard only word its message, so a wrong guess about what an event meant can't make a check pass or fail.
 */
final class AcceptanceDiff {
    private AcceptanceDiff() {}

    /**
     * An entity in the test's world. {@code health} is -1 for an entity that has none. {@code contents}: a container
     * entity's slots (a chest minecart's), by label. {@code inBox}: whether it stands in the area.
     */
    record Thing(UUID id, String type, ItemStack stack, float health, Map<String, ItemStack> contents, BlockPos pos,
            boolean inBox) {}

    /**
     * The area at one moment. {@code contents}: each block's slots by label ({@code slot 3} for its own inventory,
     * {@code north slot 3} or {@code unsided slot 3} for another handler it offers). {@code entities}: every entity in
     * the test's world but players, so one that was in the area is followed wherever it goes. Players by name.
     */
    record Snapshot(Map<BlockPos, BlockState> blocks, Map<BlockPos, String> blockEntities,
            Map<BlockPos, Map<String, ItemStack>> contents, Map<UUID, Thing> entities,
            Map<String, UUID> playerIds, Map<String, List<ItemStack>> players, Map<String, Float> playerHealth) {}

    /** One difference, and which heard events concern the same place, entity or player, for the message. */
    private record Change(String text, Predicate<Seen> concerns) {}

    /** Each change in {@code parts}, with the events heard for it. Empty when nothing in them changed. */
    static List<String> changes(Snapshot before, Snapshot after, Set<Part> parts, List<Seen> seen, Set<UUID> touched,
            Set<UUID> joinedOutside) {
        List<Change> found = new ArrayList<>();
        if (parts.contains(Part.BLOCKS)) blocks(before, after, found);
        if (parts.contains(Part.INVENTORIES)) inventories(before, after, found);
        if (parts.contains(Part.ENTITIES)) entities(before, after, touched, joinedOutside, found);
        if (parts.contains(Part.PLAYERS)) players(before, after, found);
        List<String> out = new ArrayList<>();
        for (Change c : found) out.add(c.text() + ": " + heard(c, seen));
        return out;
    }

    /** Whether nobody is to attribute this actor's events to: the server, which is trusted, or nature. */
    static boolean nobodys(Actor actor) {
        return actor instanceof Actor.Server || actor instanceof Actor.Nature;
    }

    private static String heard(Change c, List<Seen> seen) {
        List<Seen> here = seen.stream().filter(c.concerns()).toList();
        if (here.isEmpty()) {
            return "no event was heard for it. An interception gap: the bridge fires the core event before this "
                    + "change, so protection can refuse it (docs/bridge-hooks.md)";
        }
        String list = String.join("; ", here.stream().limit(3).map(Seen::toString).toList())
                + (here.size() > 3 ? "; and " + (here.size() - 3) + " more" : "");
        if (here.stream().anyMatch(Seen::cancelled)) {
            return "heard " + list + ". If a cancelled one covered this, the cancel left it behind: whatever fires it "
                    + "must change nothing when it comes back cancelled. If none did, nothing reported it";
        }
        return "heard " + list + ", none cancelled: protection let them through, so check who they act for and "
                + "which kinds this step refuses";
    }

    private static void blocks(Snapshot before, Snapshot after, List<Change> out) {
        for (Map.Entry<BlockPos, BlockState> e : before.blocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState now = after.blocks().get(pos);
            String beBefore = before.blockEntities().get(pos), beAfter = after.blockEntities().get(pos);
            if (!Objects.equals(e.getValue(), now)) {
                out.add(new Change(at(pos) + " went from " + e.getValue() + " to " + now, near(pos, true)));
            } else if (!Objects.equals(beBefore, beAfter)) {
                out.add(new Change(at(pos) + "'s block entity went from " + beBefore + " to " + beAfter,
                        near(pos, true)));
            }
        }
    }

    private static void inventories(Snapshot before, Snapshot after, List<Change> out) {
        Set<BlockPos> holders = new LinkedHashSet<>(before.contents().keySet());
        holders.addAll(after.contents().keySet());
        for (BlockPos pos : holders) {
            Map<String, ItemStack> was = before.contents().getOrDefault(pos, Map.of());
            Map<String, ItemStack> is = after.contents().getOrDefault(pos, Map.of());
            for (String label : differing(was, is)) {
                out.add(new Change("the inventory at " + at(pos) + ", " + label + ": "
                        + was.getOrDefault(label, ItemStack.EMPTY) + " -> " + is.getOrDefault(label, ItemStack.EMPTY),
                        near(pos, false)));
            }
        }
    }

    private static void entities(Snapshot before, Snapshot after, Set<UUID> touched, Set<UUID> joinedOutside,
            List<Change> out) {
        Set<UUID> ids = new LinkedHashSet<>(before.entities().keySet());
        ids.addAll(after.entities().keySet());
        for (UUID id : ids) {
            Thing was = before.entities().get(id), is = after.entities().get(id);
            // What was in the area at either end, or that an event there named on its way through.
            if (!(was != null && was.inBox() || is != null && is.inBox() || touched.contains(id))) continue;
            // An item lying in the area is also what a pickup, a merge or a despawn is about.
            Predicate<Seen> concerns = s -> s.event() instanceof EntityEvent e && e.getEntity().getUUID().equals(id)
                    || s.event() instanceof ItemPickupEvent p && p.getItemEntity().getUUID().equals(id)
                    || s.event() instanceof ItemMergeEvent m && (m.getItemEntity().getUUID().equals(id)
                            || m.getTarget().getUUID().equals(id))
                    || s.event() instanceof ItemDespawnEvent d && d.getItemEntity().getUUID().equals(id);
            if (was == null && is != null) {
                // Joined the world outside and came in: motion, as any entity walking in is.
                if (joinedOutside.contains(id)) continue;
                out.add(new Change("a " + is.type() + describe(is) + " appeared at " + at(is.pos()), concerns));
                continue;
            }
            if (was != null && is == null) {
                out.add(new Change("the " + was.type() + describe(was) + " at " + at(was.pos()) + " is gone",
                        concerns));
                continue;
            }
            if (was == null || is == null) continue;
            if (!was.type().equals(is.type())) {
                out.add(new Change("the " + was.type() + " at " + at(was.pos()) + " became a " + is.type(), concerns));
            }
            if (!ItemStack.matches(was.stack(), is.stack())) {
                out.add(new Change("the " + is.type() + " at " + at(is.pos()) + " went from " + was.stack() + " to "
                        + is.stack(), concerns));
            }
            for (String label : differing(was.contents(), is.contents())) {
                out.add(new Change("the " + is.type() + " at " + at(is.pos()) + ", " + label + ": "
                        + was.contents().getOrDefault(label, ItemStack.EMPTY) + " -> "
                        + is.contents().getOrDefault(label, ItemStack.EMPTY), concerns));
            }
            if (was.health() != is.health()) {
                out.add(new Change("the " + is.type() + " at " + at(is.pos()) + " went from " + was.health() + " to "
                        + is.health() + " health", concerns));
            }
        }
    }

    private static void players(Snapshot before, Snapshot after, List<Change> out) {
        for (Map.Entry<String, List<ItemStack>> e : before.players().entrySet()) {
            String name = e.getKey();
            UUID id = Objects.requireNonNull(before.playerIds().get(name), "every player has an id");
            Predicate<Seen> concerns = s -> s.actor().actingFor().filter(id::equals).isPresent()
                    || s.event() instanceof EntityEvent ee && ee.getEntity().getUUID().equals(id);
            String diff = slots(e.getValue(), after.players().getOrDefault(name, List.of()));
            if (diff != null) out.add(new Change(name + "'s inventory " + diff, concerns));
            Float was = before.playerHealth().get(name), is = after.playerHealth().get(name);
            if (was != null && is != null && was.floatValue() != is.floatValue()) {
                out.add(new Change(name + "'s health went from " + was + " to " + is, concerns));
            }
        }
    }

    /**
     * Events heard that reach {@code pos}: by where they were placed, a block space they list, any inventory they name
     * there, an area they cover (a tick accelerator's, a portal's), or a chunk they name. Only for the message.
     */
    private static Predicate<Seen> near(BlockPos pos, boolean listed) {
        return s -> s.pos().filter(pos::equals).isPresent() || reaches(s.event(), pos, listed);
    }

    private static boolean reaches(Event event, BlockPos pos, boolean listed) {
        return switch (event) {
            case BlockEvent b -> listed && b.getBlocks().stream().map(BlockChange::getPos).anyMatch(pos::equals);
            case InventoryChangeEvent i -> i.getHolders().stream()
                    .anyMatch(h -> h instanceof InventoryHolder.Block b && b.pos().equals(pos));
            case WorldEvent w -> w.getArea().filter(a -> a.isInside(pos)).isPresent();
            case ResourceEvent r -> r.getHolders().stream().anyMatch(h -> h instanceof ResourceHolder.Chunk c
                    && c.pos().equals(new ChunkPos(pos)) || h instanceof ResourceHolder.Block b && b.pos().equals(pos));
            default -> false;
        };
    }

    private static List<String> differing(Map<String, ItemStack> a, Map<String, ItemStack> b) {
        Set<String> labels = new LinkedHashSet<>(a.keySet());
        labels.addAll(b.keySet());
        List<String> out = new ArrayList<>();
        for (String l : labels) {
            if (!ItemStack.matches(a.getOrDefault(l, ItemStack.EMPTY), b.getOrDefault(l, ItemStack.EMPTY))) out.add(l);
        }
        return out;
    }

    /** How two lists of slots differ, or null when every slot matches. */
    private static @org.jspecify.annotations.Nullable String slots(List<ItemStack> before, List<ItemStack> after) {
        int n = Math.max(before.size(), after.size());
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack a = i < before.size() ? before.get(i) : ItemStack.EMPTY;
            ItemStack b = i < after.size() ? after.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(a, b)) slots.add("slot " + i + " " + a + " -> " + b);
        }
        return slots.isEmpty() ? null : "changed (" + String.join(", ", slots) + ")";
    }

    private static String describe(Thing t) {
        return t.stack().isEmpty() ? "" : " of " + t.stack();
    }

    private static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
