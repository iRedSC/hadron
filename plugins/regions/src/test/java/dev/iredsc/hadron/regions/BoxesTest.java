package dev.iredsc.hadron.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

/** Which blocks boxes cover, and which regions an area may stand in, decided without a server. */
class BoxesTest {
    private static final ResourceLocation WORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final BoundingBox CHUNK = new BoundingBox(0, -64, 0, 15, 319, 15);
    private static final Who BOB = new Who.Person(new UUID(0, 2));
    private static final Node USE = Node.of(Action.INVENTORY_OPEN);

    @Test
    void aBoxIsCoveredOnlyWhenEveryBlockIs() {
        BoundingBox box = new BoundingBox(0, 0, 0, 9, 9, 9);
        assertTrue(Boxes.covered(box, List.of(new BoundingBox(-1, -1, -1, 10, 10, 10))));
        assertTrue(Boxes.covered(box, List.of(new BoundingBox(0, 0, 0, 4, 9, 9), new BoundingBox(5, 0, 0, 9, 9, 9))));
        assertFalse(Boxes.covered(box, List.of(new BoundingBox(0, 0, 0, 4, 9, 9), new BoundingBox(6, 0, 0, 9, 9, 9))),
                "the slab at x = 5 is left");
        assertFalse(Boxes.covered(box, List.of()));
    }

    @Test
    void whatABoxLeavesIsEveryBlockOutsideTheCutOnce() {
        BoundingBox box = new BoundingBox(0, 0, 0, 9, 9, 9);
        List<BoundingBox> left = Boxes.minus(box, new BoundingBox(3, 3, 3, 5, 5, 5));
        long blocks = left.stream().mapToLong(b -> (long) b.getXSpan() * b.getYSpan() * b.getZSpan()).sum();
        assertEquals(1000 - 27, blocks);
    }

    private static final Rules ALLOWS = Rules.none().with(Subject.EVERYONE, Pattern.of(Bundle.USE),
            Optional.of(Rules.Verdict.ALLOW));
    private static final Rules DENIES = Rules.none().with(Subject.EVERYONE, Pattern.of(Bundle.USE),
            Optional.of(Rules.Verdict.DENY));

    /** Whether bob is refused {@code inventory.open} somewhere in the chunk, which these regions reach. */
    private static boolean refusedInChunk(Region... reached) {
        List<List<Region>> may = Protection.anywhereIn(List.of(reached), CHUNK, Resolve.answers(BOB, USE, List.of()));
        return Resolve.refusingAny(may, BOB, USE).isPresent();
    }

    private static Region over(BoundingBox box, String id, int priority, Rules rules) {
        return Region.of(id, WORLD, box).withPriority(priority).withRules(rules);
    }

    private static Region overChunk(String id, int priority, Rules rules) {
        return over(CHUNK, id, priority, rules);
    }

    @Test
    void aHigherPlotThatAnswersOverTheWholeTownAreaDecidesForTheArea() {
        Region town = Region.of("town", WORLD, CHUNK);
        Region plot = overChunk("plot", 1, ALLOWS);
        List<List<Region>> may = Protection.anywhereIn(List.of(town, plot), CHUNK, Resolve.answers(BOB, USE,
                List.of()));
        assertEquals(List.of(List.of(plot)), may, "the plot covers all of the town here, so the town never decides");
        assertFalse(refusedInChunk(town, plot));
    }

    @Test
    void overAnAreaTwoLevelsDecideAsAtOneBlock() {
        Region town = overChunk("town", 0, DENIES);
        assertTrue(refusedInChunk(town, overChunk("plot", 1, Rules.none())),
                "a plot saying nothing abstains, so the town below decides");
        assertFalse(refusedInChunk(town, overChunk("plot", 1, ALLOWS)), "a plot allowing decides over the town");
        assertTrue(refusedInChunk(overChunk("town", 0, ALLOWS), overChunk("plot", 1, DENIES)),
                "a plot denying decides over the town");
    }

    @Test
    void overAnAreaThreeLevelsDecideAsAtOneBlock() {
        Region town = overChunk("town", 0, DENIES);
        Region silent = overChunk("top", 2, Rules.none());
        assertTrue(refusedInChunk(town, overChunk("mid", 1, Rules.none()), silent),
                "both above abstain, so the town decides");
        assertFalse(refusedInChunk(town, overChunk("mid", 1, ALLOWS), silent), "the middle allows under a silent top");
        assertFalse(refusedInChunk(town, overChunk("mid", 1, Rules.none()), overChunk("top", 2, ALLOWS)),
                "the top allows over a silent middle");
        assertTrue(refusedInChunk(overChunk("town", 0, ALLOWS), overChunk("mid", 1, DENIES), silent),
                "the middle denies under a silent top");
        assertFalse(refusedInChunk(town, overChunk("mid", 1, DENIES), overChunk("top", 2, ALLOWS)),
                "the top allowing decides over both");
        Region halfAllows = over(new BoundingBox(0, -64, 0, 7, 319, 15), "mid", 1, ALLOWS);
        assertTrue(refusedInChunk(town, halfAllows, silent),
                "the middle allows only half the chunk; in the other half the town decides");
    }

    @Test
    void aHigherRegionElsewhereDoesntSpeakForAnother() {
        Region vault = Region.of("vault", WORLD, new BoundingBox(0, 0, 0, 3, 3, 3));
        Region park = Region.of("park", WORLD, new BoundingBox(10, 0, 0, 13, 3, 3)).withPriority(1)
                .withRules(Rules.none());
        List<List<Region>> may = Protection.anywhereIn(List.of(vault, park), null,
                Resolve.answers(BOB, USE, List.of()));
        assertTrue(may.contains(List.of()), "a whole world always has room outside every region");
        assertTrue(Resolve.refusingAny(may, BOB, USE).isPresent());
    }
}
