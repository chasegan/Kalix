package com.kalix.ide.flowviz.stats;

import org.junit.jupiter.api.Test;

import java.time.Month;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the sum type's contract. Value equality here is load-bearing well beyond the
 * type itself: the plot's transform cache key and the undo history both dedup on it,
 * so a mode that compares unequal to an identical selection shows up as a stale plot
 * or a phantom undo step rather than as a failure here.
 */
class SeasonalMaskModeTest {

    @Test
    void noSelectionAndEveryMonthBothCollapseToDisabled() {
        assertSame(SeasonalMaskMode.DISABLED, SeasonalMaskMode.of(Set.of()),
            "an empty selection masks nothing, so it must not look active");
        assertSame(SeasonalMaskMode.DISABLED, SeasonalMaskMode.of(EnumSet.allOf(Month.class)),
            "all twelve months mask nothing either");
    }

    @Test
    void aPartialSelectionIsEnabled() {
        SeasonalMaskMode mode = SeasonalMaskMode.of(Set.of(Month.JANUARY, Month.JUNE));
        assertTrue(mode instanceof SeasonalMaskMode.Enabled);
        assertEquals(Set.of(Month.JANUARY, Month.JUNE), ((SeasonalMaskMode.Enabled) mode).months());
    }

    @Test
    void enabledRejectsBothSelectionsThatMaskNothing() {
        // of() collapses both cases, but the record is public: constructing "enabled
        // masking nothing" directly is a contradiction, not a silent no-op. Callers
        // pattern-match on the type, so an Enabled that filters nothing would light the
        // toolbar icon and send the aggregator down its masked path for a no-op.
        assertThrows(IllegalArgumentException.class,
            () -> new SeasonalMaskMode.Enabled(Set.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new SeasonalMaskMode.Enabled(EnumSet.allOf(Month.class)),
            "all twelve months masks nothing, exactly as none does");
    }

    @Test
    void equalityIgnoresTheSetImplementationAndItsOrder() {
        Set<Month> insertionOrdered = new LinkedHashSet<>();
        insertionOrdered.add(Month.JUNE);
        insertionOrdered.add(Month.JANUARY);

        SeasonalMaskMode a = SeasonalMaskMode.of(insertionOrdered);
        SeasonalMaskMode b = SeasonalMaskMode.of(new TreeSet<>(Set.of(Month.JANUARY, Month.JUNE)));
        SeasonalMaskMode c = SeasonalMaskMode.of(EnumSet.of(Month.JANUARY, Month.JUNE));

        assertEquals(a, b);
        assertEquals(b, c);
        assertEquals(a.hashCode(), b.hashCode(), "cache keys and history dedup hash on this");
        assertEquals(b.hashCode(), c.hashCode());
    }

    @Test
    void differentSelectionsAreNotEqual() {
        assertNotEquals(SeasonalMaskMode.of(Set.of(Month.JANUARY)),
            SeasonalMaskMode.of(Set.of(Month.FEBRUARY)));
        assertNotEquals(SeasonalMaskMode.of(Set.of(Month.JANUARY)), SeasonalMaskMode.DISABLED);
    }

    @Test
    void theSelectionCannotBeMutatedThroughTheCallersSet() {
        Set<Month> caller = new LinkedHashSet<>(Set.of(Month.JANUARY));
        SeasonalMaskMode mode = SeasonalMaskMode.of(caller);
        caller.add(Month.JULY);

        assertEquals(Set.of(Month.JANUARY), ((SeasonalMaskMode.Enabled) mode).months(),
            "the record copies defensively; a shared set would mutate a cache key in place");
        assertThrows(UnsupportedOperationException.class,
            () -> ((SeasonalMaskMode.Enabled) mode).months().add(Month.JULY));
    }

    @Test
    void includesAnswersForBothVariants() {
        assertTrue(SeasonalMaskMode.DISABLED.includes(Month.MARCH), "disabled keeps every month");
        SeasonalMaskMode enabled = SeasonalMaskMode.of(Set.of(Month.MARCH));
        assertTrue(enabled.includes(Month.MARCH));
        assertTrue(!enabled.includes(Month.APRIL));
    }
}
