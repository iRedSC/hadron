package dev.iredsc.hadron.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** How regions decide a node for a subject, without a server: specificity, ties, abstaining, priority, home. */
class ResolveTest {
    private static final ResourceLocation WORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("the_nether");
    private static final BoundingBox SPAWN_BOX = new BoundingBox(-50, 0, -50, 50, 100, 50);
    private final UUID alice = new UUID(0, 1);
    private final Who.Person bob = new Who.Person(new UUID(0, 2));

    private static final Node HURT_ANIMAL = Node.of(Action.ENTITY_HURT, Kind.ANIMAL);
    private static final Node HURT_MISC = Node.of(Action.ENTITY_HURT, Kind.MISC);
    private static final Node HURT_MONSTER = Node.of(Action.ENTITY_HURT, Kind.MONSTER);
    private static final Node HURT_PLAYER = Node.of(Action.ENTITY_HURT, Kind.PLAYER);
    private static final Node BREAK = Node.of(Action.BLOCK_BREAK);

    private static Region spawn() {
        return Region.of("spawn", WORLD, SPAWN_BOX);
    }

    private static Pattern p(String text) {
        try {
            return Pattern.parse(text);
        } catch (Pattern.Invalid e) {
            throw new AssertionError(e.getMessage());
        }
    }

    private static Region with(Region r, Subject s, Rules.Verdict v, String... patterns) {
        Rules rules = r.rules();
        for (String text : patterns) rules = rules.with(s, p(text), Optional.of(v));
        return r.withRules(rules);
    }

    private static boolean refused(List<Region> here, Who who, Node node) {
        return Resolve.refusing(here, who, node).isPresent();
    }

    @Test
    void aFreshRegionKeepsEveryoneFromBuildingAndUsingButNotFromFightingMonsters() {
        List<Region> here = List.of(spawn());
        assertTrue(refused(here, bob, BREAK));
        assertTrue(refused(here, bob, Node.of(Action.INVENTORY_OPEN)));
        assertTrue(refused(here, bob, HURT_ANIMAL), "a cow is build's");
        assertTrue(!refused(here, bob, HURT_MONSTER), "spawn abstains on a monster, so nothing forbids it");
        assertTrue(!refused(here, bob, Node.of(Action.ENTER_MOVE)), "entry is open in a fresh region");
        assertTrue(!refused(here, bob, HURT_PLAYER), "pvp is open in a fresh region");
    }

    @Test
    void membersAndOwnersAreAllowedWhatNoRuleNames() {
        assertTrue(!refused(List.of(spawn().withMember(alice)), new Who.Person(alice), BREAK));
        assertTrue(!refused(List.of(spawn().withOwner(alice)), new Who.Person(alice), BREAK), "owners are members");
    }

    @Test
    void theMostSpecificRuleWinsAndAnythingNamedBeatsAFlag() {
        Region farm = with(spawn(), Subject.EVERYONE, Rules.Verdict.ALLOW, "entity.hurt.animal");
        assertTrue(!refused(List.of(farm), bob, HURT_ANIMAL), "entity.hurt.animal (3) beats build (1)");
        assertTrue(refused(List.of(farm), bob, HURT_MISC), "a villager is still build's");
        Region doors = with(spawn(), Subject.EVERYONE, Rules.Verdict.ALLOW, "block");
        assertTrue(refused(List.of(doors), bob, BREAK), "block (1) ties build (1), and deny wins a tie");
        Region named = with(spawn(), Subject.EVERYONE, Rules.Verdict.ALLOW, "block.break");
        assertTrue(!refused(List.of(named), bob, BREAK), "block.break (2) beats build (1)");
    }

    @Test
    void atEqualSpecificityDenyWins() {
        Region r = with(with(Region.of("r", WORLD, SPAWN_BOX).withRules(Rules.none()), Subject.EVERYONE,
                Rules.Verdict.ALLOW, "entity.*.animal"), Subject.EVERYONE, Rules.Verdict.DENY, "entity.hurt");
        assertTrue(refused(List.of(r), bob, HURT_ANIMAL), "entity.*.animal (2) ties entity.hurt (2)");
        assertTrue(!refused(List.of(r), bob, Node.of(Action.ENTITY_SHEAR, Kind.ANIMAL)));
        Region narrower = with(r, Subject.EVERYONE, Rules.Verdict.ALLOW, "entity.hurt.animal");
        assertTrue(!refused(List.of(narrower), bob, HURT_ANIMAL), "three named segments beat two");
    }

    @Test
    void aHigherRegionDecidesAndOneThatAbstainsPassesItDown() {
        Region plot = Region.of("plot-1", WORLD, new BoundingBox(10, 60, 10, 25, 90, 25)).withPriority(10)
                .withOwner(alice);
        Region spawn = spawn();
        List<Region> here = List.of(plot, spawn);
        assertTrue(!refused(here, new Who.Person(alice), BREAK), "the plot's members are allowed before spawn asks");
        assertEquals("plot-1", Resolve.refusing(here, bob, BREAK).orElseThrow().region().id());
        assertTrue(!refused(here, bob, HURT_MONSTER), "both abstain, so a zombie can be fought");
        Region strict = with(spawn, Subject.EVERYONE, Rules.Verdict.DENY, "entity.hurt.monster");
        assertEquals("spawn", Resolve.refusing(List.of(plot, strict), bob, HURT_MONSTER).orElseThrow().region().id(),
                "the plot abstains, so spawn's rule decides");
    }

    @Test
    void atOnePriorityAnyDenyRefusesAndOneAllowIsEnough() {
        Region open = Region.of("open", WORLD, SPAWN_BOX).withRules(Rules.none());
        Region mine = Region.of("mine", WORLD, SPAWN_BOX).withMember(bob.id());
        assertTrue(!refused(List.of(open, mine), bob, BREAK), "mine allows, open abstains");
        assertTrue(refused(List.of(mine, spawn()), bob, BREAK), "spawn refuses at the same priority");
    }

    @Test
    void theWorldActsInsideItsOwnRegionButNotAcrossAnEdge() {
        Who inside = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(1, 1, 1)))));
        Who outside = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(99, 1, 99)))));
        Who elsewhere = new Who.World(List.of(new Who.Home.At(NETHER, new BoundingBox(new BlockPos(1, 1, 1)))));
        Who straddling = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(1, 1, 1))),
                new Who.Home.At(WORLD, new BoundingBox(new BlockPos(99, 1, 99)))));
        Who gone = new Who.World(List.of(new Who.Home.Outside()));
        Node move = Node.of(Action.INVENTORY_MOVE);
        List<Region> here = List.of(spawn());
        assertTrue(!refused(here, inside, move), "a hopper inside works");
        Resolve.Decision no = Resolve.refusing(here, outside, move).orElseThrow();
        assertEquals("spawn · world · inventory.move · from outside", no.line());
        assertTrue(refused(here, elsewhere, move), "a home in another world is outside");
        assertTrue(refused(here, straddling, move), "every home has to be inside");
        assertTrue(refused(here, gone, move), "an actor with no place is outside every region");
        Region letsWaterIn = with(spawn(), Subject.WORLD, Rules.Verdict.ALLOW, "block.flow");
        assertTrue(!refused(List.of(letsWaterIn), outside, Node.of(Action.BLOCK_FLOW)), "a world rule overrides");
        Region noMonsters = with(spawn(), Subject.WORLD, Rules.Verdict.DENY, "entity.spawn.monster");
        assertTrue(refused(List.of(noMonsters), inside, Node.of(Action.ENTITY_SPAWN, Kind.MONSTER)),
                "a world rule overrides inside too");
    }

    @Test
    void aSkeletonOutsideCantShootACowInsideButCanShootAPlayer() {
        Who skeleton = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(80, 64, 0)))));
        assertTrue(refused(List.of(spawn()), skeleton, HURT_ANIMAL));
        assertTrue(!refused(List.of(spawn()), skeleton, HURT_PLAYER), "fresh regions let the world act on players");
        Who home = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(0, 64, 0)))));
        assertTrue(!refused(List.of(spawn()), home, HURT_ANIMAL), "one that spawned inside is the region's own");
    }

    @Test
    void anUnknownActorIsRefusedEvenWhatNoFlagHoldsUntilARuleLetsIt() {
        Who unknown = new Who.Unknown();
        assertTrue(refused(List.of(spawn()), unknown, Node.of(Action.ENTITY_PUSH, Kind.MONSTER)));
        Region farm = with(spawn(), Subject.UNKNOWN, Rules.Verdict.ALLOW, "inventory.move");
        assertTrue(!refused(List.of(farm), unknown, Node.of(Action.INVENTORY_MOVE)));
        assertTrue(refused(List.of(farm), unknown, BREAK));
    }

    @Test
    void aRegionAlreadyEnteredNeverRefusesEntry() {
        Region vault = with(spawn(), Subject.EVERYONE, Rules.Verdict.DENY, "entry");
        Node walk = Node.of(Action.ENTER_MOVE);
        assertTrue(refused(List.of(vault), bob, walk));
        assertTrue(Resolve.refusing(List.of(vault), bob, walk, List.of(vault)).isEmpty());
    }

    @Test
    void aFlagIsItsBundleForEveryoneAndPvpBindsMembersToo() {
        Region safe = spawn().withMember(alice);
        safe = safe.withRules(safe.rules().withFlag(Bundle.PVP, Optional.of(Rules.Verdict.DENY)));
        assertTrue(refused(List.of(safe), bob, HURT_PLAYER));
        assertTrue(refused(List.of(safe), new Who.Person(alice), HURT_PLAYER), "a safe zone binds members");
        assertTrue(refused(List.of(safe), bob, Node.of(Action.ENTITY_EFFECT, Kind.PLAYER)), "poison is pvp's");
        assertTrue(!refused(List.of(safe), bob, HURT_MONSTER), "no pvp leaves monsters to be fought");
        Region cleared = safe.withRules(safe.rules().withFlag(Bundle.PVP, Optional.empty()));
        assertTrue(!refused(List.of(cleared), new Who.Person(alice), HURT_PLAYER));
        Region open = spawn().withRules(spawn().rules().withFlag(Bundle.BUILD, Optional.of(Rules.Verdict.ALLOW)));
        assertTrue(!refused(List.of(open), bob, BREAK));
        assertEquals(List.of(Pattern.of(Bundle.BUILD)), open.rules().of(Subject.EVERYONE).allow());
        assertEquals(List.of(Pattern.of(Bundle.USE)), open.rules().of(Subject.EVERYONE).deny(),
                "allowing build took it out of deny and left use alone");
    }

    @Test
    void aRefusalSaysTheRegionSubjectNodeAndTheFlagThatHeldIt() {
        Resolve.Decision no = Resolve.refusing(List.of(spawn()), bob, HURT_ANIMAL).orElseThrow();
        assertEquals("spawn · everyone · entity.hurt.animal (build)", no.line());
        Region named = with(spawn(), Subject.EVERYONE, Rules.Verdict.DENY, "entity.hurt.animal");
        assertEquals("spawn · everyone · entity.hurt.animal", Resolve.refusing(List.of(named), bob, HURT_ANIMAL)
                .orElseThrow().line());
    }

    @Test
    void ownersAndMembersStayApart() {
        Region r = spawn().withOwner(alice).withMember(alice);
        assertTrue(r.members().contains(alice));
        assertTrue(!r.owners().contains(alice));
        assertTrue(r.withOwner(alice).members().isEmpty());
        assertTrue(!r.without(alice).isMember(alice));
    }

    @Test
    void theWorldFollowsTheHighestPriorityToo() {
        Who outside = new Who.World(List.of(new Who.Home.At(WORLD, new BoundingBox(new BlockPos(99, 1, 99)))));
        Region town = Region.of("town", WORLD, SPAWN_BOX);
        Region plot = with(Region.of("plot", WORLD, SPAWN_BOX).withPriority(1), Subject.WORLD, Rules.Verdict.ALLOW,
                "block.flow");
        Node flow = Node.of(Action.BLOCK_FLOW);
        assertTrue(!refused(List.of(town, plot), outside, flow), "the plot lets water in, before the town is asked");
        assertEquals("town", Resolve.refusing(List.of(town), outside, flow).orElseThrow().region().id());
    }
}
