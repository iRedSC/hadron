package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityDeathEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jspecify.annotations.Nullable;

/**
 * The acceptance kit over one box: a protection listener first, a logger last, and snapshots of the box to compare.
 * Deciding what each change means is {@link AcceptanceDiff}'s; this class only reads Minecraft and listens.
 */
final class TestAcceptance implements Acceptance, Listener {
    /** An entity's own motion and attention: not something a region forbids, and nobody's doing but its own. */
    private static final Set<Class<? extends Event>> MOTION = Set.of(EntityMoveEvent.class, EntityTeleportEvent.class,
            EntityVelocityEvent.class, EntityKnockbackEvent.class, EntityTargetEvent.class, EntityAirChangeEvent.class);

    private final TestContext t;
    private final ServerLevel level;
    private final BoundingBox box;
    private final Set<UUID> trusted = new HashSet<>();
    private final List<Seen> seen = new ArrayList<>();
    /** Entities born or gone outside the box this step: one in the box at only one end crossed its edge. */
    private final Set<UUID> crossing = new HashSet<>();
    /** What each heard event said it changes, copied as it fired. */
    private final Map<Event, AcceptanceDiff.Report> reports = new IdentityHashMap<>();
    /** Inventory events whose holder is Unknown: nothing can place them, so a failure says to name the holder. */
    private final List<Event> unplaced = new ArrayList<>();
    private AcceptanceDiff.Snapshot before;

    TestAcceptance(TestContext t, BoundingBox box) {
        this.t = t;
        this.level = t.level();
        this.box = Objects.requireNonNull(box, "box");
        Areas.watch(t.plugin(), level, box);
        t.registerEvents(this);
        this.before = snapshot();
    }

    @Override
    public Acceptance trust(TestPlayer... players) {
        for (TestPlayer p : players) trusted.add(p.uuid());
        return this;
    }

    @Override
    public Acceptance trustNobody() {
        trusted.clear();
        return this;
    }

    @Override
    public void mark() {
        before = snapshot();
        seen.clear();
        crossing.clear();
        reports.clear();
        unplaced.clear();
    }

    @Override
    public List<Seen> events() {
        return List.copyOf(seen);
    }

    /** The area now, refusing a step whose players changed: one who joined has nothing to be compared against. */
    private AcceptanceDiff.Snapshot after(String step) {
        AcceptanceDiff.Snapshot now = snapshot();
        if (!now.players().keySet().equals(before.players().keySet())) {
            throw new AssertionError(step + ": the test's players changed since the last mark (" + before.players()
                    .keySet() + " then, " + now.players().keySet() + " now), so their inventories and health have "
                    + "nothing to be compared against. Join every player before calling mark().");
        }
        return now;
    }

    @Override
    public void assertUnchanged(String step) {
        List<String> problems = AcceptanceDiff.unchanged(before, after(step), step());
        if (!problems.isEmpty()) {
            throw new AssertionError(step + " should have changed nothing in the protected area, but "
                    + problems.size() + " thing(s) changed:\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void assertEveryChangeSeen(String step) {
        AcceptanceDiff.Snapshot after = after(step);
        if (!AcceptanceDiff.anyChange(before, after, step())) {
            throw new AssertionError(step + ": nothing in the area changed since the last mark, so this proves "
                    + "nothing. Check the machine ran (power, fuel, an owner, enough ticks), or use assertUnchanged "
                    + "for a step that should change nothing.");
        }
        List<String> problems = AcceptanceDiff.unseen(before, after, step());
        if (!problems.isEmpty()) {
            throw new AssertionError(step + ": " + problems.size() + " change(s) in the area fired no event a plugin "
                    + "could act on. Each is an interception gap: the bridge fires the core event before the change "
                    + "(docs/bridge-hooks.md).\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void assertActingFor(TestPlayer player, String step) {
        List<String> problems = AcceptanceDiff.notActingFor(player.uuid(), player.name(), seen);
        if (!problems.isEmpty()) {
            throw new AssertionError(step + ": " + problems.size() + " event(s) in the area don't act for "
                    + player.name() + ":\n  " + String.join("\n  ", problems));
        }
        if (seen.stream().allMatch(s -> s.actor() instanceof Actor.Server || s.actor() instanceof Actor.Nature)) {
            throw new AssertionError(step + ": no event in the area acts for anyone (any there are the server's or "
                    + "nature's), so nothing shows " + player.name() + " acted. A machine " + player.name()
                    + " owns should act for them; if the step changed something, an event is missing.");
        }
    }

    private AcceptanceDiff.Step step() {
        return new AcceptanceDiff.Step(seen, reports, crossing, unplaced);
    }

    // ---- Listening ------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    void protect(Event event) {
        if (!(event instanceof Cancellable c) || !(event instanceof ActionEvent a) || where(event).isEmpty()) return;
        if (!allowed(a.getActor())) c.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void record(Event event) {
        if (!(event instanceof ActionEvent a)) return;
        Optional<Where> at = where(event);
        if (at.isEmpty()) {
            if (event instanceof InventoryEvent i && AcceptanceDiff.holders(i).stream()
                    .anyMatch(h -> h instanceof InventoryHolder.Unknown)) {
                unplaced.add(event);
            }
            boolean bornOrGone = event instanceof EntitySpawnEvent || event instanceof EntityRemoveEvent
                    || event instanceof EntityDeathEvent;
            if (bornOrGone && !(event instanceof Cancellable c && c.isCancelled())) {
                crossing.add(((EntityEvent) event).getEntity().getUUID());
            }
            return;
        }
        reports.put(event, AcceptanceDiff.report(event, level));
        seen.add(new Seen(event, a.getActor(), event instanceof Cancellable c && c.isCancelled(), at.get().pos()));
    }

    private boolean allowed(Actor actor) {
        if (actor instanceof Actor.Server) return true;
        Optional<UUID> who = actor.actingFor();
        return who.isPresent() && trusted.contains(who.get());
    }

    /** Where in the box an event happens, when it does; empty for events outside it or of no place. */
    private record Where(Optional<BlockPos> pos) {}

    private Optional<Where> where(Event event) {
        // Subclasses too: a player's move is a PlayerMoveEvent.
        for (Class<? extends Event> motion : MOTION) if (motion.isInstance(event)) return Optional.empty();
        if (event instanceof BlockEvent b) {
            if (b.getLevel() != level) return Optional.empty();
            if (box.isInside(b.getPos())) return Optional.of(new Where(Optional.of(b.getPos())));
            for (BlockChange change : b.getBlocks()) {
                if (box.isInside(change.getPos())) return Optional.of(new Where(Optional.of(change.getPos())));
            }
            return Optional.empty();
        }
        if (event instanceof EntityEvent e) return at(e.getLevel(), e.getPosition());
        if (event instanceof ExplosionEvent x) {
            // Its blocks too: a blast just outside the box still breaks blocks in it.
            if (x.getLevel() != level) return Optional.empty();
            Optional<Where> centre = at(level, x.getLocation());
            if (centre.isPresent()) return centre;
            for (BlockPos p : x.blockList()) if (box.isInside(p)) return inBox(p);
            return Optional.empty();
        }
        if (event instanceof ItemPickupEvent pickup && pickup.getLevel() == level) {
            // Where the item lands, or where it lay.
            Optional<Where> into = holderAt(pickup.getDestination());
            if (into.isPresent()) return into;
            return at(level, pickup.getItemEntity().position());
        }
        if (event instanceof ItemEvent i) return i.getLevel() == level ? actorAt(i.getActor()) : Optional.empty();
        if (event instanceof InventoryClickEvent click) {
            // A click in a menu showing a block in the box, whichever slot was clicked: a shift-click from the
            // player's own inventory fills the block's.
            for (BlockPos p : AcceptanceDiff.report(click, level).slots().keySet()) if (box.isInside(p)) return inBox(p);
        }
        if (event instanceof InventoryEvent i) {
            for (InventoryHolder holder : AcceptanceDiff.holders(i)) {
                Optional<Where> w = holderAt(holder);
                if (w.isPresent()) return w;
            }
            return Optional.empty();
        }
        if (event instanceof ResourceEvent r) {
            for (ResourceHolder holder : r.getHolders()) {
                Optional<Where> w = switch (holder) {
                    case ResourceHolder.Block b -> b.level() == level ? inBox(b.pos()) : Optional.empty();
                    case ResourceHolder.Entity e -> e.entity().level() instanceof ServerLevel l
                            ? at(l, e.entity().position()) : Optional.empty();
                    default -> Optional.empty();
                };
                if (w.isPresent()) return w;
            }
        }
        return Optional.empty();
    }

    /** An item event has no place of its own: it happens where whoever did it is. */
    private Optional<Where> actorAt(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> at(p.player().serverLevel(), p.player().position());
            case Actor.FakePlayer f -> at(f.fake().serverLevel(), f.fake().position());
            case Actor.Machine m -> m.level() == level ? inBox(m.pos()) : Optional.empty();
            case Actor.EntityActor e -> e.entity().level() instanceof ServerLevel l
                    ? at(l, e.entity().position()) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<Where> holderAt(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Block b -> b.level() == level ? inBox(b.pos()) : Optional.empty();
            case InventoryHolder.Entity e -> e.entity().level() instanceof ServerLevel l
                    ? at(l, e.entity().position()) : Optional.empty();
            case InventoryHolder.Item i -> holderAt(i.outer());
            case InventoryHolder.PerPlayer p -> holderAt(p.at());
            default -> Optional.empty();
        };
    }

    private Optional<Where> at(ServerLevel at, Vec3 pos) {
        return at == level ? inBox(BlockPos.containing(pos)) : Optional.empty();
    }

    private Optional<Where> inBox(BlockPos pos) {
        return box.isInside(pos) ? Optional.of(new Where(Optional.of(pos.immutable()))) : Optional.empty();
    }

    // ---- Recording the box ----------------------------------------------------------------------------------------

    private AcceptanceDiff.Snapshot snapshot() {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        Map<BlockPos, String> blockEntities = new LinkedHashMap<>();
        Map<BlockPos, Map<String, ItemStack>> contents = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())) {
            BlockPos pos = p.immutable();
            blocks.put(pos, level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                blockEntities.put(pos, String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            }
            Map<String, ItemStack> stacks = items(pos, be);
            if (!stacks.isEmpty()) contents.put(pos, stacks);
        }
        Map<UUID, AcceptanceDiff.Thing> entities = new LinkedHashMap<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof ServerPlayer) continue;
            ItemStack stack = e instanceof ItemEntity item ? item.getItem().copy() : ItemStack.EMPTY;
            Map<String, ItemStack> held = new LinkedHashMap<>();
            if (e instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) held.put("slot " + i, c.getItem(i).copy());
            }
            boolean consequence = e instanceof ItemEntity || e instanceof ExperienceOrb
                    || e instanceof FallingBlockEntity;
            float health = e instanceof LivingEntity living ? living.getHealth() : -1;
            entities.put(e.getUUID(), new AcceptanceDiff.Thing(e.getUUID(),
                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), stack, health, held,
                    // By the block it stands in, as where() places its events.
                    e.blockPosition(), box.isInside(e.blockPosition()), consequence));
        }
        Map<String, List<ItemStack>> players = new LinkedHashMap<>();
        Map<String, Float> health = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        Set<String> inBox = new HashSet<>();
        for (TestPlayer tp : t.players()) {
            Inventory inv = tp.player().getInventory();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) stacks.add(inv.getItem(i).copy());
            stacks.add(tp.player().containerMenu.getCarried().copy());
            players.put(tp.name(), stacks);
            if (box.isInside(tp.player().blockPosition())) inBox.add(tp.name());
            health.put(tp.name(), tp.player().getHealth());
            ids.put(tp.name(), tp.uuid());
        }
        return new AcceptanceDiff.Snapshot(blocks, blockEntities, contents, entities, ids, players, health, inBox);
    }

    /**
     * Every slot a block exposes, labelled so an event can name it. {@code slot 3}: the block's own inventory, a
     * container block entity's slots or else its sideless item handler's. Then every other handler it offers (the
     * sideless one of a container, each face's), as {@code unsided slot 3} or {@code north slot 3}: they may be views
     * of its own slots or separate stores, which only a refused step checks. Empty when it has none.
     */
    private Map<String, ItemStack> items(BlockPos pos, @Nullable BlockEntity be) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        List<IItemHandler> handlers = new ArrayList<>();
        @Nullable IItemHandler sideless = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (be instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) out.put("slot " + i, c.getItem(i).copy());
            if (sideless != null) {
                for (int i = 0; i < sideless.getSlots(); i++) {
                    out.put("unsided slot " + i, sideless.getStackInSlot(i).copy());
                }
            }
        } else if (sideless != null) {
            for (int i = 0; i < sideless.getSlots(); i++) out.put("slot " + i, sideless.getStackInSlot(i).copy());
        }
        if (sideless != null) handlers.add(sideless);
        for (Direction side : Direction.values()) {
            @Nullable IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (h == null || handlers.stream().anyMatch(seen -> seen == h)) continue;
            handlers.add(h);
            for (int i = 0; i < h.getSlots(); i++) out.put(side.getName() + " slot " + i, h.getStackInSlot(i).copy());
        }
        return out;
    }
}
