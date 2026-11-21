package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.ActionEvent;
import dev.iredsc.hadron.api.event.Cancellable;
import dev.iredsc.hadron.api.event.Carried;
import dev.iredsc.hadron.api.event.Event;
import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.EventPriority;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.Place;
import dev.iredsc.hadron.api.event.block.BlockEvent;
import dev.iredsc.hadron.api.event.entity.EntityEvent;
import dev.iredsc.hadron.api.event.player.PlayerEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

/**
 * Refuses what regions forbid, through one listener: {@link Nodes} turns each event into the nodes it asks, at the
 * places its event states, and {@link Resolve} answers each from the regions there, for whoever {@link Who} says is
 * behind it. So it covers every mod whose bridge is installed, and an act nothing maps fails closed as
 * {@link Action#OTHER}. The server, and a player with {@code /region bypass} on, are never asked.
 */
final class Protection implements Listener {
    private final RegionStore store;
    private final Feedback feedback;
    private final Bypass bypass;
    private final Nodes nodes = new Nodes();

    Protection(RegionStore store, Feedback feedback, Bypass bypass) {
        this.store = store;
        this.feedback = feedback;
        this.bypass = bypass;
    }

    /**
     * At the last priority that may still change an event, so it judges what the event will do once other plugins have
     * redirected a teleport, retargeted a mob or put a block back in a blast's list. Only {@code MONITOR}, which must
     * not change it, and another plugin's {@code HIGHEST} registered later come after.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void on(Event event) {
        if (!(event instanceof Cancellable c) || store.all().isEmpty()) return;
        List<Ask> asks = nodes.asks(event);
        if (asks.isEmpty()) return;
        Actor actor = actorOf(event);
        for (Ask ask : asks) {
            boolean refused = switch (ask) {
                case Ask.Act a -> act(event, actor, a);
                case Ask.Spare s -> {
                    spare(event, actor, s);
                    yield false;
                }
                case Ask.Enter en -> enter(actor, en);
            };
            if (refused) {
                c.setCancelled(true);
                return;
            }
        }
    }

    /** Who did it: the event's actor, or for a player's own event the player. */
    private static Actor actorOf(Event event) {
        return switch (event) {
            case ActionEvent a -> a.getActor();
            case PlayerEvent p -> new Actor.Player(p.getPlayer());
            default -> Actor.Unknown.INSTANCE;
        };
    }

    /** The online player acting for it, the one {@code /region bypass} lets through. */
    private static Optional<ServerPlayer> actingPlayer(Event event) {
        return switch (event) {
            case ActionEvent a -> a.getActingPlayer();
            case PlayerEvent p -> Optional.of(p.getPlayer());
            default -> Optional.empty();
        };
    }

    private Optional<Who> who(Event event, Actor actor, List<Place> natureHome) {
        if (actingPlayer(event).map(bypass::active).orElse(false)) return Optional.empty();
        return Who.of(actor, () -> natureHome);
    }

    /**
     * Whether a region at any place the act is decided at refuses it ({@link Ask.Act#decidedAt}); shows who is told
     * where.
     */
    private boolean act(Event event, Actor actor, Ask.Act a) {
        List<Place> home = a.home().orElseGet(() -> event instanceof BlockEvent b
                ? List.of(Place.Spot.at(b.getLevel(), b.getPos())) : a.places());
        Optional<Who> who = who(event, actor, home);
        if (who.isEmpty()) return false;
        Predicate<Region> answers = Resolve.answers(who.get(), a.node(), List.of());
        for (Place place : a.decidedAt()) {
            for (List<Region> here : mayBeIn(place, answers)) {
                Optional<Resolve.Decision> no = Resolve.refusing(here, who.get(), a.node());
                if (no.isEmpty()) continue;
                tell(actor, no.get(), place, event);
                return true;
            }
        }
        return false;
    }

    /** Spares each block a region keeps; the rest of the blast goes off. */
    private void spare(Event event, Actor actor, Ask.Spare s) {
        Optional<Who> who = who(event, actor, s.home());
        if (who.isEmpty()) return;
        s.blocks().removeIf(pos -> {
            Optional<Resolve.Decision> no = Resolve.refusing(store.at(s.level(), pos), who.get(), s.node());
            no.ifPresent(d -> feedback.watched(s.level(), pos, d));
            return no.isPresent();
        });
    }

    /**
     * Whether a player it moves may not enter where they end up, asked as that player: each one moved or carried,
     * from where that one sets out to where it ends up, and from where the carrier sets out (or with none, where that
     * one does) to where the carrier ends up and to what the move crosses on the way. Only regions a player is not already in can refuse, so anyone inside can walk
     * out. A move whose riders nobody can name is asked as anyone, wherever its bound reaches. Everyone going is shown
     * where they were refused.
     */
    private boolean enter(Actor actor, Ask.Enter en) {
        if (actor instanceof Actor.Server) return false;
        Node node = en.node();
        List<Carried.Ride> going = new ArrayList<>(en.moved());
        Optional<Resolve.Decision> no = Optional.empty();
        switch (en.carried()) {
            case Carried.Known k -> going.addAll(k.rides());
            case Carried.Unknown u -> {
                for (Place within : u.places()) {
                    if (no.isEmpty()) no = refusingEntry(List.of(List.of()), within, new Who.Anyone(), node);
                }
            }
        }
        going.removeIf(r -> !(r.entity() instanceof ServerPlayer p) || bypass.active(p));
        Optional<Carried.Ride> carrier = en.carrier();
        for (Carried.Ride r : going) {
            Who who = new Who.Person(r.entity().getUUID());
            if (no.isEmpty()) no = refusingEntry(mayBeIn(r.from(), EVERY), r.to(), who, node);
            if (no.isEmpty() && carrier.isPresent() && r != carrier.get()) {
                no = refusingEntry(mayBeIn(carrier.get().from(), EVERY), carrier.get().to(), who, node);
            }
            Place setOut = carrier.map(Carried.Ride::from).orElse(r.from());
            for (Place across : en.crossed()) {
                if (no.isEmpty()) no = refusingEntry(mayBeIn(setOut, EVERY), across, who, node);
            }
        }
        if (no.isEmpty()) return false;
        for (Carried.Ride r : going) {
            if (r.entity() instanceof ServerPlayer p) {
                BlockPos at = r.to() instanceof Place.Spot s ? s.box().getCenter() : p.blockPosition();
                feedback.refused(p, at, no.get());
                feedback.watched(p.serverLevel(), at, no.get());
            }
        }
        return true;
    }

    /** The decision refusing {@code who} entry to {@code to} from regions they may be leaving, wherever it may be. */
    private Optional<Resolve.Decision> refusingEntry(List<List<Region>> from, Place to, Who who, Node node) {
        for (List<Region> left : from) {
            for (List<Region> entered : mayBeIn(to, Resolve.answers(who, node, left))) {
                if (left.containsAll(entered)) continue;
                Optional<Resolve.Decision> no = Resolve.refusing(entered, who, node, left);
                if (no.isPresent()) return no;
            }
        }
        return Optional.empty();
    }

    /**
     * Marks a refusal where it happened: to a player acting directly, and to whoever watches the region. Machines and
     * mobs are refused quietly but for watchers.
     */
    private void tell(Actor actor, Resolve.Decision no, Place place, Event event) {
        ServerLevel level = null;
        BlockPos at = null;
        if (place instanceof Place.Spot s) {
            level = s.level();
            at = s.isBlock() ? new BlockPos(s.box().minX(), s.box().minY(), s.box().minZ()) : s.box().getCenter();
        } else if (event instanceof EntityEvent e) {
            level = e.getLevel();
            at = e.getBlockPos();
        } else if (event instanceof BlockEvent b) {
            level = b.getLevel();
            at = b.getPos();
        }
        if (actor instanceof Actor.Player p) {
            feedback.refused(p.player(), at != null ? at : p.player().blockPosition(), no);
        }
        if (level != null && at != null) feedback.watched(level, at, no);
    }

    /**
     * Where a player may set out from, as the regions they may already be in: fewest regions is the strictest, since a
     * region they are in never refuses them, so each region alone is enough.
     */
    private static final Predicate<Region> EVERY = r -> true;

    /**
     * The sets of regions a place may stand in, each standing for a spot it may be: a block is in exactly the regions
     * there. For a place that covers or may be anywhere in an area (a chunk, a level, one nobody can place): outside
     * every region if some of the area is, and each region alone wherever part of it holds the area and no region of
     * higher priority that {@code answers} covers that part. Alone is enough there: a higher region covering it there
     * abstains, only regions of its own priority decide with it, and any of those refusing is its own entry. One with
     * no place in a world is outside every region.
     */
    private List<List<Region>> mayBeIn(Place place, Predicate<Region> answers) {
        return switch (place) {
            case Place.Spot s when s.isBlock() -> List.of(store.at(s.level(),
                    new BlockPos(s.box().minX(), s.box().minY(), s.box().minZ())));
            case Place.Spot s -> anywhereIn(store.overlapping(s.level(), s.box()), s.box(), answers);
            case Place.Level l -> anywhereIn(store.in(l.level()), null, answers);
            case Place.Nowhere n -> List.of(List.of());
            case Place.Unknown u -> switch (u.bound()) {
                case Place.Spot s -> anywhereIn(store.overlapping(s.level(), s.box()), s.box(), answers);
                case Place.Level l -> anywhereIn(store.in(l.level()), null, answers);
                case Place.Anywhere a -> {
                    List<List<Region>> out = new ArrayList<>();
                    out.add(List.of());
                    Map<ResourceLocation, List<Region>> byWorld = new HashMap<>();
                    for (Region r : store.all()) byWorld.computeIfAbsent(r.world(), w -> new ArrayList<>()).add(r);
                    for (List<Region> world : byWorld.values()) out.addAll(anywhereIn(world, null, answers));
                    yield out;
                }
            };
        };
    }

    /**
     * {@link #mayBeIn} for an area of one world, given the regions that reach it; {@code area} null for a whole world,
     * which always has room outside every region. Only a region that {@code answers} hides one of lower priority.
     */
    static List<List<Region>> anywhereIn(List<Region> reached, @Nullable BoundingBox area, Predicate<Region> answers) {
        List<List<Region>> out = new ArrayList<>(reached.size() + 1);
        if (area == null || !Boxes.covered(area, reached.stream().map(Region::box).toList())) out.add(List.of());
        for (Region r : reached) {
            BoundingBox part = area == null ? r.box() : Boxes.intersection(r.box(), area);
            List<BoundingBox> higher = reached.stream().filter(h -> h.priority() > r.priority() && answers.test(h))
                    .map(Region::box)
                    .toList();
            if (!Boxes.covered(part, higher)) out.add(List.of(r));
        }
        return out;
    }
}
