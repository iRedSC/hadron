package dev.iredsc.hadron.regions;

import static dev.iredsc.hadron.regions.Action.*;

import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.Placed;
import dev.iredsc.hadron.api.event.block.BlockBreakEvent;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import dev.iredsc.hadron.api.event.block.BlockChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockCrackEvent;
import dev.iredsc.hadron.api.event.block.BlockDamageEvent;
import dev.iredsc.hadron.api.event.block.BlockDataChangeEvent;
import dev.iredsc.hadron.api.event.block.BlockDropItemEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockFadeEvent;
import dev.iredsc.hadron.api.event.block.BlockFertilizeEvent;
import dev.iredsc.hadron.api.event.block.BlockFormEvent;
import dev.iredsc.hadron.api.event.block.BlockFromToEvent;
import dev.iredsc.hadron.api.event.block.BlockGrowEvent;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockMultiPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonExtendEvent;
import dev.iredsc.hadron.api.event.block.BlockPistonRetractEvent;
import dev.iredsc.hadron.api.event.block.BlockPlaceEvent;
import dev.iredsc.hadron.api.event.block.BlockRedstoneEvent;
import dev.iredsc.hadron.api.event.block.BlockSpreadEvent;
import dev.iredsc.hadron.api.event.block.BlockTransformEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.block.BucketEmptyEvent;
import dev.iredsc.hadron.api.event.block.BucketEvent;
import dev.iredsc.hadron.api.event.block.BucketFillEvent;
import dev.iredsc.hadron.api.event.block.LeavesDecayEvent;
import dev.iredsc.hadron.api.event.block.SignChangeEvent;
import dev.iredsc.hadron.api.event.block.StructureGrowEvent;
import dev.iredsc.hadron.api.event.block.StructureMoveEvent;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityBreedEvent;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityCombustEvent;
import dev.iredsc.hadron.api.event.entity.EntityControlChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityDataChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityKnockbackEvent;
import dev.iredsc.hadron.api.event.entity.EntityLeashEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import dev.iredsc.hadron.api.event.entity.EntityRegainHealthEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityShearEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntityStatusChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityTameEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTransformEvent;
import dev.iredsc.hadron.api.event.entity.EntityUnleashEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.entity.ExperiencePickupEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.api.event.entity.HangingPlaceEvent;
import dev.iredsc.hadron.api.event.entity.ItemFrameChangeEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileLaunchEvent;
import dev.iredsc.hadron.api.event.entity.VehicleDamageEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryDestroyEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryGenerateEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.inventory.InventorySortEvent;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemConsumeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemDamageEvent;
import dev.iredsc.hadron.api.event.item.ItemDespawnEvent;
import dev.iredsc.hadron.api.event.item.ItemDropEvent;
import dev.iredsc.hadron.api.event.item.ItemEnchantEvent;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemHeldEvent;
import dev.iredsc.hadron.api.event.item.ItemMergeEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemRepairEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.item.PrepareAnvilEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.api.event.player.PlayerAbilitiesChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerAbilitiesEvent;
import dev.iredsc.hadron.api.event.player.PlayerBedEnterEvent;
import dev.iredsc.hadron.api.event.player.PlayerChatEvent;
import dev.iredsc.hadron.api.event.player.PlayerEvent;
import dev.iredsc.hadron.api.event.player.PlayerExpChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerFishEvent;
import dev.iredsc.hadron.api.event.player.PlayerGameModeChangeEvent;
import dev.iredsc.hadron.api.event.player.PlayerKickEvent;
import dev.iredsc.hadron.api.event.player.PlayerModPacketEvent;
import dev.iredsc.hadron.api.event.player.PlayerMoveEvent;
import dev.iredsc.hadron.api.event.player.PlayerSwapHandItemsEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleFlightEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSneakEvent;
import dev.iredsc.hadron.api.event.player.PlayerToggleSprintEvent;
import dev.iredsc.hadron.api.event.resource.ResourceChangeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceConsumeEvent;
import dev.iredsc.hadron.api.event.resource.ResourceConvertEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.resource.ResourceProduceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceTransferEvent;
import dev.iredsc.hadron.api.event.server.AccessControlChangeEvent;
import dev.iredsc.hadron.api.event.server.CommandExecuteEvent;
import dev.iredsc.hadron.api.event.server.DataRevealEvent;
import dev.iredsc.hadron.api.event.server.ServerEvent;
import dev.iredsc.hadron.api.event.world.ChunkForceLoadEvent;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.api.event.world.LightningStrikeEvent;
import dev.iredsc.hadron.api.event.world.PortalCreateEvent;
import dev.iredsc.hadron.api.event.world.TickAccelerateEvent;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.api.event.world.WorldEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * The one table from events to nodes: an entry per event class, each the node it is, the node by the kind of entity
 * acted on, the node by how a spawn came to be, or not a region's with the reason. A test fails for any cancellable
 * event in {@code api} without an entry of its own, and for a node no entry produces. At runtime a bridge's own
 * subclass uses its nearest mapped parent, and an action with none is {@link Action#OTHER}, which fails closed.
 *
 * <p>Places are the event's own ({@code getPlaces()}), but for one rule: a player's own inventory, and anything
 * carried in it, stands outside every region, since it goes where they go.
 *
 * <p>Built per instance: a static table fails Hadron's static-state check, and the plugin makes one when it enables.
 */
final class Nodes {
    /** What one event class is to regions. */
    sealed interface Entry {
        /** The questions it asks; {@code produces} is every action they can name, so a dead node fails a test. */
        record Asking(Set<Action> produces, Function<Event, List<Ask>> asks) implements Entry {
            public Asking {
                produces = Set.copyOf(produces);
            }
        }

        /** No region decides it, and why. */
        record NotRegions(String reason) implements Entry {
            public NotRegions {
                if (reason.isBlank()) throw new IllegalArgumentException("say why no region decides it");
            }
        }
    }

    private final Map<Class<? extends Event>, Entry> mapping = new LinkedHashMap<>();

    Nodes() {
        blocks();
        entities();
        movement();
        items();
        inventories();
        resources();
        world();
        players();
    }

    /** Every entry, by the class it is for. */
    Map<Class<? extends Event>, Entry> mapping() {
        return Collections.unmodifiableMap(mapping);
    }

    /**
     * The entry for an event of this class: its own, or its nearest mapped parent's (a bridge's subclass), which past
     * {@link ActionEvent} is {@link Action#OTHER}. An event that isn't an action and has no entry isn't a region's.
     */
    Entry entryFor(Class<?> type) {
        for (Class<?> c = type; c != null && c != Event.class; c = c.getSuperclass()) {
            Entry e = mapping.get(c);
            if (e != null) return e;
        }
        return new Entry.NotRegions("not an action, so no place's");
    }

    /** What {@code event} asks the regions; empty when no region decides it. */
    List<Ask> asks(Event event) {
        return switch (entryFor(event.getClass())) {
            case Entry.Asking a -> a.asks().apply(event);
            case Entry.NotRegions n -> List.of();
        };
    }

    // ---- Blocks ---------------------------------------------------------------------------------------------------

    private void blocks() {
        node(BlockBreakEvent.class, BLOCK_BREAK);
        node(BlockDamageEvent.class, BLOCK_BREAK);
        node(BlockCrackEvent.class, BLOCK_BREAK);
        node(BucketFillEvent.class, BLOCK_BREAK);
        node(BlockPlaceEvent.class, BLOCK_PLACE);
        node(BlockMultiPlaceEvent.class, BLOCK_PLACE);
        node(BucketEmptyEvent.class, BLOCK_PLACE);
        node(BucketEvent.class, BLOCK_CHANGE);
        node(BlockChangeEvent.class, BLOCK_CHANGE);
        node(BlockTransformEvent.class, BLOCK_CHANGE);
        node(BlockDataChangeEvent.class, BLOCK_CHANGE);
        node(SignChangeEvent.class, BLOCK_SIGN);
        // A move asks block.move of its actor, and enter.move of every player it carries.
        Function<BlockMoveEvent, List<Ask>> move = e -> List.of(new Ask.Act(Node.of(BLOCK_MOVE),
                List.copyOf(e.getPlaces())), new Ask.Enter(ENTER_MOVE, Optional.empty(), List.of(), e.getCarried()));
        asks(BlockMoveEvent.class, EnumSet.of(BLOCK_MOVE, ENTER_MOVE), move::apply);
        asks(BlockPistonEvent.class, EnumSet.of(BLOCK_MOVE, ENTER_MOVE), move::apply);
        asks(BlockPistonExtendEvent.class, EnumSet.of(BLOCK_MOVE, ENTER_MOVE), move::apply);
        asks(BlockPistonRetractEvent.class, EnumSet.of(BLOCK_MOVE, ENTER_MOVE), move::apply);
        asks(StructureMoveEvent.class, EnumSet.of(BLOCK_MOVE, ENTER_MOVE), move::apply);
        // Where fire comes from: unknown (lava, spreading flames) counts as outside every region.
        nodeFrom(BlockBurnEvent.class, BLOCK_BURN, e -> List.of(e.getIgnitingPos()
                .<Place>map(p -> Place.Spot.at(e.getLevel(), p)).orElse(OUTSIDE)));
        nodeFrom(BlockIgniteEvent.class, BLOCK_IGNITE, e -> List.of(OUTSIDE));
        nodeFrom(BlockFromToEvent.class, BLOCK_FLOW, e -> List.of(Place.Spot.at(e.getLevel(), e.getFromPos())));
        nodeFrom(BlockSpreadEvent.class, BLOCK_SPREAD, e -> List.of(Place.Spot.at(e.getLevel(), e.getSourcePos())));
        node(BlockFormEvent.class, BLOCK_FORM);
        node(BlockFadeEvent.class, BLOCK_FADE);
        node(BlockGrowEvent.class, BLOCK_GROW);
        node(StructureGrowEvent.class, BLOCK_GROW);
        node(LeavesDecayEvent.class, BLOCK_DECAY);
        node(BlockFertilizeEvent.class, BLOCK_FERTILIZE);
        node(BlockUseEvent.class, BLOCK_USE);
        node(BlockRedstoneEvent.class, BLOCK_REDSTONE);
        notRegions(BlockDropItemEvent.class, "drops follow the break that made them, which was already asked");
        node(BlockEvent.class, OTHER);
    }

    // ---- Entities -------------------------------------------------------------------------------------------------

    private void entities() {
        asks(EntitySpawnEvent.class, EnumSet.of(ENTITY_PLACE, ENTITY_SPAWN, ENTITY_LAUNCH), this::spawn);
        asks(HangingPlaceEvent.class, EnumSet.of(ENTITY_PLACE, ENTITY_SPAWN, ENTITY_LAUNCH), this::spawn);
        node(ProjectileLaunchEvent.class, ENTITY_LAUNCH);
        // A hit states where it set out from; a pvp node is decided there too (Bundle.bothEnds).
        asks(EntityDamageEvent.class, EnumSet.of(ENTITY_HURT), e -> attack(e, ENTITY_HURT, e.getSourceLocation()));
        hurt(VehicleDamageEvent.class, ENTITY_HURT);
        hurt(EntityCombustEvent.class, ENTITY_IGNITE);
        // Only a harmful effect taking hold is an attack; a removed or helpful one is nobody's to refuse.
        asks(EntityPotionEffectEvent.class, EnumSet.of(ENTITY_EFFECT), e -> e.getAction()
                == EntityPotionEffectEvent.Action.REMOVED || e.getEffect().value().getCategory()
                != MobEffectCategory.HARMFUL ? List.of() : attack(e, ENTITY_EFFECT, Optional.empty()));
        node(HangingBreakEvent.class, ENTITY_BREAK);
        byKind(EntityRemoveEvent.class, ENTITY_REMOVE);
        // Where it is and where it is stored; carried off by a player, it goes where they go.
        byKind(EntityCaptureEvent.class, ENTITY_CAPTURE, e -> {
            List<Place> places = new ArrayList<>(e.getEntityPlaces());
            switch (e.getStorage()) {
                case EntityCaptureEvent.Storage.Block b -> places.add(Place.Spot.at(b.level(), b.pos()));
                case EntityCaptureEvent.Storage.Carried c -> places.add(placeOf(c.carrier()));
                case EntityCaptureEvent.Storage.Item i -> { }
            }
            return places;
        });
        byKind(ItemFrameChangeEvent.class, ENTITY_CHANGE);
        byKind(EntityStatusChangeEvent.class, ENTITY_CHANGE);
        byKind(EntityDataChangeEvent.class, ENTITY_CHANGE);
        byKind(ArmorStandManipulateEvent.class, ENTITY_EQUIP);
        asks(EquipEvent.class, EnumSet.of(ENTITY_EQUIP), e -> List.of(new Ask.Act(Node.of(ENTITY_EQUIP,
                Kind.of(e.getEntity())), List.copyOf(e.getPlaces()))));
        byKind(EntityUseEvent.class, ENTITY_USE);
        byKind(EntityLeashEvent.class, ENTITY_LEASH);
        byKind(EntityUnleashEvent.class, ENTITY_LEASH);
        byKind(EntityShearEvent.class, ENTITY_SHEAR);
        byKind(EntityTameEvent.class, ENTITY_TAME);
        byKind(EntityBreedEvent.class, ENTITY_BREED);
        byKind(EntityTransformEvent.class, ENTITY_TRANSFORM);
        byKind(EntityControlChangeEvent.class, ENTITY_CONTROL);
        byKind(EntityVelocityEvent.class, ENTITY_PUSH);
        byKind(EntityKnockbackEvent.class, ENTITY_PUSH);
        // The kind is the target's; letting go of a target is nobody's to refuse.
        asks(EntityTargetEvent.class, EnumSet.of(ENTITY_TARGET), e -> e.getTarget().<List<Ask>>map(t -> List.of(
                new Ask.Act(Node.of(ENTITY_TARGET, Kind.of(t)), List.copyOf(e.getPlaces())))).orElse(List.of()));
        node(ExperiencePickupEvent.class, ITEM_PICKUP);
        notRegions(EntityAirChangeEvent.class, "breathing is the entity's own");
        notRegions(EntityRegainHealthEvent.class, "healing harms nobody");
        notRegions(ProjectileHitEvent.class, "judged by the damage or block change it causes");
        node(EntityEvent.class, OTHER);
    }

    private List<Ask> spawn(EntitySpawnEvent e) {
        List<Place> places = List.copyOf(e.getPlaces());
        Action a = switch (e.getCause()) {
            case PLACED -> ENTITY_PLACE;
            case SPAWNED -> ENTITY_SPAWN;
            case LAUNCHED -> ENTITY_LAUNCH;
        };
        return List.of(new Ask.Act(a.kinded() ? Node.of(a, Kind.of(e.getEntity())) : Node.of(a), places));
    }

    /** An attack on the event's entity; on a player by themselves (their own arrow, their own potion) it is theirs. */
    private static List<Ask> attack(EntityEvent e, Action a, Optional<Place> setOutFrom) {
        Entity victim = e.getEntity();
        if (victim instanceof Player p && e.getActor().actingFor().equals(Optional.of(p.getUUID()))) return List.of();
        return List.of(new Ask.Act(Node.of(a, Kind.of(victim)), List.copyOf(e.getPlaces()), Optional.empty(),
                setOutFrom));
    }

    /** An attack whose event doesn't state where it set out from: it is decided where it lands. */
    private <E extends EntityEvent> void hurt(Class<E> type, Action a) {
        asks(type, EnumSet.of(a), e -> attack(e, a, Optional.empty()));
    }

    // ---- What takes a player somewhere ----------------------------------------------------------------------------

    private void movement() {
        Function<EntityMoveEvent, List<Ask>> walk = e -> {
            Carried.Ride ride = new Carried.Ride(e.getEntity(), Place.Spot.at(e.getLevel(), e.getFromBlock()),
                    Place.Spot.at(e.getLevel(), e.getToBlock()));
            return List.of(new Ask.Enter(ENTER_MOVE, Optional.of(ride), List.of(ride), e.getCarried()));
        };
        asks(EntityMoveEvent.class, EnumSet.of(ENTER_MOVE), walk::apply);
        asks(PlayerMoveEvent.class, EnumSet.of(ENTER_MOVE), walk::apply);
        // Judged where it lands, which the event states.
        asks(EntityTeleportEvent.class, EnumSet.of(ENTER_TELEPORT), e -> {
            Carried.Ride ride = new Carried.Ride(e.getEntity(), Place.Spot.at(e.getFromLevel(), e.getFrom()),
                    Place.Spot.at(e.getToLevel(), e.getTo()));
            return List.of(new Ask.Enter(ENTER_TELEPORT, Optional.of(ride), List.of(ride), e.getCarried()));
        });
        // Boarding enters the seat, which can be another block than the mount's; what carries them all is the mount.
        asks(EntityMountEvent.class, EnumSet.of(ENTER_MOUNT), e -> {
            Place from = Place.Spot.at(e.getLevel(), e.getPosition());
            return List.of(new Ask.Enter(ENTER_MOUNT, Optional.of(new Carried.Ride(e.getEntity(), from,
                    Place.of(e.getMount()))), List.of(new Carried.Ride(e.getEntity(), from,
                    Place.Spot.at(e.getLevel(), e.getTo()))), e.getCarried()));
        });
        // Getting off enters where the rider is set down.
        asks(EntityDismountEvent.class, EnumSet.of(ENTER_DISMOUNT), e -> {
            Carried.Ride ride = new Carried.Ride(e.getEntity(), Place.Spot.at(e.getLevel(), e.getPosition()),
                    Place.Spot.at(e.getLevel(), e.getTo()));
            return List.of(new Ask.Enter(ENTER_DISMOUNT, Optional.of(ride), List.of(ride), Carried.none()));
        });
    }

    // ---- Items ----------------------------------------------------------------------------------------------------

    private void items() {
        node(ItemUseEvent.class, ITEM_USE, e -> List.of(placeOf(e.getUser())));
        node(ItemConsumeEvent.class, ITEM_CONSUME, e -> List.of(placeOf(e.getConsumer())));
        node(ItemCraftEvent.class, ITEM_CRAFT, e -> placesOf(e.getHolder()));
        node(ItemSmeltEvent.class, ITEM_SMELT, e -> placesOf(e.getHolder()));
        node(TradeEvent.class, ITEM_TRADE, e -> placesOf(e.getTrader()));
        node(ItemChangeEvent.class, ITEM_CHANGE, e -> placesOf(e.getHolder()));
        node(ItemDamageEvent.class, ITEM_CHANGE, e -> placesOf(e.getHolder()));
        node(ItemRepairEvent.class, ITEM_CHANGE, e -> placesOf(e.getHolder()));
        node(ItemEnchantEvent.class, ITEM_CHANGE, e -> placesOf(e.getHolder()));
        // Where the item lies, unless a player picks it up into their own inventory, and where it goes.
        node(ItemPickupEvent.class, ITEM_PICKUP, e -> {
            List<Place> places = new ArrayList<>();
            if (playersOwn(e.getDestination()).isEmpty()) places.add(Place.of(e.getItemEntity()));
            places.addAll(placesOf(e.getDestination()));
            return places;
        });
        node(ItemDropEvent.class, ITEM_DROP);
        notRegions(PrepareAnvilEvent.class, "only shows an offer; taking it is an ItemRepairEvent or ItemEnchantEvent");
        notRegions(ItemDespawnEvent.class, "an item running out of time is nobody's act");
        notRegions(ItemMergeEvent.class, "two stacks on the ground becoming one changes nothing anyone owns");
        notRegions(ItemHeldEvent.class, "switching hotbar slots is the player's own");
        node(ItemEvent.class, OTHER);
    }

    // ---- Inventories and resources ---------------------------------------------------------------------------------

    private void inventories() {
        // Every inventory behind a menu: a shift-click can move items into any of them.
        node(InventoryOpenEvent.class, INVENTORY_OPEN, e -> holders(e.getHolder(), e.getMenuHolder(),
                e.getSlotHolders()));
        node(InventoryClickEvent.class, INVENTORY_CLICK, e -> holders(e.getHolder(), e.getMenuHolder(),
                e.getSlotHolders()));
        node(InventoryChangeEvent.class, INVENTORY_CHANGE, e -> holders(e.getHolder(), e.getHolder(), e.getHolders()));
        node(InventoryGenerateEvent.class, INVENTORY_CHANGE, e -> holders(e.getHolder(), e.getHolder(),
                e.getHolders()));
        node(InventoryDestroyEvent.class, INVENTORY_CHANGE, e -> holders(e.getHolder(), e.getHolder(),
                e.getHolders()));
        node(InventorySortEvent.class, INVENTORY_CHANGE, e -> holders(e.getHolder(), e.getHolder(), e.getHolders()));
        node(InventoryMoveItemEvent.class, INVENTORY_MOVE, e -> holders(e.getHolder(), e.getHolder(),
                e.getHolders()));
        node(InventoryEvent.class, OTHER, e -> placesOf(e.getHolder()));
    }

    private static List<Place> holders(InventoryHolder holder, InventoryHolder menu,
            java.util.Collection<InventoryHolder> more) {
        Set<InventoryHolder> all = new LinkedHashSet<>();
        all.add(holder);
        all.add(menu);
        all.addAll(more);
        List<Place> places = new ArrayList<>();
        for (InventoryHolder h : all) places.addAll(placesOf(h));
        return places;
    }

    private void resources() {
        node(ResourceChangeEvent.class, RESOURCE_CHANGE, Nodes::resourcePlaces);
        node(ResourceProduceEvent.class, RESOURCE_CHANGE, Nodes::resourcePlaces);
        node(ResourceConsumeEvent.class, RESOURCE_CHANGE, Nodes::resourcePlaces);
        node(ResourceConvertEvent.class, RESOURCE_CHANGE, Nodes::resourcePlaces);
        node(ResourceTransferEvent.class, RESOURCE_MOVE, Nodes::resourcePlaces);
        node(ResourceEvent.class, OTHER, Nodes::resourcePlaces);
    }

    /** Where each holder stands; a player's own (their experience, what they carry) stands outside every region. */
    private static List<Place> resourcePlaces(ResourceEvent e) {
        List<ResourceHolder> holders = e.getHolders();
        if (holders.isEmpty()) return List.copyOf(e.getPlaces());
        List<Place> places = new ArrayList<>();
        for (ResourceHolder holder : holders) {
            Optional<UUID> own = switch (holder) {
                case ResourceHolder.Entity en when en.entity() instanceof Player p -> Optional.of(p.getUUID());
                case ResourceHolder.Item i -> playersOwn(i.heldIn());
                default -> Optional.empty();
            };
            if (own.isPresent()) places.add(Place.Nowhere.player(own.get()));
            else places.addAll(Place.of(holder));
        }
        return places;
    }

    // ---- The world, players, the server ---------------------------------------------------------------------------

    private void world() {
        node(LightningStrikeEvent.class, WORLD_LIGHTNING);
        node(PortalCreateEvent.class, WORLD_PORTAL);
        node(TickAccelerateEvent.class, WORLD_ACCELERATE);
        node(ChunkForceLoadEvent.class, WORLD_CHUNKLOAD);
        // Each block a region keeps is spared; the rest of the blast goes off. Nature's blast sets out from its centre.
        asks(ExplosionEvent.class, EnumSet.of(BLOCK_EXPLODE), e -> List.of(new Ask.Spare(Node.of(BLOCK_EXPLODE),
                e.getLevel(), e.blockList(), List.of(Place.Spot.at(e.getLevel(), e.getLocation())))));
        notRegions(ExplosionPrimeEvent.class, "judged by the blocks and entities the blast then touches");
        notRegions(WeatherChangeEvent.class, "a whole world's");
        notRegions(TimeSkipEvent.class, "a whole world's");
        node(WorldEvent.class, OTHER);
        node(ActionEvent.class, OTHER);
    }

    private void players() {
        node(PlayerBedEnterEvent.class, PLAYER_SLEEP);
        node(PlayerFishEvent.class, PLAYER_FISH);
        String own = "the player's own, wherever they are";
        notRegions(PlayerChatEvent.class, own);
        notRegions(PlayerExpChangeEvent.class, own);
        notRegions(PlayerGameModeChangeEvent.class, own);
        notRegions(PlayerKickEvent.class, own);
        notRegions(PlayerModPacketEvent.class, own);
        notRegions(PlayerSwapHandItemsEvent.class, own);
        notRegions(PlayerToggleFlightEvent.class, own);
        notRegions(PlayerToggleSneakEvent.class, own);
        notRegions(PlayerToggleSprintEvent.class, own);
        notRegions(PlayerAbilitiesChangeEvent.class, own);
        notRegions(PlayerAbilitiesEvent.class, own);
        notRegions(PlayerEvent.class, own);
        notRegions(CommandExecuteEvent.class, "what the command does places itself, as its own events");
        notRegions(DataRevealEvent.class, "it shows, it changes nothing");
        notRegions(AccessControlChangeEvent.class, "the server's own lists, not a place's");
        notRegions(ServerEvent.class, "the server's own, not a place's");
    }

    // ---- Where things stand for regions ---------------------------------------------------------------------------

    /** Outside every region: where an ignition from nowhere known, or an actor with no place, sets out from. */
    static final Place OUTSIDE = Place.Unknown.anywhere(Place.Unknown.Reason.UNSEEN_ORIGIN);

    /** Where an entity stands for regions: outside every one for a player, who goes where they go. */
    static Place placeOf(Entity entity) {
        return entity instanceof Player p ? Place.Nowhere.player(p.getUUID()) : Place.of(entity);
    }

    /** Where an inventory stands for regions: outside every one for a player's own, or what they carry in it. */
    static List<Place> placesOf(InventoryHolder holder) {
        Optional<UUID> own = playersOwn(holder);
        return own.isPresent() ? List.of(Place.Nowhere.player(own.get())) : Place.of(holder);
    }

    /** The player whose own inventory this is, or is carried in (a backpack in their pocket, however deep). */
    static Optional<UUID> playersOwn(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Entity en when en.entity() instanceof Player p -> Optional.of(p.getUUID());
            case InventoryHolder.Item i -> playersOwn(i.outer());
            default -> Optional.empty();
        };
    }

    // ---- Building the table ---------------------------------------------------------------------------------------

    private void put(Class<? extends Event> type, Entry entry) {
        if (mapping.put(type, entry) != null) throw new IllegalStateException(type.getSimpleName() + " is mapped twice");
    }

    private <E extends Event> void asks(Class<E> type, Set<Action> produces, Function<E, List<Ask>> asks) {
        put(type, new Entry.Asking(produces, ev -> asks.apply(type.cast(ev))));
    }

    /** The node it is, where it happens. */
    private <E extends Event & Placed> void node(Class<E> type, Action a) {
        node(type, a, e -> List.copyOf(e.getPlaces()));
    }

    private <E extends Event> void node(Class<E> type, Action a, Function<E, List<Place>> places) {
        Node node = Node.of(a);
        asks(type, EnumSet.of(a), e -> List.of(new Ask.Act(node, places.apply(e))));
    }

    /** The node it is, with where the world on its own sets out from, as the event states it. */
    private <E extends BlockEvent> void nodeFrom(Class<E> type, Action a, Function<E, List<Place>> home) {
        Node node = Node.of(a);
        asks(type, EnumSet.of(a), e -> List.of(new Ask.Act(node, List.copyOf(e.getPlaces()),
                Optional.of(home.apply(e)))));
    }

    /** The node by the kind of the event's entity. */
    private <E extends EntityEvent> void byKind(Class<E> type, Action a) {
        byKind(type, a, e -> List.copyOf(e.getPlaces()));
    }

    private <E extends EntityEvent> void byKind(Class<E> type, Action a, Function<E, List<Place>> places) {
        asks(type, EnumSet.of(a), e -> List.of(new Ask.Act(Node.of(a, Kind.of(e.getEntity())), places.apply(e))));
    }

    private void notRegions(Class<? extends Event> type, String reason) {
        put(type, new Entry.NotRegions(reason));
    }
}
