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

/** Who a region lets do what, decided without a server. */
class RulesTest {
    private static final ResourceLocation WORLD = ResourceLocation.withDefaultNamespace("overworld");
    private final UUID alice = new UUID(0, 1);
    private final UUID bob = new UUID(0, 2);

    private static Region region(String id) {
        return Region.of(id, WORLD, new BoundingBox(new BlockPos(0, 0, 0)));
    }

    @Test
    void outsidersCantBuildOrUseButCanWalkInAndFight() {
        List<Region> spawn = List.of(region("spawn"));
        Optional<UUID> who = Optional.of(bob);
        assertTrue(Rules.refusing(spawn, Flag.BUILD, who).isPresent());
        assertTrue(Rules.refusing(spawn, Flag.USE, who).isPresent());
        assertTrue(Rules.refusing(spawn, Flag.ENTRY, who).isEmpty());
        assertTrue(Rules.refusing(spawn, Flag.PVP, who).isEmpty());
    }

    @Test
    void membersAndOwnersBuild() {
        assertTrue(Rules.refusing(List.of(region("a").withMember(alice)), Flag.BUILD, Optional.of(alice)).isEmpty());
        assertTrue(Rules.refusing(List.of(region("a").withOwner(alice)), Flag.BUILD, Optional.of(alice)).isEmpty());
    }

    @Test
    void nobodyBehindItIsAnOutsider() {
        assertTrue(Rules.refusing(List.of(region("a").withOwner(alice)), Flag.BUILD, Optional.empty()).isPresent());
    }

    @Test
    void anAllowFlagLetsOutsidersIn() {
        assertTrue(Rules.refusing(List.of(region("a").withFlag(Flag.USE, true)), Flag.USE, Optional.of(bob))
                .isEmpty());
    }

    @Test
    void pvpDenyBindsMembersToo() {
        Region safe = region("safe").withMember(alice).withFlag(Flag.PVP, false);
        assertTrue(Rules.refusing(List.of(safe), Flag.PVP, Optional.of(alice)).isPresent());
    }

    @Test
    void theHighestPriorityDecides() {
        Region town = region("town");
        Region plot = region("plot").withPriority(1).withMember(bob);
        assertTrue(Rules.refusing(List.of(town, plot), Flag.BUILD, Optional.of(bob)).isEmpty(),
                "bob builds on his plot inside the town");
        assertEquals(Optional.of(town), Rules.refusing(List.of(town), Flag.BUILD, Optional.of(bob)));
    }

    @Test
    void anyRegionAtTheTopPriorityDenying() {
        Region a = region("a").withMember(bob);
        Region b = region("b");
        assertEquals(Optional.of(b), Rules.refusing(List.of(a, b), Flag.BUILD, Optional.of(bob)));
    }

    @Test
    void natureCrossesOnlyIntoRegionsItStartsInOrThatAllowBuilding() {
        Region spawn = region("spawn");
        assertTrue(Rules.refusingCrossing(List.of(), List.of(spawn)).isPresent(), "water flowing in from outside");
        assertTrue(Rules.refusingCrossing(List.of(spawn), List.of(spawn)).isEmpty(), "a piston door inside");
        assertTrue(Rules.refusingCrossing(List.of(spawn), List.of()).isEmpty(), "flowing out");
        assertTrue(Rules.refusingCrossing(List.of(), List.of(spawn.withFlag(Flag.BUILD, true))).isEmpty());
    }

    @Test
    void entryIsCheckedOnlyWhenComingIn() {
        Region vault = region("vault").withFlag(Flag.ENTRY, false);
        Optional<UUID> who = Optional.of(bob);
        assertTrue(Rules.refusingEntry(List.of(), List.of(vault), who).isPresent());
        assertTrue(Rules.refusingEntry(List.of(vault), List.of(vault), who).isEmpty(), "already inside");
        assertTrue(Rules.refusingEntry(List.of(vault), List.of(), who).isEmpty(), "leaving");
        assertTrue(Rules.refusingEntry(List.of(), List.of(vault.withMember(bob)), who).isEmpty(), "a member");
    }

    @Test
    void ownersAndMembersStayApart() {
        Region r = region("a").withOwner(alice).withMember(alice);
        assertTrue(r.members().contains(alice));
        assertTrue(!r.owners().contains(alice));
        assertTrue(r.withOwner(alice).members().isEmpty());
        assertTrue(!r.without(alice).isMember(alice));
    }

    @Test
    void natureFollowsTheHighestPriorityToo() {
        Region town = region("town");
        Region plot = region("plot").withPriority(1).withFlag(Flag.BUILD, true);
        assertTrue(Rules.refusingCrossing(List.of(), List.of(town, plot)).isEmpty(),
                "water may flow into an open plot inside a closed town, as anyone may build there");
        assertEquals(Optional.of(town), Rules.refusingCrossing(List.of(), List.of(town)));
    }
}
