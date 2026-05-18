package com.kalix.ide.flowviz.data;

/**
 * {@link SeriesRef} variant for a column from a user-loaded {@code .csv} or
 * {@code .kai} dataset.
 *
 * <p>{@code datasetId} is the dataset's absolute file path — the de-facto identity
 * already used by {@code DatasetLoaderManager.isFileAlreadyLoaded} for deduplication.
 * The user-friendly short filename used in legends is a separate label attribute and
 * is produced by {@link LabelResolver}, not stored here.</p>
 */
public record DatasetSeries(String datasetId, String baseName) implements SeriesRef {
}
