package com.kalix.ide.dataview;

import com.kalix.ide.io.CsvGzFormat;
import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.io.SourceResCsvHeaderReader;
import com.kalix.ide.preferences.PreferenceKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Format dispatch for opening a {@link DataViewSession}: the single place that
 * decides what "view this data file" means per format.
 *
 * <p>Plain {@code .csv} opens directly (dialect sniffed from the head). A
 * Source {@code .res.csv} carries an extended header the table must not show
 * as rows: its data region starts just past the {@code EOH} marker line, and
 * its column names come from the existing marker-driven header machinery
 * ({@link SourceResCsvHeaderReader}) rather than from a header row. Any
 * failure to interpret the extended header falls back to a plain CSV view —
 * uglier, but honest and never blocking the open.
 */
public final class DataViewOpener {

    private static final Logger logger = LoggerFactory.getLogger(DataViewOpener.class);

    /** Extended headers live at the top of the file; do not scan forever for one. */
    private static final long MAX_HEADER_SCAN_BYTES = 4L * 1024 * 1024;

    private DataViewOpener() {
    }

    /**
     * Opens the right kind of session for the file. Does a small, bounded amount
     * of blocking I/O (a head read; for {@code .res.csv} a capped header scan) —
     * currently invoked from the open path on the EDT, like the pre-existing
     * whole-file text read there, so everything here must stay bounded and cheap.
     */
    public static DataViewSession openFor(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        // Longest suffix first — the .res.csv test below cannot see a .csv.gz.
        if (CsvGzFormat.isCsvGz(name)) {
            return openGzipCsv(file);
        }
        if (name.endsWith(".res.csv")) {
            try {
                return openSourceResCsv(file);
            } catch (IOException | RuntimeException e) {
                logger.warn("Falling back to plain CSV view for {}: {}", file.getName(), e.getMessage());
            }
        }
        return DataViewSession.open(file.toPath());
    }

    /**
     * The {@code .csv.gz} contract (issue #374): decompress the whole payload
     * to memory — bounded while it runs by the in-memory limit preference,
     * a cross that throws {@link CsvGzFormat.TooLargeException} with the
     * honest reason — then serve it through the ordinary session machinery
     * over an in-memory channel.
     */
    private static DataViewSession openGzipCsv(File file) throws IOException {
        long limitBytes = PreferenceKeys.DATAVIEW_GZIP_MEMORY_LIMIT_MB.get() * 1024L * 1024L;
        byte[] text = CsvGzFormat.decompressBounded(file, limitBytes);
        return DataViewSession.open(ByteSource.ofMemory(file.toPath(), text), 0L, null, List.of());
    }

    private static DataViewSession openSourceResCsv(File file) throws IOException {
        HeaderScan scan = scanPastMarkerLine(file, SourceResCsvFormat.MARKER_EOH);
        List<String> seriesNames = new SourceResCsvHeaderReader().readSeriesNames(file);
        String[] columns = new String[seriesNames.size() + 1];
        columns[0] = "Date";
        for (int i = 0; i < seriesNames.size(); i++) {
            columns[i + 1] = seriesNames.get(i);
        }
        return DataViewSession.open(file.toPath(), scan.dataStartOffset(), columns, scan.headerTextLines());
    }

    /**
     * Where the data region begins, and the header's physical lines — captured,
     * not just counted: the virtual text view renders them above the indexed
     * data region, so the extended header stays visible (transparency: the
     * whole file, never just the part the table interprets).
     */
    private record HeaderScan(long dataStartOffset, List<String> headerTextLines) {
    }

    /** Locates the line following the given marker line ({@code EOH}), capturing the header text. */
    private static HeaderScan scanPastMarkerLine(File file, String marker) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(64);
            List<String> lines = new ArrayList<>();
            long position = 0;
            int b;
            while ((b = in.read()) >= 0) {
                position++;
                // Checked every byte, not only on newlines: a marker-less or
                // CR-only file must never be scanned to EOF (this runs on the
                // open path). MAX_HEADER_SCAN_BYTES also bounds the captured text.
                if (position > MAX_HEADER_SCAN_BYTES) {
                    break;
                }
                if (b == '\n') {
                    String text = line.toString(StandardCharsets.UTF_8);
                    if (text.endsWith("\r")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    lines.add(text);
                    if (text.equals(marker)) {
                        return new HeaderScan(position, lines);
                    }
                    line.reset();
                } else {
                    line.write(b);
                }
            }
        }
        throw new IOException(marker + " marker not found in the file head");
    }
}
