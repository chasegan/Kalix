package com.kalix.ide.dataview;

import java.io.IOException;

/**
 * What a tabular data source must answer for the unified Find — the whole
 * seam a new format implements to inherit the Find UX (dialog, navigator,
 * F3 repeats, status counts) unchanged.
 *
 * <p>Implementations: {@link DataViewSession} (one streamed byte scan of the
 * CSV file — the table is virtual, so the file is the only place the data
 * lives) and {@link PixieDataSession} (one in-memory pass over the decoded
 * arrays). Both fold matches through {@link DataFind.Collector}, so ordinals
 * and navigation boundaries are computed identically everywhere.
 */
public interface FindableData {

    /**
     * Scans everything once and reports the matches around the origin.
     * Blocking is allowed — the find controller always calls from a worker.
     */
    DataFind.Scan scanForMatches(DataFind.Spec spec, long fromRow, int fromColumn) throws IOException;

    /**
     * Data rows hidden from the table as an in-data header row (CSV with a
     * header: 1; otherwise 0) — the offset between scan rows and view rows.
     */
    long headerRowOffset();
}
