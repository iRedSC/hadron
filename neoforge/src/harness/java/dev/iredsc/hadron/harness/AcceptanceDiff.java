package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.test.Acceptance.Seen;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** What changed between two snapshots of an acceptance area, and which events explain each change. */
final class AcceptanceDiff {
    private AcceptanceDiff() {}

    /** An entity in the area. {@code consequence}: a dropped item, orb or falling block, made by a change elsewhere. */
    record Thing(UUID id, String type, ItemStack stack, BlockPos pos, boolean consequence) {}

    record Snapshot(Map<BlockPos, BlockState> blocks, Map<BlockPos, String> blockEntities,
            Map<BlockPos, List<ItemStack>> contents, Map<UUID, Thing> entities, Map<String, List<ItemStack>> players) {}

    /** One difference, and what an event would have to name to explain it. */
    private record Change(String text, Predicate<Seen> explains) {}

    /** Every holder an inventory event names. */
    static Set<InventoryHolder> holders(InventoryEvent event) {
        if (event instanceof InventoryChangeEvent c) return c.getHolders();
        return Set.of(event.getHolder());
    }

    /** Each change, for a step protection should have refused entirely, with what explains it. */
    static List<String> unchanged(Snapshot before, Snapshot after, List<Seen> seen) {
        List<String> out = new ArrayList<>();
        for (Change c : changes(before, after, true)) out.add(c.text() + ": " + explain(c, seen));
        return out;
    }

    /** Each change no event that went through reports. */
    static List<String> unseen(Snapshot before, Snapshot after, List<Seen> seen) {
        List<String> out = new ArrayList<>();
        for (Change c : changes(before, after, false)) {
            if (seen.stream().noneMatch(s -> !s.cancelled() && c.explains().test(s))) out.add(c.text() + explainUnseen(c, seen));
        }
        return out;
    }

    static List<String> notActingFor(UUID player, String name, List<Seen> seen) {
        List<String> out = new ArrayList<>();
        for (Seen s : seen) {
            Optional<UUID> who = s.actor().actingFor();
            if (who.isPresent() && who.get().equals(player)) continue;
            if (s.actor() instanceof Actor.Unknown) {
                out.add(s + ": Unknown, so nothing was on the actor stack. An attribution gap: push the actor around "
                        + "the work that did this (docs/bridge-hooks.md, \"Always: attribution\")");
            } else if (who.isEmpty()) {
                out.add(s + ": nobody is responsible for it. An attribution gap: remember its owner with "
                        + "Actors.remember when it is placed, spawned or claimed");
            } else {
                out.add(s + ": acts for " + who.get() + ", not " + name);
            }
        }
        return out;
    }

    private static String explain(Change c, List<Seen> seen) {
        List<Seen> here = seen.stream().filter(c.explains()).toList();
        Optional<Seen> through = here.stream().filter(s -> !s.cancelled()).findFirst();
        if (through.isPresent()) return through.get() + " went through: protection let it, so check who it acts for";
        if (!here.isEmpty()) {
            return here.get(0) + ", but it happened anyway: the cancel left this behind. Whatever fires that event must "
                    + "make no change when it comes back cancelled";
        }
        return "no event fired for it. An interception gap: the bridge fires the core event before this change, so "
                + "protection can refuse it (docs/bridge-hooks.md)";
    }

    private static String explainUnseen(Change c, List<Seen> seen) {
        List<Seen> refused = seen.stream().filter(s -> s.cancelled() && c.explains().test(s)).toList();
        if (refused.isEmpty()) return "";
        return " (" + refused.get(0) + " fired here but was cancelled, and the change happened anyway)";
    }

    private static List<Change> changes(Snapshot before, Snapshot after, boolean consequencesToo) {
        List<Change> out = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : before.blocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState now = after.blocks().get(pos);
            if (!Objects.equals(e.getValue(), now)) {
                out.add(new Change(at(pos) + " went from " + e.getValue() + " to " + now, blockAt(pos)));
                continue;
            }
            String beBefore = before.blockEntities().get(pos), beAfter = after.blockEntities().get(pos);
            if (!Objects.equals(beBefore, beAfter)) {
                out.add(new Change(at(pos) + "'s block entity went from " + beBefore + " to " + beAfter, blockAt(pos)));
            }
        }
        Set<BlockPos> containers = new LinkedHashSet<>(before.contents().keySet());
        containers.addAll(after.contents().keySet());
        for (BlockPos pos : containers) {
            String diff = stacks(before.contents().getOrDefault(pos, List.of()), after.contents().getOrDefault(pos, List.of()));
            if (diff != null) {
                out.add(new Change("the inventory at " + at(pos) + " " + diff,
                        blockAt(pos).or(s -> s.event() instanceof InventoryEvent i && holds(i, pos))));
            }
        }
        Set<UUID> ids = new LinkedHashSet<>(before.entities().keySet());
        ids.addAll(after.entities().keySet());
        for (UUID id : ids) {
            Thing was = before.entities().get(id), is = after.entities().get(id);
            String what;
            if (was == null && is != null) {
                if (is.consequence() && !consequencesToo) continue;
                what = "a " + is.type() + describe(is) + " appeared at " + at(is.pos());
            } else if (was != null && is == null) {
                if (was.consequence() && !consequencesToo) continue;
                what = "the " + was.type() + describe(was) + " at " + at(was.pos()) + " left";
            } else if (was != null && is != null && !ItemStack.matches(was.stack(), is.stack())) {
                if (is.consequence() && !consequencesToo) continue;
                what = "the " + is.type() + " at " + at(is.pos()) + " went from " + was.stack() + " to " + is.stack();
            } else {
                continue;
            }
            out.add(new Change(what, s -> s.event() instanceof EntityEvent ee && ee.getEntity().getUUID().equals(id)));
        }
        if (consequencesToo) {
            for (Map.Entry<String, List<ItemStack>> e : before.players().entrySet()) {
                List<ItemStack> now = after.players().get(e.getKey());
                if (now == null) continue;
                String diff = stacks(e.getValue(), now);
                if (diff != null) {
                    String name = e.getKey();
                    out.add(new Change(name + "'s inventory " + diff, s -> s.actor() instanceof Actor.Player p && p.player().getGameProfile().getName().equals(name)));
                }
            }
        }
        return out;
    }

    private static Predicate<Seen> blockAt(BlockPos pos) {
        return s -> s.event() instanceof BlockEvent b && covers(b, pos);
    }

    private static boolean covers(BlockEvent b, BlockPos pos) {
        if (b.getPos().equals(pos)) return true;
        for (BlockChange change : b.getBlocks()) if (change.getPos().equals(pos)) return true;
        return false;
    }

    private static boolean holds(InventoryEvent event, BlockPos pos) {
        for (InventoryHolder h : holders(event)) if (blockOf(h).filter(pos::equals).isPresent()) return true;
        return false;
    }

    private static Optional<BlockPos> blockOf(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Block b -> Optional.of(b.pos());
            case InventoryHolder.Item i -> blockOf(i.outer());
            case InventoryHolder.PerPlayer p -> blockOf(p.at());
            default -> Optional.empty();
        };
    }

    private static String describe(Thing t) {
        return t.stack().isEmpty() ? "" : " of " + t.stack();
    }

    /** How two lists of slots differ, or null when every slot matches. */
    private static @org.jspecify.annotations.Nullable String stacks(List<ItemStack> before, List<ItemStack> after) {
        int n = Math.max(before.size(), after.size());
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack a = i < before.size() ? before.get(i) : ItemStack.EMPTY;
            ItemStack b = i < after.size() ? after.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(a, b)) slots.add("slot " + i + " " + a + " -> " + b);
        }
        return slots.isEmpty() ? null : "changed (" + String.join(", ", slots) + ")";
    }

    private static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
