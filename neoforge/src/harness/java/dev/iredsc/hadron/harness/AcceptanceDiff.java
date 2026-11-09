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
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.test.Acceptance.Seen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** What changed between two snapshots of an acceptance area, and which events explain each change. */
final class AcceptanceDiff {
    private AcceptanceDiff() {}

    /** A slot label meaning every slot of that block's inventory. */
    static final String ANY_SLOT = "*";

    /**
     * An entity in the area. {@code health} is -1 for an entity that has none. {@code contents}: a container entity's
     * slots (a chest minecart's), by label. {@code consequence}: a dropped item, orb or falling block, made by a change
     * elsewhere.
     */
    record Thing(UUID id, String type, ItemStack stack, float health, Map<String, ItemStack> contents, BlockPos pos,
            boolean consequence) {}

    /**
     * The area at one moment. {@code contents}: each block's slots by label, {@code slot 3} for the block's own
     * inventory and {@code north slot 3} for a separate store behind one face. {@code world}: every entity in the
     * test's world, so one walking in or out of the area isn't taken for new or gone.
     */
    record Snapshot(Map<BlockPos, BlockState> blocks, Map<BlockPos, String> blockEntities,
            Map<BlockPos, Map<String, ItemStack>> contents, Map<UUID, Thing> entities, Set<UUID> world,
            Map<String, List<ItemStack>> players, Map<String, Float> playerHealth, Set<String> playersInBox) {}

    /**
     * What one event said it changes, copied while it fired: live block entities and an explosion's list are gone or
     * emptied by the time an assertion reads them. {@code slots} and {@code entitySlots}: per block or entity, the slot
     * labels it names, or {@link #ANY_SLOT}.
     */
    record Report(Set<BlockPos> blocks, Map<BlockPos, Set<String>> slots, Map<UUID, Set<String>> entitySlots) {
        static final Report NONE = new Report(Set.of(), Map.of(), Map.of());
    }

    /**
     * What a step heard: its events, each one's report, and the entities born or gone outside the box (so one crossing
     * its edge isn't new or gone).
     */
    record Step(List<Seen> seen, Map<Event, Report> reports, Set<UUID> crossing) {
        Report of(Seen s) {
            return reports.getOrDefault(s.event(), Report.NONE);
        }
    }

    /** One difference, and what an event would have to report to explain it. */
    private record Change(String text, Predicate<Seen> explains) {}

    /** Every holder an inventory event names. */
    static Set<InventoryHolder> holders(InventoryEvent event) {
        if (event instanceof InventoryChangeEvent c) return c.getHolders();
        return Set.of(event.getHolder());
    }

    /** What the event says it changes, read now, while its block entities and lists are as it fired them. */
    static Report report(Event event) {
        if (event instanceof BlockEvent b) {
            Set<BlockPos> changed = new HashSet<>();
            for (BlockChange c : b.getBlocks()) if (c.isChanged() || dataChanged(c)) changed.add(c.getPos());
            return new Report(changed, Map.of(), Map.of());
        }
        if (event instanceof ExplosionEvent x) return new Report(Set.copyOf(x.blockList()), Map.of(), Map.of());
        Map<BlockPos, Set<String>> slots = new HashMap<>();
        Map<UUID, Set<String>> entitySlots = new HashMap<>();
        if (event instanceof InventoryChangeEvent i) {
            for (InventoryChangeEvent.Change c : i.getChanges()) {
                String label = c.slot() == InventoryChangeEvent.Change.ANY_SLOT ? ANY_SLOT : "slot " + c.slot();
                blockOf(c.holder()).ifPresent(pos -> slots.computeIfAbsent(pos, p -> new HashSet<>()).add(label));
                if (c.holder() instanceof InventoryHolder.Entity holder) {
                    entitySlots.computeIfAbsent(holder.entity().getUUID(), id -> new HashSet<>()).add(label);
                }
            }
        } else if (event instanceof InventoryClickEvent click) {
            // A plain click moves only the clicked slot; a shift-click, a collect-all or a drag can move any slot of
            // any inventory in the menu.
            boolean spreads = click.getClick() == ClickType.QUICK_MOVE || click.getClick() == ClickType.PICKUP_ALL
                    || click.getClick() == ClickType.QUICK_CRAFT;
            Optional<Slot> clicked = click.getSlot();
            for (Slot slot : click.getMenu().slots) {
                boolean isClicked = clicked.isPresent() && clicked.get() == slot;
                if (!spreads && !isClicked) continue;
                String label = spreads ? ANY_SLOT : "slot " + slot.getContainerSlot();
                if (slot.container instanceof BlockEntity be) {
                    slots.computeIfAbsent(be.getBlockPos(), p -> new HashSet<>()).add(label);
                } else if (slot.container instanceof net.minecraft.world.entity.Entity e) {
                    entitySlots.computeIfAbsent(e.getUUID(), id -> new HashSet<>()).add(label);
                }
            }
        }
        return slots.isEmpty() && entitySlots.isEmpty() ? Report.NONE : new Report(Set.of(), slots, entitySlots);
    }

    private static boolean dataChanged(BlockChange c) {
        try {
            return !c.getBeforeData().equals(c.getAfterData());
        } catch (IllegalStateException gone) {
            return false;
        }
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
        List<String> out = new ArrayList<>();
        for (Change c : changes(before, after, false, step)) {
            if (step.seen().stream().noneMatch(s -> !s.cancelled() && c.explains().test(s))) {
                out.add(c.text() + explainUnseen(c, step.seen()));
            }
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
            return here.get(0) + ", but it happened anyway: the cancel left this behind. Whatever fires that event "
                    + "must make no change when it comes back cancelled";
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
     * {@code refused}: for a step protection should have refused, so also dropped items, orbs and falling blocks, and
     * every test player wherever they stand; otherwise only the players standing in the box at either end.
     */
    private static List<Change> changes(Snapshot before, Snapshot after, boolean refused, Step step) {
        List<Change> out = new ArrayList<>();
        blockChanges(before, after, step, out);
        inventoryChanges(before, after, refused, step, out);
        entityChanges(before, after, refused, step, out);
        playerChanges(before, after, refused, out);
        return out;
    }

    private static void blockChanges(Snapshot before, Snapshot after, Step step, List<Change> out) {
        for (Map.Entry<BlockPos, BlockState> e : before.blocks().entrySet()) {
            BlockPos pos = e.getKey();
            BlockState now = after.blocks().get(pos);
            String beBefore = before.blockEntities().get(pos), beAfter = after.blockEntities().get(pos);
            String what;
            if (!Objects.equals(e.getValue(), now)) what = at(pos) + " went from " + e.getValue() + " to " + now;
            else if (!Objects.equals(beBefore, beAfter)) {
                what = at(pos) + "'s block entity went from " + beBefore + " to " + beAfter;
            }
            else continue;
            out.add(new Change(what, s -> step.of(s).blocks().contains(pos)));
        }
    }

    private static void inventoryChanges(Snapshot before, Snapshot after, boolean refused, Step step,
            List<Change> out) {
        Set<BlockPos> containers = new LinkedHashSet<>(before.contents().keySet());
        containers.addAll(after.contents().keySet());
        for (BlockPos pos : containers) {
            Map<String, ItemStack> was = before.contents().getOrDefault(pos, Map.of());
            Map<String, ItemStack> is = after.contents().getOrDefault(pos, Map.of());
            // A block's own report covers its inventory only when the block itself went or changed (a chest broken);
            // otherwise a machine could report one state change and then move items unreported.
            boolean blockChanged = !Objects.equals(before.blocks().get(pos), after.blocks().get(pos))
                    || !Objects.equals(before.blockEntities().get(pos), after.blockEntities().get(pos));
            Set<String> labels = new LinkedHashSet<>(was.keySet());
            labels.addAll(is.keySet());
            for (String label : labels) {
                ItemStack a = was.getOrDefault(label, ItemStack.EMPTY), b = is.getOrDefault(label, ItemStack.EMPTY);
                if (ItemStack.matches(a, b)) continue;
                // A face's separate store has no slot numbers an event could name, so any slot reported at the block
                // covers it in an allowed step; a refused step lists it whatever happened.
                boolean faceStore = !label.startsWith("slot ");
                out.add(new Change("the inventory at " + at(pos) + ", " + label + ": " + a + " -> " + b, s -> {
                    Report r = step.of(s);
                    if (blockChanged && r.blocks().contains(pos)) return true;
                    Set<String> named = r.slots().getOrDefault(pos, Set.of());
                    return named.contains(label) || named.contains(ANY_SLOT)
                            || faceStore && !refused && !named.isEmpty();
                }));
            }
        }
    }

    private static void entityChanges(Snapshot before, Snapshot after, boolean refused, Step step, List<Change> out) {
        Set<UUID> crossing = step.crossing();
        Set<UUID> ids = new LinkedHashSet<>(before.entities().keySet());
        ids.addAll(after.entities().keySet());
        for (UUID id : ids) {
            Thing was = before.entities().get(id), is = after.entities().get(id);
            String what;
            Predicate<Seen> explains;
            if (was == null && is != null) {
                if (is.consequence() && !refused || before.world().contains(id) || crossing.contains(id)) continue;
                what = "a " + is.type() + describe(is) + " appeared at " + at(is.pos());
                Thing born = is;
                explains = s -> s.event() instanceof EntitySpawnEvent e && e.getEntity().getUUID().equals(id)
                        || s.event() instanceof EntityTransformEvent x && born.type().equals(
                                String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(x.getTransformedType())))
                                && x.getEntity().blockPosition().closerThan(born.pos(), 2);
            } else if (was != null && is == null) {
                if (was.consequence() && !refused || after.world().contains(id) || crossing.contains(id)) continue;
                what = "the " + was.type() + describe(was) + " at " + at(was.pos()) + " left";
                explains = s -> names(s, id) && (s.event() instanceof EntityRemoveEvent
                        || s.event() instanceof EntityDeathEvent || s.event() instanceof HangingBreakEvent
                        || s.event() instanceof EntityTransformEvent);
            } else if (was != null && is != null && !ItemStack.matches(was.stack(), is.stack())) {
                if (is.consequence() && !refused) continue;
                what = "the " + is.type() + " at " + at(is.pos()) + " went from " + was.stack() + " to " + is.stack();
                explains = s -> names(s, id);
            } else if (was != null && is != null && !labelsDiffering(was.contents(), is.contents()).isEmpty()) {
                for (String label : labelsDiffering(was.contents(), is.contents())) {
                    out.add(new Change("the " + is.type() + " at " + at(is.pos()) + ", " + label + ": "
                            + was.contents().getOrDefault(label, ItemStack.EMPTY) + " -> "
                            + is.contents().getOrDefault(label, ItemStack.EMPTY), s -> {
                                Set<String> named = step.of(s).entitySlots().getOrDefault(id, Set.of());
                                return named.contains(label) || named.contains(ANY_SLOT);
                            }));
                }
                continue;
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
    }

    private static void playerChanges(Snapshot before, Snapshot after, boolean refused, List<Change> out) {
        Predicate<String> counted = name -> refused || before.playersInBox().contains(name)
                || after.playersInBox().contains(name);
        for (Map.Entry<String, List<ItemStack>> e : before.players().entrySet()) {
            String name = e.getKey();
            List<ItemStack> now = after.players().get(name);
            if (now == null || !counted.test(name)) continue;
            String diff = stacks(e.getValue(), now);
            // By something the player did that spends or gains items (a tool wearing on a break, a block placed, an
            // item used or picked up, a click in a menu), or a change to their inventory: not a use or an open.
            if (diff != null) {
                out.add(new Change(name + "'s inventory " + diff, s -> isPlayer(s.actor(), name)
                        && (s.event() instanceof ItemEvent || s.event() instanceof InventoryChangeEvent
                                || s.event() instanceof InventoryClickEvent
                                || s.event() instanceof BlockEvent b
                                        && b.getBlocks().stream().anyMatch(BlockChange::isChanged)
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
    }

    private static List<String> labelsDiffering(Map<String, ItemStack> a, Map<String, ItemStack> b) {
        Set<String> labels = new LinkedHashSet<>(a.keySet());
        labels.addAll(b.keySet());
        List<String> out = new ArrayList<>();
        for (String l : labels) {
            if (!ItemStack.matches(a.getOrDefault(l, ItemStack.EMPTY), b.getOrDefault(l, ItemStack.EMPTY))) out.add(l);
        }
        return out;
    }

    private static boolean isPlayer(Actor actor, String name) {
        return actor instanceof Actor.Player p && p.player().getGameProfile().getName().equals(name);
    }

    private static boolean names(Seen s, UUID id) {
        return s.event() instanceof EntityEvent e && e.getEntity().getUUID().equals(id);
    }

    static Optional<BlockPos> blockOf(InventoryHolder holder) {
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
