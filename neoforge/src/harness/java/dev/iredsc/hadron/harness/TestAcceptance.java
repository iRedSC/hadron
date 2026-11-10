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
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.item.EquipmentChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemConsumeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemDespawnEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.item.PrepareAnvilEvent;
import dev.iredsc.hadron.api.event.item.PrepareItemCraftEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.api.event.world.WorldEvent;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.harness.mixin.ServerLevelEntitiesAccessor;
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
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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

    /** World events about the whole world, never an area: not the box's to refuse. */
    private static final Set<Class<? extends Event>> WHOLE_LEVEL = Set.of(WeatherChangeEvent.class,
            TimeSkipEvent.class);

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
    private final Set<Class<? extends Event>> refused = new HashSet<>();
    private final List<Seen> seen = new ArrayList<>();
    /** Events that couldn't be placed, with why: each fails assertActingFor. */
    private final List<Unplaced> unplaced = new ArrayList<>();
    /** Entities an event in the box named this step: compared even if they only passed through. */
    private final Set<UUID> touched = new HashSet<>();
    /** Events about compared entities or players that were placed outside the area: for failure messages only. */
    private final List<Seen> elsewhere = new ArrayList<>();
    /**
     * Entities that joined the world outside the box this step: one of them in the box at the end walked, rolled or
     * flowed in, which is motion, not something appearing there.
     */
    private final Set<UUID> joinedOutside = new HashSet<>();
    private final java.util.function.Consumer<EntityJoinLevelEvent> joins = this::joined;

    private void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() == level && !box.isInside(event.getEntity().blockPosition())) {
            joinedOutside.add(event.getEntity().getUUID());
        }
    }

    /** Stops hearing the loader when the test ends. */
    void close() {
        NeoForge.EVENT_BUS.unregister(joins);
    }

    /** Events the kit itself refused, so another listener's cancel never shows the machine tried. */
    private final Set<Event> denied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** An event that couldn't be placed, as it stood after every listener ran, and what to fix. */
    private record Unplaced(Seen seen, String why) {
        @Override
        public String toString() {
            return seen.event().getEventName() + ": " + why;
        }
    }
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
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, joins);
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
    public Acceptance refuse(Class<? extends Event> kind) {
        Objects.requireNonNull(kind, "kind");
        // Only what protection can deny: an action, not motion, and, for a single kind, one that can be cancelled.
        boolean action = ActionEvent.class.isAssignableFrom(kind);
        boolean concrete = !java.lang.reflect.Modifier.isAbstract(kind.getModifiers());
        boolean motion = MOTION.stream().anyMatch(m -> m.isAssignableFrom(kind));
        boolean wholeLevel = WHOLE_LEVEL.stream().anyMatch(w -> w.isAssignableFrom(kind));
        if (!action || motion || wholeLevel || concrete && !Cancellable.class.isAssignableFrom(kind)) {
            throw new IllegalArgumentException(kind.getSimpleName() + " can't be refused in an acceptance area: refuse "
                    + "takes a kind of action protection can cancel, such as BlockEvent, InventoryEvent, ItemEvent, "
                    + "ResourceEvent or EntityDamageEvent. Player events, motion, after-the-fact events and events "
                    + "about a whole world (weather, time) can't be.");
        }
        refused.add(kind);
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
        touched.clear();
        elsewhere.clear();
        denied.clear();
        joinedOutside.clear();
    }

    @Override
    public List<Seen> events() {
        List<Seen> all = new ArrayList<>(seen);
        for (Unplaced u : unplaced) all.add(u.seen());
        return List.copyOf(all);
    }

    /** Refuses a step whose players changed since the mark: one who joined has nothing to be compared against. */
    private void requireSamePlayers(String step, AcceptanceDiff.Snapshot after) {
        if (!after.players().keySet().equals(before.players().keySet())) {
            throw new AssertionError(step + ": the test's players changed since the last mark (" + before.players()
                    .keySet() + " then, " + after.players().keySet() + " now), so their inventories and health have "
                    + "nothing to be compared against. Join every player before calling mark().");
        }
    }

    @Override
    public void assertRefused(TestPlayer player, String step) {
        java.util.function.Predicate<Seen> refusedFor = s -> s.cancelled() && denied.contains(s.event())
                && s.actor().actingFor().filter(player.uuid()::equals).isPresent();
        boolean tried = seen.stream().anyMatch(refusedFor)
                || unplaced.stream().map(Unplaced::seen).anyMatch(refusedFor);
        if (!tried) {
            throw new AssertionError(step + ": nothing acting for " + player.name() + " was refused since the last "
                    + "mark, so this step proves nothing about interception. Is the machine running (power, fuel, "
                    + "range, an owner, enough ticks)? Heard: " + (seen.isEmpty() ? "no events" : seen)
                    + (unplaced.isEmpty() ? "" : "; and, nowhere it could tell, " + unplaced));
        }
    }

    @Override
    public void assertUnchanged(String step, Part... parts) {
        AcceptanceDiff.Snapshot after = snapshot();
        requireSamePlayers(step, after);
        Set<Part> which = parts.length == 0 ? EnumSet.allOf(Part.class) : EnumSet.copyOf(List.of(parts));
        List<String> problems = AcceptanceDiff.changes(before, after, which, seen, elsewhere, touched,
                joinedOutside);
        if (!problems.isEmpty()) {
            throw new AssertionError(step + " should have changed nothing in " + which + ", but " + problems.size()
                    + " thing(s) changed:\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void assertActingFor(TestPlayer player, String step) {
        requireSamePlayers(step, snapshot());
        // Protection fails closed on nature's unplaceable events too, but they are nobody's to attribute.
        List<String> problems = new ArrayList<>(unplaced.stream().filter(u -> !AcceptanceDiff.nobodys(u.seen().actor()))
                .map(Unplaced::toString).toList());
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
            if (!(a.getActor() instanceof Actor.Server)) {
                c.setCancelled(true);
                denied.add(event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void record(Event event) {
        if (!(event instanceof ActionEvent a)) return;
        switch (place(event)) {
            case Place.In in -> {
                seen.add(new Seen(event, a.getActor(), event instanceof Cancellable c && c.isCancelled(), in.pos()));
                for (Entity e : named(event)) {
                    touched.add(e.getUUID());
                    followed.putIfAbsent(e.getUUID(), e);
                }
            }
            case Place.Unplaced u -> {
                if (!(a.getActor() instanceof Actor.Server)) {
                    unplaced.add(new Unplaced(new Seen(event, a.getActor(), event instanceof Cancellable c
                            && c.isCancelled(), Optional.empty()), u.why()));
                }
            }
            case Place.Out o -> {
                // Kept for failure messages only: an event about an entity being compared, placed outside the area.
                boolean motion = MOTION.stream().anyMatch(m -> m.isInstance(event));
                if (!motion && event instanceof EntityEvent ee && (followed.containsKey(ee.getEntity().getUUID())
                        || t.players().stream().anyMatch(p -> p.uuid().equals(ee.getEntity().getUUID())))) {
                    elsewhere.add(new Seen(event, a.getActor(), event instanceof Cancellable c && c.isCancelled(),
                            Optional.empty()));
                }
            }
        }
    }

    private boolean allowed(Actor actor, Event event) {
        if (actor instanceof Actor.Server) return true;
        Optional<UUID> who = actor.actingFor();
        if (who.isEmpty() || !trusted.contains(who.get())) return false;
        for (Class<? extends Event> kind : refused) if (kind.isInstance(event)) return false;
        return true;
    }

    private Place place(Event event) {
        // Subclasses too: a player's move is a PlayerMoveEvent.
        for (Class<? extends Event> motion : MOTION) if (motion.isInstance(event)) return OUT;
        if (event instanceof BlockEvent b) {
            if (b.getLevel() != level) return OUT;
            if (box.isInside(b.getPos())) return new Place.In(Optional.of(b.getPos()));
            for (BlockChange change : b.getBlocks()) if (box.isInside(change.getPos())) return in(change.getPos());
            if (b instanceof BlockMoveEvent move) {
                // A structure sweeping through: where its blocks come from, go to, or pass on the way.
                for (BlockMoveEvent.Move m : move.getMoves()) {
                    if (box.isInside(m.from())) return in(m.from());
                    if (box.isInside(m.to())) return in(m.to());
                }
                for (BlockPos swept : move.getSweptSpace()) if (box.isInside(swept)) return in(swept);
            }
            return OUT;
        }
        if (event instanceof ProjectileHitEvent hit && hit.getLevel() == level) {
            // Where it lands, as well as the shooter's place: a hitscan shot names its shooter as the entity.
            Place lands = at(level, hit.getHitPosition());
            if (lands instanceof Place.In) return lands;
            Optional<BlockPos> block = hit.getHitBlock();
            if (block.isPresent() && box.isInside(block.get())) return in(block.get());
        }
        if (event instanceof EntityEvent e) {
            // Either of a pair: a rider boarding a vehicle in the area, a lead tied to a knot in it.
            for (Entity named : named(event)) {
                if (named.level() == level && at(level, named.position()) instanceof Place.In p) return p;
            }
            return at(e.getLevel(), e.getPosition());
        }
        if (event instanceof WorldEvent w && !(event instanceof ExplosionEvent)) {
            // An area a world event names (an accelerator's field, a portal's frame), by whether it overlaps.
            return w.getLevel() == level && w.getArea().filter(box::intersects).isPresent()
                    ? new Place.In(Optional.empty()) : OUT;
        }
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
            if (into instanceof Place.In) return into;
            Place lay = at(level, pickup.getItemEntity().position());
            // An unknown destination might be in the area: fail closed unless the item lay in it anyway.
            return lay instanceof Place.In || !(into instanceof Place.Unplaced) ? lay
                    : unplacedHolder();
        }
        if (event instanceof ItemDropEvent drop) {
            // Where the item lands, or where whoever dropped it stands.
            if (drop.getLevel() != level) return OUT;
            Place lands = at(level, drop.getPosition());
            if (lands instanceof Place.In) return lands;
            // Known to land outside: the area's only if whoever dropped it stands in it.
            return actorAt(drop.getActor());
        }
        if (event instanceof ItemEvent i && !itemEntities(i).isEmpty()) {
            // A merge or a despawn happens where its items lie, either of a merge's two, whoever is behind it.
            if (i.getLevel() != level) return OUT;
            for (ItemEntity item : itemEntities(i)) {
                Place lies = at(level, item.position());
                if (lies instanceof Place.In) return lies;
            }
        }
        if (event instanceof ItemEvent i) {
            if (i.getLevel() != level) return OUT;
            // An item event naming the inventory it happens in (a craft, a smelt, a trade, an anvil) happens there.
            // An item used, eaten or equipped happens where the entity it is about stands.
            Optional<LivingEntity> about = itemUser(i);
            if (about.isPresent() && at(level, about.get().position()) instanceof Place.In p) return p;
            Optional<InventoryHolder> in = itemHolder(i);
            Place named = in.map(this::holder).orElse(OUT);
            if (named instanceof Place.In) return named;
            if (named instanceof Place.Unplaced) {
                Place by = actorAt(i.getActor());
                return by instanceof Place.In ? by : unplacedHolder();
            }
            // An inventory it names, known to be elsewhere: the area isn't where it happened, whoever is behind it.
            if (in.isPresent()) return OUT;
            return i.getActor() instanceof Actor.Unknown
                    ? new Place.Unplaced("nobody is behind it, so nothing says where it happened: push the actor "
                            + "(the player or machine using the item)")
                    : actorAt(i.getActor());
        }
        if (event instanceof InventoryClickEvent click) {
            Place on = menu(click.getMenu(), click.getViewer());
            if (on instanceof Place.In) return on;
        }
        if (event instanceof InventoryOpenEvent open && open.getMenu().isPresent()
                && menu(open.getMenu().get(), open.getViewer()) instanceof Place.In on) {
            return on;
        }
        if (event instanceof InventoryEvent i) {
            Set<InventoryHolder> holders = i instanceof InventoryChangeEvent c ? c.getHolders() : Set.of(i.getHolder());
            boolean unknown = false;
            for (InventoryHolder h : holders) {
                Place p = holder(h);
                if (p instanceof Place.In) return p;
                unknown |= p instanceof Place.Unplaced;
            }
            return unknown ? unplacedHolder() : OUT;
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
            return OUT;
        }
        // Each family above says where its events happen. Any other action (a bridge's own event type, a family added
        // later) might be in the area, so protection fails closed on it rather than letting it through unseen.
        return new Place.Unplaced("the acceptance kit doesn't know where a " + event.getEventName() + " happens: "
                + "make it a block, entity, item, inventory, resource or world event, whose place the kit reads");
    }

    /** Every entity an event names: its subject, and the other of a pair (a mount, a lead's holder), or its items. */
    private static List<Entity> named(Event event) {
        return switch (event) {
            case EntityMountEvent e -> List.of(e.getEntity(), e.getMount());
            case EntityLeashEvent e -> List.of(e.getEntity(), e.getHolder());
            case EntityEvent e -> List.of(e.getEntity());
            case ItemPickupEvent p -> holderEntity(p.getDestination()).map(d -> List.<Entity>of(p.getItemEntity(), d))
                    .orElse(List.of(p.getItemEntity()));
            case ItemEvent i -> List.copyOf(itemEntities(i));
            case InventoryChangeEvent c -> c.getHolders().stream().flatMap(h -> holderEntity(h).stream()).toList();
            case InventoryEvent i -> holderEntity(i.getHolder()).map(List::of).orElse(List.of());
            default -> List.of();
        };
    }

    /** The entity holding an inventory (a cart, a donkey, a player), through items and per-player copies. */
    private static Optional<Entity> holderEntity(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Entity e -> Optional.of(e.entity());
            case InventoryHolder.Item i -> holderEntity(i.outer());
            case InventoryHolder.PerPlayer p -> holderEntity(p.at());
            default -> Optional.empty();
        };
    }

    /** The item entities an item event is about, when it is about ones lying in the world. */
    private static List<ItemEntity> itemEntities(ItemEvent event) {
        return switch (event) {
            case ItemMergeEvent e -> List.of(e.getItemEntity(), e.getTarget());
            case ItemDespawnEvent e -> List.of(e.getItemEntity());
            default -> List.of();
        };
    }

    private static Place unplacedHolder() {
        return new Place.Unplaced("its holder is unknown, so nothing says whose inventory changed: name the holder "
                + "(InventoryHolder.Block or InventoryHolder.Entity)");
    }

    private static Optional<LivingEntity> itemUser(ItemEvent event) {
        return switch (event) {
            case EquipEvent e -> Optional.of(e.getEntity());
            case ItemUseEvent e -> Optional.of(e.getUser());
            case ItemConsumeEvent e -> Optional.of(e.getConsumer());
            case EquipmentChangeEvent e -> Optional.of(e.getEntity());
            default -> Optional.empty();
        };
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

    /**
     * A menu open on a block in the box, whichever slot a click hits: a shift-click from the player's own inventory
     * fills the block's. What the menu is open on, as core works it out (a machine's menu over its item handler),
     * then any slot over a block entity in the box, either half of a double chest included.
     */
    private Place menu(AbstractContainerMenu menu, Player viewer) {
        Place on = holder(InventoryHolders.ofMenu(menu, viewer));
        if (on instanceof Place.In) return on;
        Map<BlockPos, Container> containers = null;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof BlockEntity be && be.getLevel() == level && box.isInside(be.getBlockPos())) {
                return in(be.getBlockPos());
            }
            if (slot.container instanceof CompoundContainer both) {
                if (containers == null) containers = containersInBox();
                for (Map.Entry<BlockPos, Container> half : containers.entrySet()) {
                    if (both.contains(half.getValue())) return in(half.getKey());
                }
            }
        }
        return OUT;
    }

    private Map<BlockPos, Container> containersInBox() {
        Map<BlockPos, Container> out = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                box.maxZ())) {
            if (level.getBlockEntity(p) instanceof Container c) out.put(p.immutable(), c);
        }
        return out;
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
        for (Map.Entry<UUID, Entity> f : new ArrayList<>(followed.entrySet())) {
            Entity e = f.getValue();
            if (!e.isRemoved()) {
                // Only one its world holds: an entity whose spawn was refused was never added, though not removed.
                if (e.level() instanceof ServerLevel at
                        && ((ServerLevelEntitiesAccessor) at).hadron$entityManager().isLoaded(f.getKey())) {
                    present.putIfAbsent(f.getKey(), e);
                }
                continue;
            }
            // Gone from this instance: a live one with the same id anywhere (reloaded from its chunk, a copy in the
            // world it went to) is it now, and is followed from here on, so its own end is seen.
            Entity live = null;
            boolean held = false;
            for (ServerLevel other : level.getServer().getAllLevels()) {
                Entity copy = other.getEntity(f.getKey());
                if (copy != null && !copy.isRemoved()) live = copy;
                held |= ((ServerLevelEntitiesAccessor) other).hadron$entityManager().isLoaded(f.getKey());
            }
            if (live != null) {
                followed.put(f.getKey(), live);
                present.putIfAbsent(f.getKey(), live);
                continue;
            }
            // Saved with its chunk, or held but not yet listed by the world it went to: motion, so it counts as it
            // left. Anything else, or a change of worlds no world holds, is gone.
            Entity.RemovalReason why = e.getRemovalReason();
            if (why == Entity.RemovalReason.UNLOADED_TO_CHUNK || why == Entity.RemovalReason.UNLOADED_WITH_PLAYER
                    || why == Entity.RemovalReason.CHANGED_DIMENSION && held) {
                present.putIfAbsent(f.getKey(), e);
            }
        }
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
                    e.blockPosition(), e.level() == level && box.isInside(e.blockPosition())));
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
        // A chest's item handler is both halves of a double chest, so its own slots are the whole truth for it.
        if (be instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) out.put("slot " + i, chest.getItem(i).copy());
            return out;
        }
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
