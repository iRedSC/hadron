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
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.PrepareAnvilEvent;
import dev.iredsc.hadron.api.event.item.PrepareItemCraftEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.neoforge.InventoryHolders;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jspecify.annotations.Nullable;

/**
 * The acceptance kit over one box: a protection listener first, a logger last, and snapshots of the box to compare.
 * Comparing is {@link AcceptanceDiff}'s; this class reads Minecraft, places events and listens.
 */
final class TestAcceptance implements Acceptance, Listener {
    /** Motion and attention: not something a region forbids, so anyone may walk through. */
    private static final Set<Class<? extends Event>> MOTION = Set.of(EntityMoveEvent.class, EntityTeleportEvent.class,
            EntityVelocityEvent.class, EntityKnockbackEvent.class, EntityTargetEvent.class, EntityAirChangeEvent.class);

    /** Where an event happens, as far as the box goes. */
    private sealed interface Place {
        /** In the box, at {@code pos} when it has one. */
        record In(Optional<BlockPos> pos) implements Place {}

        /** Elsewhere, or not a kind the box is about. */
        record Out() implements Place {}

        /** Nowhere it can tell: {@code why} says what to fix. */
        record Unplaced(String why) implements Place {}
    }

    private static final Place OUT = new Place.Out();

    private final TestContext t;
    private final ServerLevel level;
    private final BoundingBox box;
    private final Set<UUID> trusted = new HashSet<>();
    private final Set<Class<?>> refused = new HashSet<>();
    private final List<Seen> seen = new ArrayList<>();
    /** Events that couldn't be placed, with why: each fails assertActingFor. */
    private final List<String> unplaced = new ArrayList<>();
    /**
     * Entities seen in the box at the last snapshot, by reference: one moved into a chunk whose entities aren't
     * listed yet still counts until it is removed.
     */
    private final Map<UUID, Entity> followed = new LinkedHashMap<>();
    private AcceptanceDiff.Snapshot before;

    TestAcceptance(TestContext t, BoundingBox box) {
        this.t = t;
        this.level = t.level();
        // A copy: a BoundingBox can be moved, and the watched, placed and compared area must stay one area.
        Objects.requireNonNull(box, "box");
        this.box = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        Areas.watch(t.plugin(), level, box);
        t.registerEvents(this);
        this.before = snapshot();
        follow(before);
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
    public Acceptance refuse(Class<?>... kinds) {
        for (Class<?> kind : kinds) {
            if (!Event.class.isAssignableFrom(kind)) {
                throw new IllegalArgumentException(kind.getName() + " isn't a Hadron event: refuse takes event classes "
                        + "such as BlockEvent.class or InventoryEvent.class");
            }
        }
        refused.addAll(List.of(kinds));
        return this;
    }

    @Override
    public Acceptance refuseNothing() {
        refused.clear();
        return this;
    }

    @Override
    public void mark() {
        before = snapshot();
        follow(before);
        seen.clear();
        unplaced.clear();
    }

    @Override
    public List<Seen> events() {
        return List.copyOf(seen);
    }

    @Override
    public void assertUnchanged(String step, Part... parts) {
        AcceptanceDiff.Snapshot after = snapshot();
        if (!after.players().keySet().equals(before.players().keySet())) {
            throw new AssertionError(step + ": the test's players changed since the last mark (" + before.players()
                    .keySet() + " then, " + after.players().keySet() + " now), so their inventories and health have "
                    + "nothing to be compared against. Join every player before calling mark().");
        }
        Set<Part> which = parts.length == 0 ? EnumSet.allOf(Part.class) : EnumSet.copyOf(List.of(parts));
        List<String> problems = AcceptanceDiff.changes(before, after, which, seen);
        if (!problems.isEmpty()) {
            throw new AssertionError(step + " should have changed nothing in " + which + ", but " + problems.size()
                    + " thing(s) changed:\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void assertActingFor(TestPlayer player, String step) {
        List<String> problems = new ArrayList<>(unplaced);
        for (Seen s : seen) {
            if (AcceptanceDiff.nobodys(s.actor())) continue;
            Optional<UUID> who = s.actor().actingFor();
            if (who.isPresent() && who.get().equals(player.uuid())) continue;
            if (s.actor() instanceof Actor.Unknown) {
                problems.add(s + ": Unknown, so nothing was on the actor stack. An attribution gap: push the actor "
                        + "around the work that did this (docs/bridge-hooks.md, \"Always: attribution\")");
            } else if (who.isEmpty()) {
                problems.add(s + ": nobody is responsible for it. An attribution gap: remember its owner with "
                        + "Actors.remember when it is placed, spawned or claimed");
            } else {
                problems.add(s + ": acts for " + who.get() + ", not " + player.name());
            }
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(step + ": " + problems.size() + " event(s) don't show " + player.name()
                    + " acting:\n  " + String.join("\n  ", problems));
        }
        if (seen.stream().allMatch(s -> AcceptanceDiff.nobodys(s.actor()))) {
            throw new AssertionError(step + ": no event in the area acts for anyone (any there are the server's or "
                    + "nature's), so nothing shows " + player.name() + " acted. A machine " + player.name()
                    + " owns should act for them; if the step changed something, an event is missing.");
        }
    }

    // ---- Listening ------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    void protect(Event event) {
        if (!(event instanceof Cancellable c) || !(event instanceof ActionEvent a)) return;
        Place place = place(event);
        if (place instanceof Place.Out) return;
        // An event that can't be placed might be in the box: fail closed, as protection should.
        if (!allowed(a.getActor(), event) || place instanceof Place.Unplaced) {
            if (!(a.getActor() instanceof Actor.Server)) c.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void record(Event event) {
        if (!(event instanceof ActionEvent a)) return;
        switch (place(event)) {
            case Place.In in -> seen.add(new Seen(event, a.getActor(), event instanceof Cancellable c
                    && c.isCancelled(), in.pos()));
            case Place.Unplaced u -> {
                if (!(a.getActor() instanceof Actor.Server)) unplaced.add(event.getEventName() + ": " + u.why());
            }
            case Place.Out o -> { }
        }
    }

    private boolean allowed(Actor actor, Event event) {
        if (actor instanceof Actor.Server) return true;
        Optional<UUID> who = actor.actingFor();
        if (who.isEmpty() || !trusted.contains(who.get())) return false;
        for (Class<?> kind : refused) if (kind.isInstance(event)) return false;
        return true;
    }

    private Place place(Event event) {
        // Subclasses too: a player's move is a PlayerMoveEvent.
        for (Class<? extends Event> motion : MOTION) if (motion.isInstance(event)) return OUT;
        if (event instanceof BlockEvent b) {
            if (b.getLevel() != level) return OUT;
            if (box.isInside(b.getPos())) return new Place.In(Optional.of(b.getPos()));
            for (BlockChange change : b.getBlocks()) if (box.isInside(change.getPos())) return in(change.getPos());
            return OUT;
        }
        if (event instanceof EntityEvent e) return at(e.getLevel(), e.getPosition());
        if (event instanceof ExplosionEvent x) {
            // Its blocks too: a blast just outside the box still breaks blocks in it.
            if (x.getLevel() != level) return OUT;
            Place centre = at(level, x.getLocation());
            if (centre instanceof Place.In) return centre;
            for (BlockPos p : x.blockList()) if (box.isInside(p)) return in(p);
            return OUT;
        }
        if (event instanceof ItemPickupEvent pickup) {
            if (pickup.getLevel() != level) return OUT;
            Place into = holder(pickup.getDestination());
            return into instanceof Place.In ? into : at(level, pickup.getItemEntity().position());
        }
        if (event instanceof ItemDropEvent drop) {
            // Where the item lands, or where whoever dropped it stands.
            if (drop.getLevel() != level) return OUT;
            Place lands = at(level, drop.getPosition());
            if (lands instanceof Place.In) return lands;
        }
        if (event instanceof ItemEvent i) {
            if (i.getLevel() != level) return OUT;
            // An item event naming the inventory it happens in (a craft, a smelt, a trade, an anvil) happens there.
            Optional<InventoryHolder> in = itemHolder(i);
            if (in.isPresent() && holder(in.get()) instanceof Place.In p) return p;
            return i.getActor() instanceof Actor.Unknown
                    ? new Place.Unplaced("nobody is behind it, so nothing says where it happened: push the actor "
                            + "(the player or machine using the item)")
                    : actorAt(i.getActor());
        }
        if (event instanceof InventoryClickEvent click) {
            // Whichever slot was clicked, a menu open on a block in the box can change it: a shift-click from the
            // player's own inventory fills the block's. What the menu is open on, as core works it out (a double
            // chest, a machine's menu over its item handler), then any slot over a block entity in the box.
            Place on = holder(InventoryHolders.ofMenu(click.getMenu(), click.getViewer()));
            if (on instanceof Place.In) return on;
            for (Slot slot : click.getMenu().slots) {
                if (slot.container instanceof BlockEntity be && be.getLevel() == level
                        && box.isInside(be.getBlockPos())) {
                    return in(be.getBlockPos());
                }
            }
        }
        if (event instanceof InventoryEvent i) {
            Set<InventoryHolder> holders = i instanceof InventoryChangeEvent c ? c.getHolders() : Set.of(i.getHolder());
            boolean unknown = false;
            for (InventoryHolder h : holders) {
                Place p = holder(h);
                if (p instanceof Place.In) return p;
                unknown |= p instanceof Place.Unplaced;
            }
            return unknown ? new Place.Unplaced("its holder is unknown, so nothing says whose inventory changed: name "
                    + "the holder (InventoryHolder.Block or InventoryHolder.Entity)") : OUT;
        }
        if (event instanceof ResourceEvent r) {
            boolean held = false;
            for (ResourceHolder h : r.getHolders()) {
                held |= h instanceof ResourceHolder.Item;
                Place p = switch (h) {
                    case ResourceHolder.Block b -> b.level() == level ? inBox(b.pos()) : OUT;
                    case ResourceHolder.Entity e -> e.entity().level() instanceof ServerLevel l
                            ? at(l, e.entity().position()) : OUT;
                    case ResourceHolder.Chunk c -> c.level() == level && overlaps(c.pos())
                            ? new Place.In(Optional.empty()) : OUT;
                    default -> OUT;
                };
                if (p instanceof Place.In) return p;
            }
            // A resource in an item (a tool's energy) is where whoever holds it is.
            if (held) {
                if (r.getActor() instanceof Actor.Unknown) {
                    return new Place.Unplaced("its resource is in an item and nobody is behind it, so nothing says "
                            + "where it happened: push the actor (the player or machine holding the item)");
                }
                return actorAt(r.getActor());
            }
        }
        return OUT;
    }

    private static Optional<InventoryHolder> itemHolder(ItemEvent event) {
        return switch (event) {
            case ItemChangeEvent e -> Optional.of(e.getHolder());
            case ItemCraftEvent e -> Optional.of(e.getHolder());
            case PrepareItemCraftEvent e -> Optional.of(e.getHolder());
            case PrepareAnvilEvent e -> Optional.of(e.getHolder());
            case ItemSmeltEvent e -> Optional.of(e.getHolder());
            case TradeEvent e -> Optional.of(e.getTrader());
            default -> Optional.empty();
        };
    }

    private boolean overlaps(ChunkPos chunk) {
        return chunk.getMinBlockX() <= box.maxX() && chunk.getMaxBlockX() >= box.minX()
                && chunk.getMinBlockZ() <= box.maxZ() && chunk.getMaxBlockZ() >= box.minZ();
    }

    /** An item event has no place of its own: it happens where whoever did it is. */
    private Place actorAt(Actor actor) {
        return switch (actor) {
            case Actor.Player p -> at(p.player().serverLevel(), p.player().position());
            case Actor.FakePlayer f -> at(f.fake().serverLevel(), f.fake().position());
            case Actor.Machine m -> m.level() == level ? inBox(m.pos()) : OUT;
            case Actor.EntityActor e -> e.entity().level() instanceof ServerLevel l
                    ? at(l, e.entity().position()) : OUT;
            default -> OUT;
        };
    }

    private Place holder(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Block b -> b.level() == level ? inBox(b.pos()) : OUT;
            case InventoryHolder.Entity e -> e.entity().level() instanceof ServerLevel l
                    ? at(l, e.entity().position()) : OUT;
            case InventoryHolder.Item i -> holder(i.outer());
            case InventoryHolder.PerPlayer p -> holder(p.at());
            case InventoryHolder.Unknown u -> new Place.Unplaced("unknown holder");
            default -> OUT;
        };
    }

    private Place at(ServerLevel at, Vec3 pos) {
        return at == level ? inBox(BlockPos.containing(pos)) : OUT;
    }

    private Place inBox(BlockPos pos) {
        return box.isInside(pos) ? in(pos) : OUT;
    }

    private static Place in(BlockPos pos) {
        return new Place.In(Optional.of(pos.immutable()));
    }

    // ---- Recording the box ----------------------------------------------------------------------------------------

    /** From a mark on, every entity in the box then is followed, however many times the step is asserted. */
    private void follow(AcceptanceDiff.Snapshot at) {
        followed.clear();
        for (AcceptanceDiff.Thing thing : at.entities().values()) {
            if (!thing.inBox()) continue;
            Entity e = level.getEntity(thing.id());
            if (e != null) followed.put(thing.id(), e);
        }
    }

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
        Map<UUID, Entity> present = new LinkedHashMap<>();
        for (Entity e : level.getAllEntities()) present.put(e.getUUID(), e);
        for (Entity e : followed.values()) if (!e.isRemoved()) present.putIfAbsent(e.getUUID(), e);
        for (Entity e : present.values()) {
            if (e instanceof ServerPlayer) continue;
            ItemStack stack = e instanceof ItemEntity item ? item.getItem().copy() : ItemStack.EMPTY;
            Map<String, ItemStack> held = new LinkedHashMap<>();
            if (e instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) held.put("slot " + i, c.getItem(i).copy());
            }
            float health = e instanceof LivingEntity living ? living.getHealth() : -1;
            entities.put(e.getUUID(), new AcceptanceDiff.Thing(e.getUUID(),
                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), stack, health, held,
                    // By the block it stands in, as place() puts its events.
                    e.blockPosition(), box.isInside(e.blockPosition())));
        }
        Map<String, List<ItemStack>> players = new LinkedHashMap<>();
        Map<String, Float> health = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (TestPlayer tp : t.players()) {
            Inventory inv = tp.player().getInventory();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) stacks.add(inv.getItem(i).copy());
            stacks.add(tp.player().containerMenu.getCarried().copy());
            players.put(tp.name(), stacks);
            health.put(tp.name(), tp.player().getHealth());
            ids.put(tp.name(), tp.uuid());
        }
        return new AcceptanceDiff.Snapshot(blocks, blockEntities, contents, entities, ids, players, health);
    }

    /**
     * Every slot a block exposes: {@code slot 3} for its own inventory (a container block entity's slots, or else
     * its sideless item handler's), then every other handler it offers (a container's sideless one, each face's), as
     * {@code unsided slot 3} or {@code north slot 3}, since any of them may be a separate store. Empty when it has
     * none.
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
