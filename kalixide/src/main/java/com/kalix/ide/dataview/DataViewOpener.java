package com.kalix.ide.dataview;

import com.kalix.ide.io.SourceResCsvFormat;
import com.kalix.ide.io.SourceResCsvHeaderReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
        if (name.endsWith(".res.csv")) {
            try {
                return openSourceResCsv(file);
            } catch (IOException | RuntimeException e) {
                logger.warn("Falling back to plain CSV view for {}: {}", file.getName(), e.getMessage());
            }
        }
        return DataViewSession.open(file.toPath());
    }

    private static DataViewSession openSourceResCsv(File file) throws IOException {
        long dataStart = offsetPastMarkerLine(file, SourceResCsvFormat.MARKER_EOH);
        List<String> seriesNames = new SourceResCsvHeaderReader().readSeriesNames(file);
        String[] columns = new String[seriesNames.size() + 1];
        columns[0] = "Date";
        for (int i = 0; i < seriesNames.size(); i++) {
            columns[i + 1] = seriesNames.get(i);
        }
        return DataViewSession.open(file.toPath(), dataStart, columns);
    }

    /** Byte offset of the line following the given marker line ({@code EOH}). */
    private static long offsetPastMarkerLine(File file, String marker) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            StringBuilder line = new StringBuilder(32);
            long position = 0;
            int b;
            while ((b = in.read()) >= 0) {
                position++;
                // Checked every byte, not only on newlines: a marker-less or
                // CR-only file must never be scanned to EOF (this runs on the
                // open path).
                if (position > MAX_HEADER_SCAN_BYTES) {
                    break;
                }
                if (b == '\n') {
                    String text = line.toString();
                    if (text.endsWith("\r")) {
                        text = text.substring(0, text.length() - 1);
                    }
                    if (text.equals(marker)) {
                        return position;
                    }
                    line.setLength(0);
                } else if (line.length() < 64) {
                    line.append((char) b);
                }
            }
        }
        throw new IOException(marker + " marker not found in the file head");
    }
}
