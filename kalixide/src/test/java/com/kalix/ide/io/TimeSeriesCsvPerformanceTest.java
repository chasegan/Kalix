package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance sanity check for the plain-CSV importer/exporter: a 1M-row × 3-series
 * file must import and export in seconds, not minutes. The bound is deliberately
 * generous (20 s each on CI-class hardware) so the test guards against complexity
 * regressions — the exporter was O(rows² × series) before the k-way merge, taking
 * ~5.5 minutes on this workload — without being flaky. Measured times are printed.
 */
class TimeSeriesCsvPerformanceTest {

    private static final int ROWS = 1_000_000;
    private static final int SERIES = 3;
    private static final long BOUND_MS = 20_000;

    @TempDir
    Path tempDir;

    private static final LabelResolver LABELS =
        ref -> ((DatasetSeries) ref).baseName();

    @Test
    @Timeout(120)
    void importsAndExportsMillionRowFileInSeconds() throws Exception {
        // --- generate a 1M-row daily CSV (~30 MB) ---
        File csv = tempDir.resolve("million.csv").toFile();
        LocalDate start = LocalDate.of(900, 1, 1);   // 1M daily rows span ~2700 years
        try (BufferedWriter w = Files.newBufferedWriter(csv.toPath(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder("Date");
            for (int s = 0; s < SERIES; s++) sb.append(",series").append(s + 1);
            w.write(sb.append('\n').toString());
            for (int r = 0; r < ROWS; r++) {
                sb.setLength(0);
                sb.append(start.plusDays(r));
                for (int s = 0; s < SERIES; s++) {
                    sb.append(',').append((r % 1000) / 10.0 + s);
                }
                w.write(sb.append('\n').toString());
            }
        }

        // --- import ---
        long t0 = System.nanoTime();
        TimeSeriesCsvImporter.CsvImportResult result = TimeSeriesCsvImporter.parse(csv, null);
        long importMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("CSV perf: import %d rows x %d series: %d ms%n", ROWS, SERIES, importMs);

        assertFalse(result.hasErrors(), () -> "errors: " + result.getErrors());
        assertEquals(SERIES, result.getSeries().size());
        assertEquals(ROWS, result.getSeries().get(0).data().getPointCount());
        assertTrue(importMs < BOUND_MS,
            () -> "import took " + importMs + " ms (bound " + BOUND_MS + " ms)");

        // --- export the imported data ---
        DataSet ds = new DataSet();
        for (NamedSeries s : result.getSeries()) {
            ds.addSeries(new DatasetSeries("/perf", s.name()), s.data());
        }
        File out = tempDir.resolve("million_out.csv").toFile();
        t0 = System.nanoTime();
        TimeSeriesCsvExporter.export(ds, out, null, LABELS);
        long exportMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("CSV perf: export %d rows x %d series: %d ms%n", ROWS, SERIES, exportMs);

        assertTrue(exportMs < BOUND_MS,
            () -> "export took " + exportMs + " ms (bound " + BOUND_MS + " ms)");

        // Sanity: exported file has header + one row per timestamp.
        try (var lines = Files.lines(out.toPath(), StandardCharsets.UTF_8)) {
            assertEquals(ROWS + 1, lines.count());
        }
    }
}
