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
import dev.iredsc.hadron.api.event.entity.EntityMoveEvent;
import dev.iredsc.hadron.api.event.entity.EntityUseEvent;
import dev.iredsc.hadron.api.event.entity.HangingBreakEvent;
import dev.iredsc.hadron.api.event.entity.ItemFrameChangeEvent;
import dev.iredsc.hadron.api.event.entity.EntitySpawnEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent;
import dev.iredsc.hadron.api.event.entity.EntityTeleportEvent.TeleportCause;
import dev.iredsc.hadron.api.event.world.ExplosionEvent;
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

/**
 * Refuses what regions forbid, through Hadron's events, so it covers every mod whose bridge is installed and treats
 * every mod without one as an outsider. Who is asked is the actor's {@link Actor#actingFor()}: a player, a machine's
 * owner, a drone's operator. The server is always let through; the world on its own is held to
 * {@link Rules#refusingCrossing}.
 */
final class Protection implements Listener {
    private final RegionStore store;
    private final Feedback feedback;
    private final Bypass bypass;

    Protection(RegionStore store, Feedback feedback, Bypass bypass) {
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
        if (e.getActor() instanceof Actor.Server) return;
        Set<ServerPlayer> going = new LinkedHashSet<>();
        if (e.getEntity() instanceof ServerPlayer p) going.add(p);
        for (Entity rider : e.getPassengers()) {
            if (rider instanceof ServerPlayer p) going.add(p);
            going.addAll(riders(rider));
        }
        going.removeIf(bypass::active);
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
        // Whoever answers for it, online or not: a turret keeps its owner while they're away.
        Optional<UUID> who = e.getActor().actingFor().filter(id -> !id.equals(hurt.getUUID()));
        if (who.isEmpty()) return;
        // Both sides count: nobody fights out of a safe zone or into one.
        ServerLevel level = e.getLevel();
        List<BlockPos> sides = new ArrayList<>(3);
        sides.add(hurt.blockPosition());
        e.getActingPlayer().filter(a -> a.level() == level).ifPresent(a -> sides.add(a.blockPosition()));
        e.getDamager().filter(d -> d.level() == level).ifPresent(d -> sides.add(d.blockPosition()));
        for (BlockPos side : sides) {
            Optional<Region> no = Rules.refusing(store.at(level, side), Flag.PVP, who);
            if (no.isPresent()) {
                e.setCancelled(true);
                tell(e, hurt.blockPosition(), no.get());
                return;
            }
        }
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
        Entity placed = e.getEntity();
        if (placed instanceof HangingEntity || placed instanceof ArmorStand || placed instanceof VehicleEntity
                || placed instanceof EndCrystal) {
            entityChange(e, e, placed, Flag.BUILD);
        }
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
