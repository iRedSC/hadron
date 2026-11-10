package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.block.BlockChange;
import dev.iredsc.hadron.api.event.block.BlockDropItemEvent;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.block.BlockFromToEvent;
import dev.iredsc.hadron.api.event.block.BlockMoveEvent;
import dev.iredsc.hadron.api.event.block.BlockSpreadEvent;
import dev.iredsc.hadron.api.event.block.BlockUseEvent;
import dev.iredsc.hadron.api.event.entity.EntityDamageEvent;
import dev.iredsc.hadron.api.event.entity.EntityMountEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryMoveItemEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryHolder;
import net.minecraft.world.effect.MobEffectCategory;
import java.util.Map;
import java.util.HashMap;
import dev.iredsc.hadron.api.event.player.PlayerQuitEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryOpenEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryCloseEvent;
import dev.iredsc.hadron.api.event.inventory.InventoryClickEvent;
import dev.iredsc.hadron.api.event.entity.EntityPotionEffectEvent;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
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
import dev.iredsc.hadron.api.plugin.JavaPlugin;
import java.util.ArrayList;
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
    /** What each player has open, for their clicks. */
    private final Map<UUID, InventoryHolder> openMenus = new HashMap<>();

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
            List<Region> from = store.at(level, source(e));
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
        // Both sides count: nobody fights out of a safe zone or into one.
        ServerLevel level = hurt.serverLevel();
        List<BlockPos> sides = new ArrayList<>(3);
        sides.add(hurt.blockPosition());
        e.getActingPlayer().filter(a -> a.level() == level).ifPresent(a -> sides.add(a.blockPosition()));
        damager.filter(d -> d.level() == level).ifPresent(d -> sides.add(d.blockPosition()));
        // Where the attack comes from: a turret block, a fake player, a thrown potion, a mob someone controls.
        switch (e.getActor()) {
            case Actor.Machine m when m.level() == level -> sides.add(m.pos());
            case Actor.FakePlayer f when f.fake().level() == level -> sides.add(f.fake().blockPosition());
            case Actor.EntityActor a when a.entity().level() == level -> sides.add(a.entity().blockPosition());
            default -> { }
        }
        for (BlockPos side : sides) {
            Optional<Region> no = Rules.refusing(store.at(level, side), Flag.PVP, who);
            if (no.isPresent()) return no;
        }
        return Optional.empty();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void use(EntityUseEvent e) {
        Entity target = e.getEntity();
        entityChange(e, e, target, target instanceof ArmorStand || target instanceof HangingEntity ? Flag.BUILD
                : Flag.USE);
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
        if (placed(spawned)) entityChange(e, e, spawned, Flag.BUILD);
    }

    /** Removing a placed object outright (/kill on a stand, a mod's remover) is building, as breaking it is. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void remove(EntityRemoveEvent e) {
        if (placed(e.getEntity())) entityChange(e, e, e.getEntity(), Flag.BUILD);
    }

    /**
     * Inventories in regions: a menu opened or clicked in, items moved in or out by a hopper, a pipe, a machine.
     * Someone behind it (a player, a machine's owner) needs {@code use} wherever each inventory stands, even after
     * the menu is open. The world on its own (a hopper nobody owns) is held to {@link Rules#refusingCrossing} both
     * ways, as a piston is. An inventory with no place in a world (linked storage, one nothing identified) stands
     * outside every region. A player's own inventory is never a region's.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    private void inventory(InventoryEvent e) {
        if (!(e instanceof Cancellable c) || trusted(e)) return;
        Set<InventoryHolder> holders = new LinkedHashSet<>();
        holders.add(e.getHolder().place());
        if (e instanceof InventoryChangeEvent change) {
            for (InventoryChangeEvent.Change ch : change.getChanges()) holders.add(ch.holder().place());
        }
        if (e instanceof InventoryMoveItemEvent move) {
            holders.add(move.getSource().place());
            holders.add(move.getDestination().place());
        }
        // A click names the slot clicked, which may be the player's own, yet moves items to and from the whole menu.
        if (e instanceof InventoryClickEvent click) {
            InventoryHolder menu = openMenus.get(click.getViewer().getUUID());
            if (menu != null) holders.add(menu);
        }
        List<List<Region>> places = new ArrayList<>();
        for (InventoryHolder holder : holders) places.addAll(regionsAt(holder));
        Optional<Region> no = worldOnItsOwn(e.getActor()) ? crossing(places) : using(places, e.getActor().actingFor());
        if (no.isEmpty()) return;
        c.setCancelled(true);
        if (e.getActor() instanceof Actor.Player a && e.getHolder().place() instanceof InventoryHolder.Block b) {
            tell(e, b.pos(), no.get());
        }
    }

    /** Remembers what each player has open, so their clicks are checked against it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    private void opened(InventoryOpenEvent e) {
        openMenus.put(e.getViewer().getUUID(), e.getHolder().place());
    }

    @EventHandler
    private void closed(InventoryCloseEvent e) {
        openMenus.remove(e.getViewer().getUUID());
    }

    @EventHandler
    private void quit(PlayerQuitEvent e) {
        openMenus.remove(e.getPlayer().getUUID());
    }

    /** Nobody behind it: nature, or a machine or mob nobody owns. */
    private static boolean worldOnItsOwn(Actor actor) {
        if (actor.actingFor().isPresent()) return false;
        return actor instanceof Actor.Nature || actor instanceof Actor.Machine || actor instanceof Actor.EntityActor;
    }

    private static Optional<Region> crossing(List<List<Region>> places) {
        for (List<Region> from : places) {
            for (List<Region> to : places) {
                Optional<Region> no = Rules.refusingCrossing(from, to);
                if (no.isPresent()) return no;
            }
        }
        return Optional.empty();
    }

    private static Optional<Region> using(List<List<Region>> places, Optional<UUID> who) {
        for (List<Region> here : places) {
            Optional<Region> no = Rules.refusing(here, Flag.USE, who);
            if (no.isPresent()) return no;
        }
        return Optional.empty();
    }

    /**
     * The regions at each spot an inventory stands: both halves of a double chest, an entity's block; outside every
     * region for one with no place in a world; nothing for a player's own.
     */
    private List<List<Region>> regionsAt(InventoryHolder holder) {
        return switch (holder) {
            case InventoryHolder.Block b -> {
                List<List<Region>> spots = new ArrayList<>(2);
                spots.add(store.at(b.level(), b.pos()));
                BlockState state = b.level().getBlockState(b.pos());
                if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    spots.add(store.at(b.level(), b.pos().relative(ChestBlock.getConnectedDirection(state))));
                }
                yield spots;
            }
            case InventoryHolder.Entity en when en.entity() instanceof Player -> List.of();
            case InventoryHolder.Entity en when en.entity().level() instanceof ServerLevel level ->
                    List.of(store.at(level, en.entity().blockPosition()));
            default -> List.of(List.of());
        };
    }

    private static boolean placed(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand || entity instanceof VehicleEntity
                || entity instanceof EndCrystal;
    }

        private void entityChange(ActionEvent e, Cancellable c, Entity target, Flag flag) {
        if (!(target.level() instanceof ServerLevel level)) return;
        BlockPos pos = target.blockPosition();
        Optional<Region> no = e.getActor() instanceof Actor.Nature ? Optional.empty()
                : refusing(e, level, pos, flag);
        if (no.isEmpty()) return;
        c.setCancelled(true);
        tell(e, pos, no.get());
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

    /** Where the world's own change comes from: the fluid's source, the spreading block, the piston. */
    private static BlockPos source(BlockEvent e) {
        if (e instanceof BlockFromToEvent f) return f.getFromPos();
        if (e instanceof BlockSpreadEvent s) return s.getSourcePos();
        return e.getPos();
    }

    /** Every space the event touches, including all a move sweeps through. */
    private static Set<BlockPos> touched(BlockEvent e) {
        Set<BlockPos> out = new LinkedHashSet<>();
        out.add(e.getPos());
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
