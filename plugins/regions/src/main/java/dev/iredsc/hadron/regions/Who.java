package dev.iredsc.hadron.regions;

import dev.iredsc.hadron.api.actor.Actor;
import dev.iredsc.hadron.api.event.Place;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Who a region judges an act as, before knowing the region: a person (a member of one region, everyone to the next),
 * the world on its own with where it sets out from, or a mod nobody named. Picked from the actor by an exhaustive
 * switch, so a new kind of actor fails to compile here until someone decides it.
 */
sealed interface Who {
    /** Whoever answers for it, online or not: a player, or the owner of a machine, fake player or entity. */
    record Person(UUID id) implements Who {
        public Person {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Anyone at all, such as whoever may be aboard a move nobody traced: judged as everyone, in every region. */
    record Anyone() implements Who {}

    /** Someone judged as a member in every region: only {@code /region why <node> members} asks this way. */
    record Member() implements Who {}

    /** Nobody behind it, setting out from every one of {@code home}: never empty, so "inside" is never vacuous. */
    record World(List<Home> home) implements Who {
        public World {
            home = List.copyOf(home);
            if (home.isEmpty()) throw new IllegalArgumentException("the world sets out from somewhere; use Outside");
        }

        /** Whether every place it sets out from is inside {@code region}: then it is that region's own world. */
        boolean homeInside(Region region) {
            for (Home h : home) {
                if (!(h instanceof Home.At at) || !at.world().equals(region.world()) || !contains(region.box(), at.box())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean contains(BoundingBox outer, BoundingBox inner) {
            return outer.minX() <= inner.minX() && outer.minY() <= inner.minY() && outer.minZ() <= inner.minZ()
                    && outer.maxX() >= inner.maxX() && outer.maxY() >= inner.maxY() && outer.maxZ() >= inner.maxZ();
        }
    }

    /** A mod acted and nothing said who. */
    record Unknown() implements Who {}

    /** Where the world on its own sets out from: blocks in a world, or outside every region. */
    sealed interface Home {
        record At(ResourceLocation world, BoundingBox box) implements Home {
            public At {
                Objects.requireNonNull(world, "world");
                Objects.requireNonNull(box, "box");
            }
        }

        /** No place a region can hold: an unseen origin, a whole level, an actor that has gone. */
        record Outside() implements Home {}

        /** A place as a home: a spot (or the spot an unknown place is bound to); anything else is outside. */
        static Home of(Place place) {
            return switch (place) {
                case Place.Spot s -> new At(s.level().dimension().location(), s.box());
                case Place.Unknown u when u.bound() instanceof Place.Spot s ->
                        new At(s.level().dimension().location(), s.box());
                case Place.Unknown u -> new Outside();
                case Place.Level l -> new Outside();
                case Place.Nowhere n -> new Outside();
            };
        }

        static List<Home> of(List<Place> places) {
            List<Home> out = new ArrayList<>();
            for (Place p : places) out.add(of(p));
            return out.isEmpty() ? List.of(new Outside()) : out;
        }
    }

    /** The subject this is in {@code region}: a person is a member there or everyone else. */
    default Subject subjectIn(Region region) {
        return switch (this) {
            case Person p -> region.isMember(p.id()) ? Subject.MEMBERS : Subject.EVERYONE;
            case Anyone a -> Subject.EVERYONE;
            case Member m -> Subject.MEMBERS;
            case World w -> Subject.WORLD;
            case Unknown u -> Subject.UNKNOWN;
        };
    }

    /**
     * Who an actor is judged as; empty for the server, which no region asks. {@code natureHome} is where nature's act
     * sets out from, as its event states it. A machine or fake player nobody owns sets out from where it stands, an
     * entity nobody is behind from where it entered the world ({@code Actors.origin}), wherever it is now; a
     * remembered actor nobody is behind, which has no place, from outside every region.
     */
    static Optional<Who> of(Actor actor, Supplier<List<Place>> natureHome) {
        Optional<UUID> owner = actor.actingFor();
        return switch (actor) {
            case Actor.Server s -> Optional.empty();
            case Actor.Player p -> Optional.of(new Person(p.player().getUUID()));
            case Actor.Unknown u -> Optional.of(new Unknown());
            case Actor.Nature n -> Optional.of(new World(Home.of(natureHome.get())));
            case Actor.FakePlayer f -> Optional.of(owner.<Who>map(Person::new).orElseGet(() -> new World(List.of(
                    Home.of(Place.of(f.fake()))))));
            case Actor.Machine m -> Optional.of(owner.<Who>map(Person::new).orElseGet(() -> new World(List.of(
                    Home.of(Place.Spot.at(m.level(), m.pos()))))));
            case Actor.EntityActor e -> Optional.of(owner.<Who>map(Person::new).orElseGet(() -> new World(List.of(
                    Home.of(e.origin())))));
            case Actor.Remembered r -> Optional.of(owner.<Who>map(Person::new).orElseGet(() -> new World(List.of(
                    new Home.Outside()))));
        };
    }
}
