package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.entity.EntityAirChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityCaptureEvent;
import dev.iredsc.hadron.api.event.entity.EntityCombustEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.entity.EntityTargetEvent;
import dev.iredsc.hadron.api.event.entity.EntityVelocityEvent;
import dev.iredsc.hadron.api.event.entity.ProjectileHitEvent;
import dev.iredsc.hadron.api.event.item.EquipEvent;
import dev.iredsc.hadron.api.event.item.ItemChangeEvent;
import dev.iredsc.hadron.api.event.item.ItemConsumeEvent;
import dev.iredsc.hadron.api.event.item.ItemCraftEvent;
import dev.iredsc.hadron.api.event.item.ItemEvent;
import dev.iredsc.hadron.api.event.item.ItemPickupEvent;
import dev.iredsc.hadron.api.event.item.ItemSmeltEvent;
import dev.iredsc.hadron.api.event.item.ItemUseEvent;
import dev.iredsc.hadron.api.event.item.TradeEvent;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockDropItemEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockFromToEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockSpreadEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.entity.ArmorStandManipulateEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.resource.ResourceEvent;
import dev.iredsc.hadron.api.event.resource.ResourceHolder;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import dev.iredsc.hadron.api.event.block.BlockIgniteEvent;
import dev.iredsc.hadron.api.event.block.BlockBurnEvent;
import net.minecraft.world.effect.MobEffectCategory;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import net.minecraft.world.entity.Mob;
import dev.iredsc.hadron.api.event.inventory.InventoryEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntityRemoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityDismountEvent;
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.api.event.entity.ItemFrameChangeEvent;
import dev.iredsc.hadron.api.event.entity.VehicleDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
import dev.iredsc.hadron.api.event.world.ExplosionPrimeEvent;
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.ArrayList;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Refuses what regions forbid, through Hadron's events, so it covers every mod whose bridge is installed and treats
 * every mod without one as an outsider. Who is asked is the actor's {@link Actor#actingFor()}: a player, a machine's
 * owner, a drone's operator. The server is always let through; the world on its own is held to
 * {@link Rules#refusingCrossing}.
 */
final class Protection implements Listener {
    private final JavaPlugin plugin;
    private final RegionStore store;
    private final Feedback feedback;
    private final Bypass bypass;
    Protection(JavaPlugin plugin, RegionStore store, Feedback feedback, Bypass bypass) {
        this.plugin = plugin;
        this.store = store;
        this.feedback = feedback;
        this.bypass = bypass;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void block(BlockEvent e) {
        // Drops follow a break this already decided.
        if (!(e instanceof Cancellable c) || e instanceof BlockDropItemEvent) return;
        ServerLevel level = e.getLevel();
        if (!store.hasAny(level)) return;
        Flag flag = e instanceof BlockUseEvent ? Flag.USE : Flag.BUILD;
        if (e.getActor() instanceof Actor.Nature) {
            List<Region> from = source(e).map(at -> store.at(level, at)).orElse(List.of());
            for (BlockPos pos : touched(e)) {
                if (Rules.refusingCrossing(from, store.at(level, pos)).isPresent()) {
                    c.setCancelled(true);
                    return;
                }
            }
            return;
        }
        for (BlockPos pos : touched(e)) {
            Optional<Region> no = refusing(e, level, pos, flag);
            if (no.isPresent()) {
                c.setCancelled(true);
                tell(e, pos, no.get());
                return;
            }
        }
    }

    /** Spares the protected blocks; the rest of the blast goes off. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void explosion(ExplosionEvent e) {
        ServerLevel level = e.getLevel();
        if (!store.hasAny(level)) return;
        if (e.getActor() instanceof Actor.Nature) {
            List<Region> from = store.at(level, BlockPos.containing(e.getLocation()));
            e.blockList().removeIf(pos -> Rules.refusingCrossing(from, store.at(level, pos)).isPresent());
            return;
        }
        e.blockList().removeIf(pos -> refusing(e, level, pos, Flag.BUILD).isPresent());
    }

    /**
     * Covers players walking and vehicles: the driver, or whatever moves it, and every player aboard. A rider gets no
     * move of their own, so a member can't ferry an outsider in.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void move(EntityMoveEvent e) {
        ServerLevel level = e.getLevel();
        List<Region> from = store.at(level, e.getFromBlock());
        List<Region> to = store.at(level, e.getToBlock());
        if (from.containsAll(to)) return;
        Optional<Region> no = trusted(e) ? Optional.empty() : Rules.refusingEntry(from, to, e.getActor().actingFor());
        for (ServerPlayer rider : riders(e.getEntity())) {
            if (no.isPresent()) break;
            if (!bypass.active(rider)) no = Rules.refusingEntry(from, to, Optional.of(rider.getUUID()));
        }
        if (no.isEmpty()) return;
        e.setCancelled(true);
        Region refusing = no.get();
        e.getActingPlayer().ifPresent(p -> feedback.refused(p, e.getToBlock(), refusing));
        for (ServerPlayer rider : riders(e.getEntity())) feedback.refused(rider, e.getToBlock(), refusing);
    }

    /** Every player riding {@code vehicle}, however deep. */
    private static List<ServerPlayer> riders(Entity vehicle) {
        List<ServerPlayer> riders = new ArrayList<>();
        for (Entity rider : vehicle.getIndirectPassengers()) {
            if (rider instanceof ServerPlayer p) riders.add(p);
        }
        return riders;
    }

    /**
     * Teleports in (an ender pearl, chorus fruit, a portal, a plugin) are held to entry as walking in is: movement
     * tracking starts over after a teleport, so {@link #move} never sees them. Every player going counts: the one
     * teleporting and everyone riding along.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void teleport(EntityTeleportEvent e) {
        Set<ServerPlayer> going = going(e);
        if (going.isEmpty()) return;
        List<Region> from = store.at(e.getFromLevel(), BlockPos.containing(e.getFrom()));
        for (BlockPos spot : arrival(e)) {
            List<Region> to = store.at(e.getToLevel(), spot);
            for (ServerPlayer p : going) {
                Optional<Region> no = Rules.refusingEntry(from, to, Optional.of(p.getUUID()));
                if (no.isEmpty()) continue;
                e.setCancelled(true);
                for (ServerPlayer told : going) feedback.refused(told, spot, no.get());
                return;
            }
        }
    }

    /**
     * Where a teleport really ends can differ from where it asked (a mod's random teleport settles on the ground, a
     * waystone steps aside to a safe spot), so each player is checked again where they stand a tick later, and sent
     * back where they came from if a region they may not enter holds them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void landed(EntityTeleportEvent e) {
        Set<ServerPlayer> going = going(e);
        if (going.isEmpty()) return;
        recheck(going, e.getFromLevel(), e.getFrom());
    }

    /**
     * Getting off can set a player down inside a region (a boat facing into it), with no move or teleport to ask
     * before, so they are checked where they stand a tick later, as after a teleport.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void dismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || bypass.active(p)) return;
        recheck(Set.of(p), p.serverLevel(), p.position());
    }

    /**
     * A tick from now, sends each player back to {@code from} if they stand in a region they may not enter and
     * weren't in there.
     */
    private void recheck(Set<ServerPlayer> players, ServerLevel fromLevel, Vec3 from) {
        List<Region> before = store.at(fromLevel, BlockPos.containing(from));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (ServerPlayer p : players) {
                if (p.hasDisconnected() || p.isRemoved() || bypass.active(p)) continue;
                Optional<Region> no = Rules.refusingEntry(before, store.at(p.serverLevel(), p.blockPosition()),
                        Optional.of(p.getUUID()));
                if (no.isEmpty()) continue;
                feedback.refused(p, p.blockPosition(), no.get());
                p.teleportTo(fromLevel, from.x, from.y, from.z, p.getYRot(), p.getXRot());
            }
        });
    }

    /** The players a teleport moves who answer to entry: not the server's own teleports, nor bypassing players. */
    private Set<ServerPlayer> going(EntityTeleportEvent e) {
        Set<ServerPlayer> going = new LinkedHashSet<>();
        if (e.getActor() instanceof Actor.Server) return going;
        if (e.getEntity() instanceof ServerPlayer p) going.add(p);
        for (Entity rider : e.getPassengers()) {
            if (rider instanceof ServerPlayer p) going.add(p);
            going.addAll(riders(rider));
        }
        going.removeIf(bypass::active);
        return going;
    }

    /** Boarding is entering where the mount stands: a player stepping into a boat inside a region rides in. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void mount(EntityMountEvent e) {
        List<ServerPlayer> boarding = new ArrayList<>();
        if (e.getEntity() instanceof ServerPlayer p) boarding.add(p);
        boarding.addAll(riders(e.getEntity()));
        if (!(e.getMount().level() instanceof ServerLevel level)) return;
        BlockPos at = e.getMount().blockPosition();
        List<Region> to = store.at(level, at);
        for (ServerPlayer p : boarding) {
            if (bypass.active(p) || e.getActor() instanceof Actor.Server) continue;
            Optional<Region> no = Rules.refusingEntry(store.at(level, p.blockPosition()), to,
                    Optional.of(p.getUUID()));
            if (no.isEmpty()) continue;
            e.setCancelled(true);
            feedback.refused(p, at, no.get());
            return;
        }
    }

    /**
     * Where a teleport may end. Chorus fruit and endermen-style teleports within a world settle on the ground below
     * the spot asked for, so every block they could fall through counts, down to the first that stops them.
     */
    private static List<BlockPos> arrival(EntityTeleportEvent e) {
        BlockPos to = BlockPos.containing(e.getTo());
        boolean settles = e.getToLevel() == e.getFromLevel() && (e.getCause() == TeleportCause.CHORUS_FRUIT
                || e.getCause() == TeleportCause.RANDOM_TELEPORT);
        if (!settles) return List.of(to);
        ServerLevel level = e.getToLevel();
        List<BlockPos> column = new ArrayList<>();
        BlockPos at = to;
        column.add(at);
        while (at.getY() > level.getMinBuildHeight() && !level.getBlockState(at.below()).blocksMotion()) {
            at = at.below();
            column.add(at);
        }
        return column;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void damage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (victim instanceof ArmorStand) {
            entityChange(e, e, victim, Flag.BUILD);
            return;
        }
        if (!(victim instanceof ServerPlayer hurt)) return;
        pvp(e, hurt, e.getDamager()).ifPresent(no -> {
            e.setCancelled(true);
            tell(e, hurt.blockPosition(), no);
        });
    }

    /**
     * A harmful effect put on a player (a splash of poison, a lingering cloud) is an attack, refused where the hit
     * would be: its later ticks come from nobody, so this is the one moment to ask.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void effect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer hurt) || e.getAction() == EntityPotionEffectEvent.Action.REMOVED
                || e.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return;
        pvp(e, hurt, Optional.empty()).ifPresent(no -> {
            e.setCancelled(true);
            tell(e, hurt.blockPosition(), no);
        });
    }

    /** Setting a player alight is an attack, refused where a hit would be. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void ignite(EntityCombustEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer hurt)) return;
        pvp(e, hurt, Optional.empty()).ifPresent(no -> {
            e.setCancelled(true);
            tell(e, hurt.blockPosition(), no);
        });
    }

    /** The region refusing this attack on {@code hurt}, if it is PvP and a region on either side denies it. */
    private Optional<Region> pvp(ActionEvent e, ServerPlayer hurt, Optional<Entity> damager) {
        if (e.getActor() instanceof Actor.Server) return Optional.empty();
        // Whoever answers for it, online or not: a turret keeps its owner while they're away. Anything else made to
        // hurt (a machine, an arrow, something unknown) is an outsider when nobody owns it; only the world (lava, a
        // fall) and a mob acting on its own aren't PvP.
        Optional<UUID> who = e.getActor().actingFor();
        if (who.isPresent() && who.get().equals(hurt.getUUID())) return Optional.empty();
        if (who.isEmpty() && (e.getActor() instanceof Actor.Nature
                || e.getActor() instanceof Actor.EntityActor a && a.entity() instanceof Mob)) return Optional.empty();
        // Every side counts, each in its own world: nobody fights out of a safe zone or into one, even across worlds.
        List<Place.Spot> sides = new ArrayList<>(4);
        sides.add(Place.Spot.at(hurt.serverLevel(), hurt.blockPosition()));
        e.getActingPlayer().ifPresent(a -> sides.add(Place.Spot.at(a.serverLevel(), a.blockPosition())));
        damager.ifPresent(d -> spotOf(d).ifPresent(sides::add));
        // Where the attack comes from: a turret block, a fake player, a thrown potion, a mob someone controls.
        switch (e.getActor()) {
            case Actor.Machine m -> sides.add(Place.Spot.at(m.level(), m.pos()));
            case Actor.FakePlayer f -> sides.add(Place.Spot.at(f.fake().serverLevel(), f.fake().blockPosition()));
            case Actor.EntityActor a -> spotOf(a.entity()).ifPresent(sides::add);
            default -> { }
        }
        for (Place.Spot side : sides) {
            Optional<Region> no = Rules.refusing(store.at(side.level(), new BlockPos(side.box().minX(),
                    side.box().minY(), side.box().minZ())), Flag.PVP, who);
            if (no.isPresent()) return no;
        }
        return Optional.empty();
    }

    private static Optional<Place.Spot> spotOf(Entity entity) {
        return entity.level() instanceof ServerLevel level ? Optional.of(Place.Spot.at(level, entity.blockPosition()))
                : Optional.empty();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void use(EntityUseEvent e) {
        Entity target = e.getEntity();
        // People aren't a region's: trading items with a player, healing one, is between the two of them.
        if (target instanceof Player) return;
        entityChange(e, e, target, target instanceof ArmorStand || target instanceof HangingEntity ? Flag.BUILD
                : Flag.USE);
    }

    /** Taking, giving or swapping an armor stand's equipment, from a click or a mod's editing screen: building. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void armorStand(ArmorStandManipulateEvent e) {
        entityChange(e, e, e.getEntity(), Flag.BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void hangingBreak(HangingBreakEvent e) {
        entityChange(e, e, e.getEntity(), Flag.BUILD);
    }

    /** Hitting a boat or minecart wears it down and breaks it: building. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void vehicleHit(VehicleDamageEvent e) {
        entityChange(e, e, e.getEntity(), Flag.BUILD);
    }

    /** A frame's item going in, coming out or turning is building, whoever or whatever does it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void frame(ItemFrameChangeEvent e) {
        entityChange(e, e, e.getEntity(), Flag.BUILD);
    }

    /**
     * Placing an object is building: item frames and paintings, armor stands, boats and minecarts, end crystals. Mobs
     * aren't: regions don't decide what spawns.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void spawn(EntitySpawnEvent e) {
        Entity spawned = e.getEntity();
        // Every place it names: an item frame's wall as well as the frame, so one can't hang inward across an edge.
        if (placed(spawned)) refuse(e, e, List.copyOf(e.getPlaces()), Flag.BUILD, Optional.of(spawned.blockPosition()));
    }

    /**
     * Removing a placed object outright (/kill on a stand, a mod's remover) is building, as breaking it is. Capturing
     * anything into a block (a mob jar) builds there too; carried off by a player, it goes where they go.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void remove(EntityRemoveEvent e) {
        List<Place> places = new ArrayList<>();
        // A placed object counts everywhere its event says it is: its block, and a frame's or painting's wall.
        if (placed(e.getEntity())) places.addAll(e.getEntityPlaces());
        if (e instanceof EntityCaptureEvent capture) {
            switch (capture.getStorage()) {
                case EntityCaptureEvent.Storage.Block b -> places.add(Place.Spot.at(b.level(), b.pos()));
                case EntityCaptureEvent.Storage.Carried c -> places.add(placeOf(c.carrier()));
                case EntityCaptureEvent.Storage.Item i -> { }
            }
        }
        if (!places.isEmpty()) refuse(e, e, places, Flag.BUILD, Optional.of(e.getEntity().blockPosition()));
    }

    /**
     * Inventories in regions: a menu opened or clicked in, items moved in or out by a hopper, a pipe, a machine.
     * Someone behind it (a player, a machine's owner) needs {@code use} wherever each inventory it reaches stands,
     * even after the menu is open: a click is checked against every inventory behind the menu, since a shift-click can
     * move items into any of them. The world on its own (a hopper nobody owns) is held to
     * {@link Rules#refusingCrossing} both ways, as a piston is. An inventory with no place in a world (linked storage,
     * a plugin's own menu) stands outside every region. So does a player's own inventory, and what they carry in it
     * (a backpack): it goes where they go, so no region's {@code use} covers it, yet a hopper nobody owns can't empty a
     * chest inside into a player outside.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void inventory(InventoryEvent e) {
        if (!(e instanceof Cancellable c) || trusted(e)) return;
        Set<InventoryHolder> holders = new LinkedHashSet<>();
        holders.add(e.getHolder());
        if (e instanceof InventoryChangeEvent change) holders.addAll(change.getHolders());
        if (e instanceof InventoryClickEvent click) {
            holders.add(click.getMenuHolder());
            holders.addAll(click.getSlotHolders());
        }
        if (e instanceof InventoryOpenEvent open) {
            holders.add(open.getMenuHolder());
            holders.addAll(open.getSlotHolders());
        }
        List<Place> places = new ArrayList<>();
        for (InventoryHolder holder : holders) places.addAll(placesOf(holder));
        refuse(e, c, places, Flag.USE,
                e.getHolder() instanceof InventoryHolder.Block b ? Optional.of(b.pos()) : Optional.empty());
    }

    /**
     * Energy, fluids and other resources a mod moves, makes or spends: draining a protected battery, a pipe filling a
     * tank. Held to {@code use}, as inventories are, wherever each holder stands; a player's own (their experience,
     * what they carry) stands outside every region, as their inventory does.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void resource(ResourceEvent e) {
        if (trusted(e)) return;
        List<Place> places = new ArrayList<>();
        List<ResourceHolder> holders = e.getHolders();
        if (holders.isEmpty()) places.addAll(e.getPlaces());
        for (ResourceHolder holder : holders) {
            Optional<UUID> own = switch (holder) {
                case ResourceHolder.Entity en when en.entity() instanceof Player p -> Optional.of(p.getUUID());
                case ResourceHolder.Item i -> playersOwn(i.heldIn());
                default -> Optional.empty();
            };
            if (own.isPresent()) {
                places.add(Place.Nowhere.player(own.get()));
            } else {
                places.addAll(Place.of(holder));
            }
        }
        refuse(e, e, places, Flag.USE, Optional.empty());
    }

    /**
     * An item used, eaten, crafted, changed, traded or picked up: held to {@code use} where what holds it stands, and
     * putting one on (a dispenser dressing an armor stand) to {@code build}. What a player holds, eats or picks up is
     * their own, so it stands outside every region; a loose item a player picks up isn't a region's either. Drops,
     * merges, despawns and switching slots aren't a region's business.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void item(ItemEvent e) {
        if (!(e instanceof Cancellable c) || trusted(e)) return;
        List<Place> places = new ArrayList<>();
        Flag flag = Flag.USE;
        switch (e) {
            case ItemChangeEvent ch -> places.addAll(placesOf(ch.getHolder()));
            case ItemCraftEvent cr -> places.addAll(placesOf(cr.getHolder()));
            case ItemSmeltEvent sm -> places.addAll(placesOf(sm.getHolder()));
            case TradeEvent tr -> places.addAll(placesOf(tr.getTrader()));
            case ItemConsumeEvent co -> places.add(placeOf(co.getConsumer()));
            case ItemUseEvent us -> places.add(placeOf(us.getUser()));
            case EquipEvent eq -> {
                places.add(placeOf(eq.getEntity()));
                flag = Flag.BUILD;
            }
            case ItemPickupEvent pk -> {
                if (playersOwn(pk.getDestination()).isEmpty()) places.add(Place.of(pk.getItemEntity()));
                places.addAll(placesOf(pk.getDestination()));
            }
            default -> {
                return;
            }
        }
        refuse(e, c, places, flag, Optional.empty());
    }

    /**
     * Every other action that can be refused, held to {@code build} wherever it happens: a mod's change to an
     * entity's data, shearing, taming, leashing, a portal or lightning made, chunks force-loaded, a tick accelerator.
     * The handlers above decide their own kinds; moving, aiming and breathing are anyone's, weather and time are a
     * whole world's, a projectile's hit and an explosion about to go off are decided by what they do (damage, the
     * blocks they change), and what happens to a player themselves is no region's, but PvP's.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void action(Event ev) {
        if (!(ev instanceof ActionEvent e) || !(ev instanceof Cancellable c) || decidedElsewhere(ev) || trusted(e)) {
            return;
        }
        if (ev instanceof EntityEvent en && en.getEntity() instanceof Player) return;
        refuse(e, c, List.copyOf(e.getPlaces()), Flag.BUILD, Optional.empty());
    }

    /** Kinds a handler above decides, or that no region decides. */
    private static final List<Class<?>> DECIDED_ELSEWHERE = List.of(BlockEvent.class, ExplosionEvent.class,
            InventoryEvent.class, ResourceEvent.class, ItemEvent.class, EntityMoveEvent.class, EntityTeleportEvent.class,
            EntityMountEvent.class, EntityDismountEvent.class, EntityDamageEvent.class, EntityPotionEffectEvent.class,
            EntityUseEvent.class, ArmorStandManipulateEvent.class, HangingBreakEvent.class, VehicleDamageEvent.class,
            ItemFrameChangeEvent.class, EntitySpawnEvent.class, EntityRemoveEvent.class, EntityVelocityEvent.class,
            EntityTargetEvent.class, EntityAirChangeEvent.class, ProjectileHitEvent.class, ExplosionPrimeEvent.class,
            WeatherChangeEvent.class, TimeSkipEvent.class);

    private static boolean decidedElsewhere(Event e) {
        for (Class<?> kind : DECIDED_ELSEWHERE) if (kind.isInstance(e)) return true;
        return false;
    }

    /** Where an entity stands for regions: outside every one for a player, who goes where they go. */
    private static Place placeOf(Entity entity) {
        return entity instanceof Player p ? Place.Nowhere.player(p.getUUID()) : Place.of(entity);
    }

    /** Where an inventory stands for regions: outside every one for a player's own, or what they carry in it. */
    private static List<Place> placesOf(InventoryHolder holder) {
        Optional<UUID> own = playersOwn(holder);
        return own.isPresent() ? List.of(Place.Nowhere.player(own.get())) : Place.of(holder);
    }

    /** The player whose own inventory this is, or is carried in (a backpack in their pocket, however deep). */
    private static Optional<UUID> playersOwn(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Entity en when en.entity() instanceof Player p -> Optional.of(p.getUUID());
            case InventoryHolder.Item i -> playersOwn(i.outer());
            default -> Optional.empty();
        };
    }

    /**
     * Refuses {@code e} if a region at any of {@code places} forbids {@code flag} to whoever is behind it, or, with
     * nobody behind it, forbids carrying it between them. A place that may be in more than one spot is refused if any
     * spot it may be in refuses.
     */
    private void refuse(ActionEvent e, Cancellable c, List<Place> places, Flag flag, Optional<BlockPos> shown) {
        if (trusted(e)) return;
        List<List<List<Region>>> possible = new ArrayList<>(places.size() + 1);
        for (Place place : places) possible.add(mayBeIn(place));
        // The world on its own carries a change from where the machine or mob doing it stands, too.
        if (worldOnItsOwn(e.getActor())) source(e.getActor()).ifPresent(at -> possible.add(mayBeIn(at)));
        Optional<Region> no = worldOnItsOwn(e.getActor()) ? crossing(possible)
                : Rules.refusingAny(possible.stream().flatMap(List::stream).toList(), flag, e.getActor().actingFor());
        if (no.isEmpty()) return;
        c.setCancelled(true);
        shown.ifPresent(pos -> tell(e, pos, no.get()));
    }

    /** Where a machine or mob acting on its own stands. */
    private static Optional<Place> source(Actor actor) {
        return switch (actor) {
            case Actor.Machine m -> Optional.of(Place.Spot.at(m.level(), m.pos()));
            case Actor.EntityActor a -> Optional.of(Place.of(a.entity()));
            default -> Optional.empty();
        };
    }

    /** Nobody behind it: nature, or a machine or mob nobody owns. */
    private static boolean worldOnItsOwn(Actor actor) {
        if (actor.actingFor().isPresent()) return false;
        return actor instanceof Actor.Nature || actor instanceof Actor.Machine || actor instanceof Actor.EntityActor;
    }

    /**
     * The first region refusing a carry between two of the places, either way, for any spot each may be in. A block
     * never crosses into itself; a place that may be in more than one spot may, since one unknown place can stand for
     * several inventories (every inventory nothing identifies is the same {@code Unknown}).
     */
    private static Optional<Region> crossing(List<List<List<Region>>> places) {
        for (int i = 0; i < places.size(); i++) {
            for (int j = 0; j < places.size(); j++) {
                if (i == j && places.get(i).size() == 1) continue;
                for (List<Region> from : places.get(i)) {
                    for (List<Region> to : places.get(j)) {
                        Optional<Region> no = Rules.refusingCrossing(from, to);
                        if (no.isPresent()) return no;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The sets of regions a place may stand in, each standing for a spot it may be: a block is in exactly the regions
     * there. For a place that covers or may be anywhere in an area (a chunk, a level, one nobody can place): outside
     * every region if some of the area is, and each region alone wherever part of it holds the area and no region of
     * higher priority covers that part. Alone is enough there: only regions of its own priority decide with it, and any
     * of those refusing is its own entry. One with no place in a world is outside every region.
     */
    private List<List<Region>> mayBeIn(Place place) {
        return switch (place) {
            case Place.Spot s when s.isBlock() -> List.of(store.at(s.level(),
                    new BlockPos(s.box().minX(), s.box().minY(), s.box().minZ())));
            case Place.Spot s -> anywhereIn(store.overlapping(s.level(), s.box()), s.box());
            case Place.Level l -> anywhereIn(store.in(l.level()), null);
            case Place.Nowhere n -> List.of(List.of());
            case Place.Unknown u -> switch (u.bound()) {
                case Place.Spot s -> anywhereIn(store.overlapping(s.level(), s.box()), s.box());
                case Place.Level l -> anywhereIn(store.in(l.level()), null);
                case Place.Anywhere a -> {
                    List<List<Region>> out = new ArrayList<>();
                    out.add(List.of());
                    Map<ResourceLocation, List<Region>> byWorld = new HashMap<>();
                    for (Region r : store.all()) byWorld.computeIfAbsent(r.world(), w -> new ArrayList<>()).add(r);
                    for (List<Region> world : byWorld.values()) out.addAll(anywhereIn(world, null));
                    yield out;
                }
            };
        };
    }

    /**
     * {@link #mayBeIn} for an area of one world, given the regions that reach it; {@code area} null for a whole world,
     * which always has room outside every region.
     */
    static List<List<Region>> anywhereIn(List<Region> reached, @Nullable BoundingBox area) {
        List<List<Region>> out = new ArrayList<>(reached.size() + 1);
        if (area == null || !Boxes.covered(area, reached.stream().map(Region::box).toList())) out.add(List.of());
        for (Region r : reached) {
            BoundingBox part = area == null ? r.box() : Boxes.intersection(r.box(), area);
            List<BoundingBox> higher = reached.stream().filter(h -> h.priority() > r.priority()).map(Region::box)
                    .toList();
            if (!Boxes.covered(part, higher)) out.add(List.of(r));
        }
        return out;
    }

    private static boolean placed(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof VehicleEntity
                || entity instanceof EndCrystal;
    }

    /**
     * Refuses a change to {@code target} wherever its event says it happens: its own block and, for a frame or
     * painting, the wall it hangs on, so one facing out from a protected wall stays protected.
     */
    private void entityChange(ActionEvent e, Cancellable c, Entity target, Flag flag) {
        refuse(e, c, List.copyOf(e.getPlaces()), flag, Optional.of(target.blockPosition()));
    }

    /** The region refusing this actor here, unless the actor is trusted. */
    private Optional<Region> refusing(ActionEvent e, ServerLevel level, BlockPos pos, Flag flag) {
        if (trusted(e)) return Optional.empty();
        return Rules.refusing(store.at(level, pos), flag, e.getActor().actingFor());
    }

    /** The server, or a player with /region bypass on. */
    private boolean trusted(ActionEvent e) {
        if (e.getActor() instanceof Actor.Server) return true;
        return e.getActingPlayer().map(bypass::active).orElse(false);
    }

    /** Shows a player acting directly where they were refused; machines and mobs are refused quietly. */
    private void tell(ActionEvent e, BlockPos pos, Region region) {
        if (e.getActor() instanceof Actor.Player p) feedback.refused(p.player(), pos, region);
    }

    /**
     * Where the world's own change comes from: the fluid's source, the spreading block, the fire burning it, the
     * piston. Empty when fire's origin isn't known (lava, spreading flames), which counts as outside every region, so
     * fire that may have come from outside can't burn or catch inside one.
     */
    private static Optional<BlockPos> source(BlockEvent e) {
        if (e instanceof BlockFromToEvent f) return Optional.of(f.getFromPos());
        if (e instanceof BlockSpreadEvent s) return Optional.of(s.getSourcePos());
        if (e instanceof BlockBurnEvent b) return b.getIgnitingPos();
        if (e instanceof BlockIgniteEvent) return Optional.empty();
        return Optional.of(e.getPos());
    }

    /**
     * Every space the event touches, including all a move sweeps through, and, for a block used, every block the use
     * acts on (a bed's or door's other half, a double chest's), so the half outside a region doesn't reach the half in.
     */
    private static Set<BlockPos> touched(BlockEvent e) {
        Set<BlockPos> out = new LinkedHashSet<>();
        out.add(e.getPos());
        if (e instanceof BlockUseEvent) {
            for (Place p : e.getPlaces()) {
                if (p instanceof Place.Spot s && s.level() == e.getLevel()) {
                    BlockPos.betweenClosedStream(s.box()).forEach(pos -> out.add(pos.immutable()));
                }
            }
        }
        for (BlockChange change : e.getBlocks()) out.add(change.getPos());
        if (e instanceof BlockMoveEvent m) {
            out.addAll(m.getSweptSpace());
            for (BlockMoveEvent.Move move : m.getMoves()) {
                out.add(move.from());
                out.add(move.to());
            }
        }
        return out;
    }
}
