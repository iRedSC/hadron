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
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.world.Areas;
import dev.iredsc.hadron.test.Acceptance;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;
import java.util.ArrayList;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
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
    }

    @Override
    public List<Seen> events() {
        return List.copyOf(seen);
    }

    @Override
    public void assertUnchanged(String step) {
        List<String> problems = AcceptanceDiff.unchanged(before, snapshot(), seen);
        if (!problems.isEmpty()) {
            throw new AssertionError(step + " should have changed nothing in the protected area, but "
                    + problems.size() + " thing(s) changed:\n  " + String.join("\n  ", problems));
        }
    }

    @Override
    public void assertEveryChangeSeen(String step) {
        AcceptanceDiff.Snapshot after = snapshot();
        if (!AcceptanceDiff.anyChange(before, after)) {
            throw new AssertionError(step + ": nothing in the area changed since the last mark, so this proves "
                    + "nothing. Check the machine ran (power, fuel, an owner, enough ticks), or use assertUnchanged "
                    + "for a step that should change nothing.");
        }
        List<String> problems = AcceptanceDiff.unseen(before, after, seen);
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
        if (seen.isEmpty()) {
            throw new AssertionError(step + ": no event fired in the area, so nothing shows who acted. If the step "
                    + "changed something, that is an interception gap; if it shouldn't have, assert that instead.");
        }
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
        if (at.isEmpty()) return;
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
        if (MOTION.contains(event.getClass())) return Optional.empty();
        if (event instanceof BlockEvent b) {
            if (b.getLevel() != level) return Optional.empty();
            if (box.isInside(b.getPos())) return Optional.of(new Where(Optional.of(b.getPos())));
            for (BlockChange change : b.getBlocks()) {
                if (box.isInside(change.getPos())) return Optional.of(new Where(Optional.of(change.getPos())));
            }
            return Optional.empty();
        }
        if (event instanceof EntityEvent e) return at(e.getLevel(), e.getPosition());
        if (event instanceof ExplosionEvent x) return at(x.getLevel(), x.getLocation());
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
        Map<BlockPos, List<ItemStack>> contents = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockPos pos = p.immutable();
            blocks.put(pos, level.getBlockState(pos));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) blockEntities.put(pos, String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            List<ItemStack> stacks = items(pos);
            if (!stacks.isEmpty()) contents.put(pos, stacks);
        }
        Map<UUID, AcceptanceDiff.Thing> entities = new LinkedHashMap<>();
        Set<UUID> world = new HashSet<>();
        for (Entity e : level.getAllEntities()) world.add(e.getUUID());
        // By the block it stands in, as where() places its events, so an entity counted here is one whose events
        // protection and the logger hear.
        for (Entity e : level.getEntities((Entity) null, AABB.of(box).inflate(1),
                e -> !(e instanceof ServerPlayer) && box.isInside(e.blockPosition()))) {
            ItemStack stack = e instanceof ItemEntity item ? item.getItem().copy() : ItemStack.EMPTY;
            boolean consequence = e instanceof ItemEntity || e instanceof ExperienceOrb
                    || e instanceof FallingBlockEntity;
            float health = e instanceof LivingEntity living ? living.getHealth() : -1;
            entities.put(e.getUUID(), new AcceptanceDiff.Thing(e.getUUID(),
                    String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())), stack, health,
                    e.blockPosition(), consequence));
        }
        Map<String, List<ItemStack>> players = new LinkedHashMap<>();
        Map<String, Float> health = new LinkedHashMap<>();
        for (TestPlayer tp : t.players()) {
            Inventory inv = tp.player().getInventory();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) stacks.add(inv.getItem(i).copy());
            stacks.add(tp.player().containerMenu.getCarried().copy());
            players.put(tp.name(), stacks);
            health.put(tp.name(), tp.player().getHealth());
        }
        return new AcceptanceDiff.Snapshot(blocks, blockEntities, contents, entities, world, players, health);
    }

    /**
     * Every slot a block exposes: its sideless item handler, then each distinct face's in turn. A wrapper made afresh
     * per face lists the same slots again, which only repeats them. Empty when it has none.
     */
    private List<ItemStack> items(BlockPos pos) {
        List<IItemHandler> handlers = new ArrayList<>();
        // Every face as well as the sideless one: a block may keep a separate store behind one face.
        List<@Nullable Direction> sides = new ArrayList<>();
        sides.add(null);
        sides.addAll(List.of(Direction.values()));
        for (@Nullable Direction side : sides) {
            @Nullable IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (h != null && handlers.stream().noneMatch(seen -> seen == h)) handlers.add(h);
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (IItemHandler h : handlers) {
            for (int i = 0; i < h.getSlots(); i++) stacks.add(h.getStackInSlot(i).copy());
        }
        return stacks;
    }
}
