package com.kalix.ide.flowviz.data;

import java.io.File;
import java.util.function.LongFunction;

/**
 * Default {@link LabelResolver}. Projects each {@link SeriesRef} variant to its
 * user-visible label using only the data carried on the ref plus, for {@link RunSeries},
 * an injected lookup that returns the current display name for a run id, and for
 * {@link AggregateSeries}, an injected lookup that returns the aggregate's
 * {@link AggregateLabel}.
 *
 * <p>The lookups are functions rather than direct {@code Map}s so the resolver
 * stays decoupled from the {@code RunManager}'s internal storage shape. Owners pass
 * something like {@code id -> runManager.runNameForId(id)}.</p>
 *
 * <p>The {@link DatasetSeries} variant needs no external dependency — the short
 * filename used in the label is derived from the absolute path stored on the ref.</p>
 *
 * <p>If a lookup returns {@code null} (run or aggregate unknown or recently removed),
 * the label falls back to {@code "?"} to make the missing identity obvious to a
 * reader rather than silently producing a misleading label.</p>
 */
public final class DefaultLabelResolver implements LabelResolver {

    private static final String UNKNOWN = "?";

    /** Appended to an aggregate's origin label once that origin has been removed. */
    static final String REMOVED_SUFFIX = " (removed)";

    private final LongFunction<String> runNameLookup;
    private final LongFunction<AggregateLabel> aggregateLookup;

    /** A resolver for contexts that hold no aggregates. */
    public DefaultLabelResolver(LongFunction<String> runNameLookup) {
        this(runNameLookup, id -> null);
    }

    public DefaultLabelResolver(LongFunction<String> runNameLookup,
                                LongFunction<AggregateLabel> aggregateLookup) {
        this.runNameLookup = runNameLookup;
        this.aggregateLookup = aggregateLookup;
    }

    @Override
    public String labelFor(SeriesRef ref) {
        return nameFor(ref) + " [" + sourceLabel(ref) + "]";
    }

    @Override
    public String nameFor(SeriesRef ref) {
        return switch (ref) {
            case RunSeries r -> r.baseName();
            case LastSeries l -> l.baseName();
            case DatasetSeries d -> d.baseName();
            case AggregateSeries a -> {
                AggregateLabel label = aggregateLookup.apply(a.aggregateId());
                yield AggregateSeries.NAME_PREFIX + (label != null ? label.name() : UNKNOWN);
            }
        };
    }

    @Override
    public String sourceLabel(SeriesRef ref) {
        return switch (ref) {
            case RunSeries r -> runLabel(r.runId());
            case LastSeries l -> "Last";
            case DatasetSeries d -> datasetLabel(d.datasetId());
            case AggregateSeries a -> {
                AggregateLabel label = aggregateLookup.apply(a.aggregateId());
                yield label != null ? originLabel(label) : UNKNOWN;
            }
        };
    }

    /**
     * The label of the source an aggregate was summed from: projected live while that
     * source exists, so a run rename carries through, and frozen at its last display
     * name, marked removed, once it does not.
     */
    public String originLabel(AggregateLabel label) {
        return originLabel(label.origin(), label.removedOriginLabel());
    }

    /** {@link #originLabel(AggregateLabel)} for an origin alone, e.g. its group node. */
    public String originLabel(SourceRef origin, String removedOriginLabel) {
        if (removedOriginLabel != null) {
            return removedOriginLabel + REMOVED_SUFFIX;
        }
        return switch (origin) {
            case RunSource r -> runLabel(r.runId());
            case LastSource l -> "Last";
            case DatasetSource d -> datasetLabel(d.datasetId());
            // Aggregates of aggregates are out of scope; creation never builds one.
            case AggregateSource s -> throw new IllegalStateException(
                "Aggregate origin cannot itself be an aggregate: " + s);
        };
    }

    private String runLabel(long runId) {
        String runName = runNameLookup.apply(runId);
        return runName != null ? runName : UNKNOWN;
    }

    private static String datasetLabel(String datasetId) {
        return new File(datasetId).getName();
    }
}
