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

    @Test
    void aHigherPlotOverTheWholeTownAreaDecidesForTheArea() {
        Region town = Region.of("town", WORLD, CHUNK);
        Region plot = Region.of("plot", WORLD, CHUNK).withPriority(1).withFlag(Flag.USE, true);
        List<List<Region>> may = Protection.anywhereIn(List.of(town, plot), CHUNK);
        assertEquals(List.of(List.of(plot)), may, "the plot covers all of the town here, so the town never decides");
        Optional<UUID> bob = Optional.of(new UUID(0, 2));
        assertTrue(Rules.refusingAny(may, Flag.USE, bob).isEmpty());
    }

    @Test
    void aHigherRegionElsewhereDoesntSpeakForAnother() {
        Region vault = Region.of("vault", WORLD, new BoundingBox(0, 0, 0, 3, 3, 3));
        Region park = Region.of("park", WORLD, new BoundingBox(10, 0, 0, 13, 3, 3)).withPriority(1)
                .withFlag(Flag.USE, true);
        List<List<Region>> may = Protection.anywhereIn(List.of(vault, park), null);
        assertTrue(may.contains(List.of()), "a whole world always has room outside every region");
        assertTrue(Rules.refusingAny(may, Flag.USE, Optional.of(new UUID(0, 2))).isPresent());
    }
}
