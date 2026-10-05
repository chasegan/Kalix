package com.kalix.ide.flowviz.data;

/**
 * {@link SeriesRef} variant for a user-created derived series: the sum of named series from
 * a single source, computed and held by the Run Manager.
 *
 * <p>{@code derivedId} is assigned when the derived series is created and never reused.
 * The user-given name is a label, not identity (per ADR-0003 §1): renaming a derived series
 * leaves this ref, and everything keyed by it, untouched. The name is projected by
 * {@link LabelResolver#nameFor(SeriesRef)}.</p>
 *
 * <p>Unlike the other variants, {@link #baseName()} is therefore <em>not</em> a display
 * name: it is a stable internal key ({@code "derived series#<id>"}) and must never be shown.
 * Display code calls {@link LabelResolver#nameFor(SeriesRef)} instead.</p>
 */
public record DerivedSeries(long derivedId) implements SeriesRef {

    /** Prefix of every derived series' displayed name: {@code "derived.<name>"}. */
    public static final String NAME_PREFIX = "derived.";

    /** Stable internal key, never displayed — see the class comment. */
    @Override
    public String baseName() {
        return "derived series#" + derivedId;
    }
}
