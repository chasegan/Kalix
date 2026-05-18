package com.kalix.ide.flowviz.data;

import java.io.File;
import java.util.function.LongFunction;

/**
 * Default {@link LabelResolver}. Projects each {@link SeriesRef} variant to its
 * user-visible label using only the data carried on the ref plus, for {@link RunSeries},
 * an injected lookup that returns the current display name for a run id.
 *
 * <p>The run-name lookup is a function rather than a direct {@code Map} so the resolver
 * stays decoupled from the {@code RunManager}'s internal storage shape. Owners pass
 * something like {@code id -> runManager.runNameForId(id)}.</p>
 *
 * <p>The {@link DatasetSeries} variant needs no external dependency — the short
 * filename used in the label is derived from the absolute path stored on the ref.</p>
 *
 * <p>If the run-name lookup returns {@code null} (run unknown or recently removed),
 * the label falls back to {@code "?"} to make the missing identity obvious to a
 * reader rather than silently producing a misleading label.</p>
 */
public final class DefaultLabelResolver implements LabelResolver {

    private final LongFunction<String> runNameLookup;

    public DefaultLabelResolver(LongFunction<String> runNameLookup) {
        this.runNameLookup = runNameLookup;
    }

    @Override
    public String labelFor(SeriesRef ref) {
        return switch (ref) {
            case RunSeries r -> {
                String runName = runNameLookup.apply(r.runId());
                yield r.baseName() + " [" + (runName != null ? runName : "?") + "]";
            }
            case LastSeries l -> l.baseName() + " [Last]";
            case DatasetSeries d -> d.baseName() + " [" + new File(d.datasetId()).getName() + "]";
        };
    }
}
