package com.kalix.ide.flowviz.data;

/**
 * {@link SourceRef} variant for a user-loaded dataset file, identified by its
 * absolute path — the same {@code datasetId} convention as {@link DatasetSeries}.
 */
public record DatasetSource(String datasetId) implements SourceRef {
}
