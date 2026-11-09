package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityRegainHealthEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntityTransformEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.test.Acceptance.Seen;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** What changed between two snapshots of an acceptance area, and which events explain each change. */
final class AcceptanceDiff {
    private AcceptanceDiff() {}

    /**
     * An entity in the area. {@code health} is -1 for an entity that has none. {@code consequence}: a dropped item,
     * orb or falling block, made by a change elsewhere.
     */
    record Thing(UUID id, String type, ItemStack stack, float health, BlockPos pos, boolean consequence) {}

    /** {@code world}: every entity in the test's world, so one walking in or out of the area isn't taken for new or gone. */
    record Snapshot(Map<BlockPos, BlockState> blocks, Map<BlockPos, String> blockEntities,
            Map<BlockPos, List<ItemStack>> contents, Map<UUID, Thing> entities, Set<UUID> world,
            Map<String, List<ItemStack>> players, Map<String, Float> playerHealth, Set<String> playersInBox) {}

    /**
     * What a step heard: its events, the entities born or gone outside the box (so one crossing its edge isn't new or
     * gone), and each explosion's blocks as they stood when it fired, since the explosion empties its live list later.
     */
    record Step(List<Seen> seen, Set<UUID> crossing, Map<Event, List<BlockPos>> blasts) {}

    /** One difference, and what an event would have to name to explain it. */
    private record Change(String text, Predicate<Seen> explains) {}

    /** Every holder an inventory event names. */
    static Set<InventoryHolder> holders(InventoryEvent event) {
        if (event instanceof InventoryChangeEvent c) return c.getHolders();
        return Set.of(event.getHolder());
    }

    /** Each change, for a step protection should have refused entirely, with what explains it. */
    static List<String> unchanged(Snapshot before, Snapshot after, Step step) {
        List<String> out = new ArrayList<>();
        for (Change c : changes(before, after, true, step)) out.add(c.text() + ": " + explain(c, step.seen()));
        return out;
    }

    /** Whether anything in the area or a test player's inventory changed. */
    static boolean anyChange(Snapshot before, Snapshot after, Step step) {
        return !changes(before, after, true, step).isEmpty();
    }

    /** Each change no event that went through reports. */
    static List<String> unseen(Snapshot before, Snapshot after, Step step) {
        List<Seen> seen = step.seen();
        List<String> out = new ArrayList<>();
        for (Change c : changes(before, after, false, step)) {
            if (seen.stream().noneMatch(s -> !s.cancelled() && c.explains().test(s))) out.add(c.text() + explainUnseen(c, seen));
        }
        return out;
    }

    static List<String> notActingFor(UUID player, String name, List<Seen> seen) {
        List<String> out = new ArrayList<>();
        for (Seen s : seen) {
            // The server is trusted and nature is the world on its own: neither is anyone's to attribute.
            if (s.actor() instanceof Actor.Server || s.actor() instanceof Actor.Nature) continue;
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

    /**
     * {@code consequencesToo}: for a refused step, also dropped items, orbs and falling blocks, and every test player
     * wherever they stand; otherwise only the players standing in the box at either end.
     */
    private static List<Change> changes(Snapshot before, Snapshot after, boolean consequencesToo, Step step) {
        Set<UUID> crossing = step.crossing();
        Function<BlockPos, Predicate<Seen>> blockAt = pos -> blockAt(pos, step.blasts());
        List<Change> out = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : before.blocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState now = after.blocks().get(pos);
            if (!Objects.equals(e.getValue(), now)) {
                out.add(new Change(at(pos) + " went from " + e.getValue() + " to " + now, blockAt.apply(pos)));
                continue;
            }
            String beBefore = before.blockEntities().get(pos), beAfter = after.blockEntities().get(pos);
            if (!Objects.equals(beBefore, beAfter)) {
                out.add(new Change(at(pos) + "'s block entity went from " + beBefore + " to " + beAfter, blockAt.apply(pos)));
            }
        }
        Set<BlockPos> containers = new LinkedHashSet<>(before.contents().keySet());
        containers.addAll(after.contents().keySet());
        for (BlockPos pos : containers) {
            String diff = stacks(before.contents().getOrDefault(pos, List.of()), after.contents().getOrDefault(pos, List.of()));
            if (diff == null) continue;
            Predicate<Seen> inventory = s -> s.event() instanceof InventoryChangeEvent i && holds(i, pos);
            // A block event explains its inventory only when the block itself went or changed (a chest broken);
            // otherwise a machine could report one state change and then move items unreported.
            boolean blockChanged = !Objects.equals(before.blocks().get(pos), after.blocks().get(pos))
                    || !Objects.equals(before.blockEntities().get(pos), after.blockEntities().get(pos));
            out.add(new Change("the inventory at " + at(pos) + " " + diff,
                    blockChanged ? blockAt.apply(pos).or(inventory) : inventory));
        }
        Set<UUID> ids = new LinkedHashSet<>(before.entities().keySet());
        ids.addAll(after.entities().keySet());
        for (UUID id : ids) {
            Thing was = before.entities().get(id), is = after.entities().get(id);
            String what;
            Predicate<Seen> explains;
            if (was == null && is != null) {
                if (is.consequence() && !consequencesToo || before.world().contains(id) || crossing.contains(id)) continue;
                what = "a " + is.type() + describe(is) + " appeared at " + at(is.pos());
                Thing born = is;
                explains = s -> s.event() instanceof EntitySpawnEvent e && e.getEntity().getUUID().equals(id)
                        || s.event() instanceof EntityTransformEvent x && born.type().equals(
                                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(x.getTransformedType())))
                                && x.getEntity().blockPosition().closerThan(born.pos(), 2);
            } else if (was != null && is == null) {
                if (was.consequence() && !consequencesToo || after.world().contains(id) || crossing.contains(id)) continue;
                what = "the " + was.type() + describe(was) + " at " + at(was.pos()) + " left";
                explains = s -> names(s, id) && (s.event() instanceof EntityRemoveEvent
                        || s.event() instanceof EntityDeathEvent || s.event() instanceof HangingBreakEvent
                        || s.event() instanceof EntityTransformEvent);
            } else if (was != null && is != null && !ItemStack.matches(was.stack(), is.stack())) {
                if (is.consequence() && !consequencesToo) continue;
                what = "the " + is.type() + " at " + at(is.pos()) + " went from " + was.stack() + " to " + is.stack();
                explains = s -> names(s, id);
            } else if (was != null && is != null && was.health() != is.health()) {
                what = "the " + is.type() + " at " + at(is.pos()) + " went from " + was.health() + " to "
                        + is.health() + " health";
                explains = s -> names(s, id) && (s.event() instanceof EntityDamageEvent
                        || s.event() instanceof EntityRegainHealthEvent);
            } else {
                continue;
            }
            out.add(new Change(what, explains));
        }
        Predicate<String> counted = name -> consequencesToo || before.playersInBox().contains(name)
                || after.playersInBox().contains(name);
        for (Map.Entry<String, List<ItemStack>> e : before.players().entrySet()) {
            String name = e.getKey();
            List<ItemStack> now = after.players().get(name);
            if (now == null || !counted.test(name)) continue;
            String diff = stacks(e.getValue(), now);
            // By something the player did that spends or gains items (a tool wearing on a break, a block placed, an
            // item used or picked up), or a change to their inventory: not a use or an open, which changes nothing.
            if (diff != null) {
                out.add(new Change(name + "'s inventory " + diff, s -> isPlayer(s.actor(), name)
                        && (s.event() instanceof ItemEvent || s.event() instanceof InventoryChangeEvent
                                || s.event() instanceof BlockEvent b && b.getBlocks().stream().anyMatch(BlockChange::isChanged)
                                || s.event() instanceof EntityDamageEvent || s.event() instanceof EntitySpawnEvent)));
            }
        }
        for (Map.Entry<String, Float> e : before.playerHealth().entrySet()) {
            String name = e.getKey();
            Float now = after.playerHealth().get(name);
            if (now == null || now.floatValue() == e.getValue().floatValue() || !counted.test(name)) continue;
            out.add(new Change(name + "'s health went from " + e.getValue() + " to " + now,
                    s -> (s.event() instanceof EntityDamageEvent || s.event() instanceof EntityRegainHealthEvent)
                            && ((EntityEvent) s.event()).getEntity() instanceof ServerPlayer p
                            && p.getGameProfile().getName().equals(name)));
        }
        return out;
    }

    /** Events that report a change at that position: a block used or damaged there changes nothing, so explains nothing. */
    private static Predicate<Seen> blockAt(BlockPos pos, Map<Event, List<BlockPos>> blasts) {
        return s -> s.event() instanceof BlockEvent b && changes(b, pos)
                || s.event() instanceof ExplosionEvent && blasts.getOrDefault(s.event(), List.of()).contains(pos);
    }

    private static boolean changes(BlockEvent b, BlockPos pos) {
        for (BlockChange change : b.getBlocks()) {
            if (change.getPos().equals(pos)
                    && (change.isChanged() || !change.getBeforeData().equals(change.getAfterData()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayer(Actor actor, String name) {
        return actor instanceof Actor.Player p && p.player().getGameProfile().getName().equals(name);
    }

    private static boolean names(Seen s, UUID id) {
        return s.event() instanceof EntityEvent e && e.getEntity().getUUID().equals(id);
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
