package dev.iredsc.hadron.regions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Text becomes a pattern only through the parser, which refuses what matches no node and says what would. */
class PatternTest {
    private static String refusal(String text) {
        return assertThrows(Pattern.Invalid.class, () -> Pattern.parse(text)).getMessage();
    }

    @Test
    void aMistypedNodeNamesTheNearestNodeAndWhatIsUnderItsPrefix() {
        assertEquals("`entity.hurt.animals` isn't a node. Did you mean `entity.hurt.animal`? Under entity.hurt: "
                + "animal, misc, monster, player", refusal("entity.hurt.animals"));
        assertTrue(refusal("block.brake").contains("Did you mean `block.break`?"), refusal("block.brake"));
        assertTrue(refusal("block.break.stone").contains("block.break has nothing below it"));
    }

    @Test
    void aPatternMatchingNoNodeALeadingStarAnEmptySegmentOrCapitalsAreRefused() {
        assertEquals("`block.*.animal` matches no node: block.* has nothing below it, so write `block.*`",
                refusal("block.*.animal"));
        assertTrue(refusal("*.hurt").contains("name the root, one of block, enter, entity"), refusal("*.hurt"));
        assertTrue(refusal("block..break").contains("empty segment"));
        assertTrue(refusal("Block.Break").contains("`block.break`"));
        assertTrue(refusal("open").contains("no flag a rule can name"));
        assertTrue(refusal("").contains("build, use, entry, pvp"));
    }

    @Test
    void aPatternCoversWhatIsBelowItAndAStarIsOneSegment() throws Exception {
        Node hurtAnimal = Node.of(Action.ENTITY_HURT, Kind.ANIMAL);
        assertTrue(Pattern.parse("entity").matches(hurtAnimal));
        assertTrue(Pattern.parse("entity.hurt").matches(hurtAnimal));
        assertTrue(Pattern.parse("entity.*.animal").matches(hurtAnimal));
        assertTrue(!Pattern.parse("entity.*.animal").matches(Node.of(Action.ENTITY_HURT, Kind.MISC)));
        assertTrue(!Pattern.parse("entity.*").matches(Node.of(Action.BLOCK_BREAK)));
        assertEquals(Optional.of(Bundle.BUILD), Pattern.parse("build").bundle());
        assertTrue(Pattern.parse("build").matches(hurtAnimal));
        assertTrue(!Pattern.parse("build").matches(Node.of(Action.ENTITY_HURT, Kind.MONSTER)), "monsters are open");
        assertEquals(Optional.of(hurtAnimal), Pattern.parse("entity.hurt.animal").leaf());
        assertEquals(Optional.empty(), Pattern.parse("entity.hurt").leaf());
    }

    @Test
    void completionWalksTheTreeOneSegmentAtATime() {
        assertTrue(Pattern.completions("").containsAll(List.of("build", "pvp", "block", "entity", "other")));
        assertEquals(List.of("entity.hurt"), Pattern.completions("entity.hu"));
        assertEquals(List.of("entity.hurt.*", "entity.hurt.animal", "entity.hurt.misc", "entity.hurt.monster",
                "entity.hurt.player"), Pattern.completions("entity.hurt."));
        assertTrue(Pattern.completions("entity.*.").contains("entity.*.animal"));
        assertEquals(List.of(), Pattern.completions("block.break."));
    }
}
