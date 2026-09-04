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
        /** Defensively copies into an unmodifiable {@code EnumSet}, fixing iteration to Jan..Dec. */
        public Enabled {
            EnumSet<Month> selection = EnumSet.noneOf(Month.class);
            selection.addAll(months);
            if (selection.isEmpty()) {
                throw new IllegalArgumentException("Seasonal mask needs at least one month; use DISABLED");
            }
            months = Collections.unmodifiableSet(selection);
        }
    }

    /**
     * Builds a mode from a month selection.
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
