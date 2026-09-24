package com.kalix.ide.flowviz.data;

/**
 * {@link SeriesRef} variant for a user-created aggregate: the sum of named series from
 * a single source, computed and held by the Run Manager.
 *
 * <p>{@code aggregateId} is assigned when the aggregate is created and never reused.
 * The user-given name is a label, not identity (per ADR-0003 §1): renaming an aggregate
 * leaves this ref, and everything keyed by it, untouched. The name is projected by
 * {@link LabelResolver#nameFor(SeriesRef)}.</p>
 *
 * <p>Unlike the other variants, {@link #baseName()} is therefore <em>not</em> a display
 * name: it is a stable internal key ({@code "aggregate#<id>"}) and must never be shown.
 * Display code calls {@link LabelResolver#nameFor(SeriesRef)} instead.</p>
 */
public record AggregateSeries(long aggregateId) implements SeriesRef {

    /** Prefix of every aggregate's displayed name: {@code "aggregate.<name>"}. */
    public static final String NAME_PREFIX = "aggregate.";

    /** Stable internal key, never displayed — see the class comment. */
    @Override
    public String baseName() {
        return "aggregate#" + aggregateId;
    }
}
