package dev.iredsc.hadron.neoforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which tests --tests picks, and which test classes that failed to load are still reported. */
final class TestFilterTest {
    static final class GoodTests {}

    @Test
    void matchingIgnoresCase() {
        TestFilter filter = TestFilter.parse("goodtests.someMethod");
        assertTrue(filter.matches(GoodTests.class, "SOMEMETHOD"));
        assertFalse(filter.matches(GoodTests.class, "other"));
    }

    @Test
    void aClassThatCouldNotLoadIsReportedWhenAMethodPatternNamesItInAnyCase() {
        // A load failure of a class the run asked for must never vanish into a green run.
        TestFilter filter = TestFilter.parse("brokentests.someMethod, GoodTests");
        assertTrue(filter.mightMatchClass("dev.example.BrokenTests"), "simple name, other case");
        assertTrue(TestFilter.parse("DEV.EXAMPLE.brokentests.x").mightMatchClass("dev.example.BrokenTests"),
                "full name, other case");
        assertTrue(TestFilter.parse("dev.example.outer$brokentests.x").mightMatchClass("dev.example.Outer$BrokenTests"),
                "a nested class's binary name");
    }

    @Test
    void aClassNoPatternCanNameIsNotReported() {
        TestFilter filter = TestFilter.parse("GoodTests.someMethod, OtherTests");
        assertFalse(filter.mightMatchClass("dev.example.BrokenTests"));
        assertFalse(filter.mightMatchClass("dev.example.BrokenTestsExtra"));
    }

    @Test
    void aWildcardMightNameAnyClass() {
        assertTrue(TestFilter.parse("*secret*").mightMatchClass("dev.example.BrokenTests"));
    }
}
