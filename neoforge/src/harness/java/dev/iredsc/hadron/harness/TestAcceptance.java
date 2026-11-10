package dev.iredsc.hadron.harness;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.harness.mixin.ServerLevelEntitiesAccessor;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.SideEffects;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
    private sealed interface Where {
        /** In the box, at {@code pos} when it is one block. */
        record In(Optional<BlockPos> pos) implements Where {}

        /** Elsewhere, or not a kind the box is about. */
        record Out() implements Where {}

        /** Possibly in the box, and nothing can tell: {@code why} says what to fix. */
        record Unplaced(String why) implements Where {}
    }

    private static final Where OUT = new Where.Out();

    private final TestContext t;
    private final ServerLevel level;
    private final BoundingBox box;
    private final Set<UUID> trusted = new HashSet<>();
    private final Set<Class<? extends Event>> refused = new HashSet<>();
    private final List<Seen> seen = new ArrayList<>();
    /** Events that couldn't be placed, with why: each fails assertActingFor. */
    private final List<Unplaced> unplaced = new ArrayList<>();
    /**
     * Entities an event in the box named this step, or any event named while they stood in it: compared even if they
     * only passed through.
     */
    private final Set<UUID> touched = new HashSet<>();
    /** Events about compared entities or players that were placed outside the area: for failure messages only. */
    private final List<Seen> elsewhere = new ArrayList<>();
    /**
     * How each entity that wasn't anywhere at the mark first joined a world this step, never overwritten: as it stood,
     * when it joined outside the box (one in the box at the end walked, rolled or flowed in, which is motion) or was
     * loaded from disk, or empty when it was born inside (it appeared there). A later rejoin (a portal, a reload)
     * keeps the first.
     */
    private final Map<UUID, Optional<AcceptanceDiff.Thing>> arrived = new LinkedHashMap<>();
    private final java.util.function.Consumer<EntityJoinLevelEvent> joins = this::joined;

    private void joined(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (e instanceof ServerPlayer) return;
        // Its newest instance (born, reloaded from its chunk, a copy through a portal) is the one followed, so where
        // it ends is seen even if that is inside the box and nothing reported it.
        followed.put(e.getUUID(), e);
        if (before.entities().containsKey(e.getUUID())) return;
        AcceptanceDiff.Thing now = thing(e);
        // One loaded from disk inside the box was already there, not born there: it is compared with how it loaded.
        arrived.putIfAbsent(e.getUUID(), now.inBox() && !event.loadedFromDisk() ? Optional.empty() : Optional.of(now));
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
     * Every entity seen at the last mark or born since, by reference: one moved into a chunk whose entities aren't
     * listed yet still counts until it is removed, and one removed is seen where it went.
     */
    private final Map<UUID, Entity> followed = new LinkedHashMap<>();
    private AcceptanceDiff.Snapshot before;

    /** Game events, so a refused step that still made a sculk sensor hear something fails. */
    private final TestSideEffects heard;

    TestAcceptance(TestContext t, BoundingBox box, TestSideEffects heard) {
        this.t = t;
        this.heard = heard;
        this.level = t.level();
        // A copy: a BoundingBox can be moved, and the watched, placed and compared area must stay one area.
        Objects.requireNonNull(box, "box");
        this.box = new BoundingBox(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        Areas.watch(t.plugin(), level, box);
        t.registerEvents(this);
        this.before = snapshot();
        follow(before);
        // Last and uncancelled only: a join another listener refused never happened.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, EntityJoinLevelEvent.class,
                joins);
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
        heard.mark();
        before = snapshot();
        follow(before);
        seen.clear();
        unplaced.clear();
        touched.clear();
        elsewhere.clear();
        denied.clear();
        arrived.clear();
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
        List<String> problems = new ArrayList<>(AcceptanceDiff.changes(before, after, which, seen, elsewhere, touched,
                arrived));
        if (which.contains(Part.BLOCKS)) {
            // A vibration in the box is what a sculk sensor there would turn into a redstone signal. Walking through
            // is allowed, so motion doesn't count, and a test player's events in another world are elsewhere.
            for (SideEffects.GameEventEmitted e : heard.seen(SideEffects.GameEventEmitted.class)) {
                if (!TestSideEffects.isMotion(e.event()) && e.level() == level
                        && box.isInside(BlockPos.containing(e.pos()))) {
                    problems.add("a sculk sensor in the box would hear it: " + e);
                }
            }
        }
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
        Where place = place(event);
        if (place instanceof Where.Out) return;
        // An event that can't be placed might be in the box: fail closed, as protection should.
        if (!allowed(a.getActor(), event) || place instanceof Where.Unplaced) {
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
            case Where.In in -> {
                seen.add(new Seen(event, a.getActor(), event instanceof Cancellable c && c.isCancelled(), in.pos()));
                for (Entity e : named(event)) {
                    touched.add(e.getUUID());
                    followed.putIfAbsent(e.getUUID(), e);
                }
            }
            case Where.Unplaced u -> {
                if (!(a.getActor() instanceof Actor.Server)) {
                    unplaced.add(new Unplaced(new Seen(event, a.getActor(), event instanceof Cancellable c
                            && c.isCancelled(), Optional.empty()), u.why()));
                }
            }
            case Where.Out o -> {
                boolean motion = MOTION.stream().anyMatch(m -> m.isInstance(event));
                // An entity it names that stands in the area is compared, though the event is placed elsewhere: a
                // bridge's event may name more than it places, and what it did to that entity is still a change.
                // Motion is anyone's to make, so it names nothing to compare.
                for (Entity e : motion ? List.<Entity>of() : named(event)) {
                    if (e.level() == level && box.isInside(e.blockPosition())) {
                        touched.add(e.getUUID());
                        followed.putIfAbsent(e.getUUID(), e);
                    }
                }
                // Kept for failure messages only: an event about an entity being compared, placed outside the area.
                if (!motion && named(event).stream().anyMatch(e -> followed.containsKey(e.getUUID())
                        || t.players().stream().anyMatch(p -> p.uuid().equals(e.getUUID())))) {
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

    /**
     * Where an event happens, as far as the box goes, read from its places: in when one is in the box, unplaced when
     * none is but an unknown one could be, out otherwise. Motion and whole-level events are out whatever their places,
     * since walking through and the weather are not the box's to refuse.
     */
    private Where place(Event event) {
        // Subclasses too: a player's move is a PlayerMoveEvent.
        for (Class<? extends Event> motion : MOTION) if (motion.isInstance(event)) return OUT;
        for (Class<? extends Event> whole : WHOLE_LEVEL) if (whole.isInstance(event)) return OUT;
        if (!(event instanceof Placed placed)) return OUT;
        Place.Unknown unknown = null;
        for (Place p : placed.getPlaces()) {
            switch (p) {
                case Place.Spot s when s.level() == level && s.box().intersects(box) -> {
                    return new Where.In(s.isBlock() ? Optional.of(new BlockPos(s.box().minX(), s.box().minY(),
                            s.box().minZ())) : Optional.empty());
                }
                case Place.Level l when l.level() == level -> {
                    return new Where.In(Optional.empty());
                }
                case Place.Unknown u -> {
                    if (unknown == null && couldInclude(u.bound())) unknown = u;
                }
                default -> {}
            }
        }
        if (unknown == null) return OUT;
        return new Where.Unplaced("the acceptance kit doesn't know where a " + event.getEventName() + " happens: "
                + why(unknown.reason()));
    }

    private boolean couldInclude(Place.Bound bound) {
        return switch (bound) {
            case Place.Anywhere a -> true;
            case Place.Level l -> l.level() == level;
            case Place.Spot s -> s.level() == level && s.box().intersects(box);
        };
    }

    /** What a bridge fixes so its event is placed. */
    private static String why(Place.Unknown.Reason reason) {
        return switch (reason) {
            case UNIDENTIFIED_INVENTORY -> "its holder is unknown, so nothing says whose inventory it is: name the "
                    + "holder (InventoryHolder.Block or InventoryHolder.Entity)";
            case UNDESCRIBED_MENU -> "its menu is one nothing describes, so nothing says which inventories its slots "
                    + "reach: name where the menu is open (a ContainerLevelAccess, or the block entity or entity as "
                    + "its slots' container)";
            case UNPLACEABLE_OPERATION -> "its places are Place.Unknown: state where it happens in getPlaces(), or "
                    + "make it a block, entity, item, inventory, resource or world event, whose family states them";
            case UNSEEN_ORIGIN -> "nothing says where it came from: state where it starts";
            case COMMAND -> "a command acts where its effects do, which fire their own events";
            case NOBODY_BEHIND_IT -> "nobody is behind it and nothing else says where: push the actor (the player or "
                    + "machine doing it)";
        };
    }

    /**
     * Every entity an event names, read from all its getters: its subject, the other of a pair (a mount, a lead's
     * holder, a breeding's parents), a shot's or blast's victims, its items, whoever did it, and the entities holding
     * any inventory or resource it names. Read rather than listed per kind, so a new event, or a getter added to one,
     * can't leave an entity it touches uncompared. Not where it happens: that is {@link Placed#getPlaces()}.
     */
    static List<Entity> named(Event event) {
        Set<Entity> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (java.lang.reflect.Method getter : GETTERS.get(event.getClass())) {
            try {
                collect(getter.invoke(event), out);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A getter that can't answer names nothing.
            }
        }
        return List.copyOf(out);
    }

    /**
     * Who an action is for, not who took part in it: an owner far from their machine is no participant. An optional
     * {@code getPlayer} is the same as {@code getActingPlayer} on every action that has one; a player event's own
     * {@code getPlayer}, never empty, is its subject and is read.
     */
    private static final Set<String> ATTRIBUTION = Set.of("getActor", "getActingPlayer", "getOperation",
            "getPlaces");

    /** Each event class's public no-argument getters, outside {@link Object} and attribution. */
    @dev.iredsc.hadron.checks.StaticState(reason = "a cache of each event class's getters, which never change; "
            + "it goes with the classes")
    private static final ClassValue<List<java.lang.reflect.Method>> GETTERS = new ClassValue<>() {
        @Override
        protected List<java.lang.reflect.Method> computeValue(Class<?> type) {
            List<java.lang.reflect.Method> out = new ArrayList<>();
            for (java.lang.reflect.Method m : type.getMethods()) {
                if (m.getParameterCount() != 0 || m.getDeclaringClass() == Object.class
                        || java.lang.reflect.Modifier.isStatic(m.getModifiers()) || !m.getName().startsWith("get")
                        || ATTRIBUTION.contains(m.getName())
                        || m.getName().equals("getPlayer") && m.getReturnType() == Optional.class) {
                    continue;
                }
                // A public getter on an event class that isn't public (a bridge's nested one) is read all the same.
                m.trySetAccessible();
                out.add(m);
            }
            return List.copyOf(out);
        }
    };

    private static void collect(@Nullable Object value, Set<Entity> out) {
        switch (value) {
            case null -> {}
            case Entity e -> out.add(e);
            case Optional<?> o -> o.ifPresent(v -> collect(v, out));
            case java.util.Collection<?> c -> c.forEach(v -> collect(v, out));
            case InventoryHolder h -> holderEntity(h).ifPresent(out::add);
            case ResourceHolder.Entity r -> out.add(r.entity());
            case ResourceHolder.Item r -> holderEntity(r.heldIn()).ifPresent(out::add);
            default -> {}
        }
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

    // ---- Recording the box ----------------------------------------------------------------------------------------

    /** An entity as it stands now: what the snapshot compares. */
    private AcceptanceDiff.Thing thing(Entity e) {
        ItemStack stack = e instanceof ItemEntity item ? item.getItem().copy() : ItemStack.EMPTY;
        Map<String, ItemStack> held = new LinkedHashMap<>();
        if (e instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) held.put("slot " + i, c.getItem(i).copy());
        }
        float health = e instanceof LivingEntity living ? living.getHealth() : -1;
        return new AcceptanceDiff.Thing(e.getUUID(), String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())),
                stack, health, held,
                // By the block it stands in, as place() puts its events.
                e.blockPosition(), e.level() == level && box.isInside(e.blockPosition()));
    }

    /**
     * From a mark on, every entity anywhere then is followed, however many times the step is asserted: one that walks
     * in and is destroyed there is seen going, by where it was last.
     */
    private void follow(AcceptanceDiff.Snapshot at) {
        Map<UUID, Entity> was = new LinkedHashMap<>(followed);
        followed.clear();
        for (AcceptanceDiff.Thing thing : at.entities().values()) {
            // The reference the snapshot read it through, first: one saved with its chunk is listed by no world.
            Entity known = was.get(thing.id());
            if (known != null) {
                followed.put(thing.id(), known);
                continue;
            }
            for (ServerLevel l : level.getServer().getAllLevels()) {
                Entity e = l.getEntity(thing.id());
                if (e != null) {
                    followed.put(thing.id(), e);
                    break;
                }
            }
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
        Map<UUID, AcceptanceDiff.Thing> gone = new LinkedHashMap<>();
        Map<UUID, Entity> present = new LinkedHashMap<>();
        // Every loaded world's, the test's first: a mob that comes from another world was already somewhere.
        for (Entity e : level.getAllEntities()) present.put(e.getUUID(), e);
        for (ServerLevel other : level.getServer().getAllLevels()) {
            if (other == level) continue;
            for (Entity e : other.getAllEntities()) present.putIfAbsent(e.getUUID(), e);
        }
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
            } else if (!(e instanceof ServerPlayer)) {
                gone.put(f.getKey(), thing(e));
            }
        }
        for (Entity e : present.values()) {
            if (!(e instanceof ServerPlayer)) entities.put(e.getUUID(), thing(e));
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
        return new AcceptanceDiff.Snapshot(blocks, blockEntities, contents, entities, gone, ids, players,
                health);
    }

    /** Whether a handler is the vanilla wrapper over this chest, or over the double chest it is half of. */
    private static boolean overChest(IItemHandler handler, ChestBlockEntity chest) {
        // Exactly vanilla's wrapper: a subclass may add storage of its own.
        return handler.getClass() == net.neoforged.neoforge.items.wrapper.InvWrapper.class
                && handler instanceof net.neoforged.neoforge.items.wrapper.InvWrapper w
                && (w.getInv() == chest || w.getInv() instanceof CompoundContainer both && both.contains(chest));
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
        // A chest's vanilla item handler is both halves of a double chest, so its own slots are the whole truth for
        // it; any other handler it offers (a mod's) is a store of its own and is read as one.
        if (be instanceof ChestBlockEntity chest && sideless != null && overChest(sideless, chest)) sideless = null;
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
            if (h == null || handlers.stream().anyMatch(seen -> seen == h)
                    || be instanceof ChestBlockEntity chest && overChest(h, chest)) {
                continue;
            }
            handlers.add(h);
            for (int i = 0; i < h.getSlots(); i++) out.put(side.getName() + " slot " + i, h.getStackInSlot(i).copy());
        }
        return out;
    }
}
