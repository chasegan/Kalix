package com.kalix.ide.flowviz.stats;

import java.time.Month;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Communicates the state of seasonal masking for the plot window.
 *
 * <p>Sealed rather than an enum because the month selection is a payload carried by one
 * variant only.</p>
 */
public sealed interface SeasonalMaskMode {
    SeasonalMaskMode DISABLED = new Disabled();
    record Disabled() implements SeasonalMaskMode {
    }

    record Enabled(Set<Month> months) implements SeasonalMaskMode {
        /**
         * Defensively copies into an unmodifiable {@code EnumSet}, fixing iteration to Jan..Dec.
         *
         * <p>Rejects both degenerate selections - none and all twelve - so that an
         * {@code Enabled} always masks something. The invariant has to hold here and not
         * only in {@link #of}, because callers pattern-match on the type: anything that
         * tests {@code instanceof Enabled} (the toolbar's lit icon, the aggregator's
         * masked path) would otherwise report an active mask over unfiltered data.</p>
         */
        public Enabled {
            EnumSet<Month> selection = EnumSet.noneOf(Month.class);
            selection.addAll(months);
            if (selection.isEmpty() || selection.size() == Month.values().length) {
                throw new IllegalArgumentException(
                    "Seasonal mask must select some but not all months; use DISABLED");
            }
            months = Collections.unmodifiableSet(selection);
        }
    }

    /**
     * Builds a mode from a month selection, collapsing the two selections that mask
     * nothing - none, and all twelve - to {@link #DISABLED}. This is the entry point to
     * use whenever the selection comes from outside; {@link Enabled} rejects those two
     * outright rather than silently accepting a mask that filters nothing.
     */
    static SeasonalMaskMode of(Set<Month> months) {
        if (months.isEmpty() || months.size() == Month.values().length) {
            return DISABLED;
        }
        return new Enabled(months);
    }

    /** Whether {@code month} survives this mask. Always true when disabled. */
    default boolean includes(Month month) {
        return switch (this) {
            case Disabled ignored -> true;
            case Enabled(Set<Month> months) -> months.contains(month);
        };
    }
}
