package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieReader;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * One open Pixie dataset: the decoded, in-memory model behind the pixie table
 * and plot. Pixie inverts the CSV physics — there is no row text to index
 * (values exist only after Gorilla decode of the {@code .pxb}), so this is a
 * <b>decode-once, serve-both-views-from-arrays</b> session: the metadata
 * ({@code .pxt}) is read first for an honest pre-decode gate (total values vs
 * {@link PreferenceKeys#DATAVIEW_PLOT_MAX_ROWS}), then the whole pair decodes
 * on a worker and both views read the arrays.
 *
 * <p>Series in one file may disagree on time base; the table serves a
 * <b>union time index</b> (every timestamp any series has, sorted, deduped)
 * with an absent cell rendered blank — distinct from a stored {@code NaN},
 * which renders as {@code NaN}. The whole file, honestly.
 *
 * <p>Reloads coalesce on a single drain-loop worker (the
 * {@code DataDocument.refreshDataViewFromDisk} pattern); listeners hear about
 * completed loads on the EDT. Reads are EDT-safe (one volatile snapshot).
 */
public final class PixieDataSession {

    /** All methods are delivered on the EDT. */
    public interface Listener {
        void onLoaded();
    }

    private static final DateTimeFormatter DATE_ONLY =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /** One immutable load result; the volatile hand-off between worker and views. */
    private static final class Snapshot {
        static final Snapshot LOADING = new Snapshot(false, null, List.of(), new long[0]);

        final boolean loaded;
        final String refusal; // non-null: why there is no data to show
        final List<NamedSeries> series;
        final long[] unionTimesMillis;

        Snapshot(boolean loaded, String refusal, List<NamedSeries> series, long[] unionTimesMillis) {
            this.loaded = loaded;
            this.refusal = refusal;
            this.series = series;
            this.unionTimesMillis = unionTimesMillis;
        }

        static Snapshot refused(String reason) {
            return new Snapshot(true, reason, List.of(), new long[0]);
        }
    }

    private final File pxtFile;
    private final String basePath;
    private final LongSupplier rowLimit;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot snapshot = Snapshot.LOADING;
    private volatile boolean disposed = false;

    /** Set by every reload request; drained by the single load worker. */
    private final AtomicBoolean loadRequested = new AtomicBoolean(false);
    private final AtomicBoolean loadInFlight = new AtomicBoolean(false);

    public PixieDataSession(File pxtFile) {
        this(pxtFile, () -> PreferenceKeys.DATAVIEW_PLOT_MAX_ROWS.get());
    }

    /** Test seam: the value limit is injectable so tests need no preference writes. */
    PixieDataSession(File pxtFile, LongSupplier rowLimit) {
        this.pxtFile = pxtFile;
        String path = pxtFile.getAbsolutePath();
        String lower = path.toLowerCase(Locale.ROOT);
        // PixieReader takes the extension-less base path and appends .pxt/.pxb itself.
        this.basePath = lower.endsWith(".pxt") || lower.endsWith(".pxb")
            ? path.substring(0, path.length() - 4) : path;
        this.rowLimit = rowLimit;
        reloadFromDisk();
    }

    /** The manifest file this session was opened from (identity for series refs). */
    public File pxtFile() {
        return pxtFile;
    }

    /** Requests a (coalesced) re-decode — the file's bytes changed. Safe from any thread. */
    public void reloadFromDisk() {
        loadRequested.set(true);
        maybeStartWorker();
    }

    private void maybeStartWorker() {
        if (!loadInFlight.compareAndSet(false, true)) {
            return; // the running worker drains loadRequested before exiting
        }
        Thread loader = new Thread(() -> {
            try {
                while (!disposed && loadRequested.getAndSet(false)) {
                    loadOnce();
                }
            } finally {
                loadInFlight.set(false);
                if (loadRequested.get() && !disposed) {
                    maybeStartWorker();
                }
            }
        }, "kalix-pixie-load");
        loader.setDaemon(true);
        loader.start();
    }

    /** One decode: gate from the metadata alone, then the full pair. Worker thread. */
    private void loadOnce() {
        Snapshot fresh;
        try {
            PixieReader reader = new PixieReader();
            long totalPoints = 0;
            for (PixieReader.SeriesInfo info : reader.getSeriesInfo(basePath)) {
                totalPoints += info.pointCount;
            }
            long limit = rowLimit.getAsLong();
            if (totalPoints > limit) {
                // Refused BEFORE decoding: the gate must never cost the memory
                // it exists to protect.
                fresh = Snapshot.refused(String.format(
                    "Table and plot disabled: %,d values exceeds the %,d-row limit"
                        + " (Preferences → Editor → Load and Save)",
                    totalPoints, limit));
            } else {
                List<NamedSeries> series = reader.readAllSeries(basePath);
                fresh = new Snapshot(true, null, List.copyOf(series), unionTimestamps(series));
            }
        } catch (IOException | RuntimeException e) {
            fresh = Snapshot.refused("Pixie read failed: " + e.getMessage());
        }
        if (disposed) {
            return;
        }
        snapshot = fresh;
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                for (Listener listener : listeners) {
                    listener.onLoaded();
                }
            }
        });
    }

    /** Every timestamp any series has — sorted, deduped: the table's row index. */
    private static long[] unionTimestamps(List<NamedSeries> series) {
        int total = 0;
        for (NamedSeries s : series) {
            total += s.data().getPointCount();
        }
        long[] all = new long[total];
        int n = 0;
        for (NamedSeries s : series) {
            long[] timestamps = s.data().getTimestamps();
            System.arraycopy(timestamps, 0, all, n, timestamps.length);
            n += timestamps.length;
        }
        Arrays.sort(all);
        int unique = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[unique++] = all[i];
            }
        }
        return Arrays.copyOf(all, unique);
    }

    // --- EDT-safe reads (one volatile snapshot) ---

    /** False only while the first decode is still running. */
    public boolean isLoaded() {
        return snapshot.loaded;
    }

    /** Why there is no data to show, or {@code null}. */
    public String refusal() {
        return snapshot.refusal;
    }

    public int seriesCount() {
        return snapshot.series.size();
    }

    /** The whole dotted series name, exactly as the {@code .pxt} spells it. */
    public String seriesName(int index) {
        return snapshot.series.get(index).name();
    }

    public int rowCount() {
        return snapshot.unionTimesMillis.length;
    }

    public long timestampAt(int row) {
        return snapshot.unionTimesMillis[row];
    }

    /** The date column's text: date-only at midnight, full timestamp otherwise. */
    public String dateText(int row) {
        long millis = snapshot.unionTimesMillis[row];
        Instant instant = Instant.ofEpochMilli(millis);
        return millis % 86_400_000L == 0 ? DATE_ONLY.format(instant) : DATE_TIME.format(instant);
    }

    /**
     * The cell text for a series at a union row: blank when the series has no
     * value at that timestamp (a time-base gap), {@code NaN} when it stored one.
     */
    public String cellText(int row, int seriesIndex) {
        Snapshot current = snapshot;
        TimeSeriesData data = current.series.get(seriesIndex).data();
        int index = Arrays.binarySearch(data.getTimestamps(), current.unionTimesMillis[row]);
        if (index < 0) {
            return ""; // absent at this timestamp: not data, not NaN
        }
        return formatValue(data.getValues()[index]);
    }

    /** The decoded series (name + data), for the plot mount. Immutable list. */
    public List<NamedSeries> decodedSeries() {
        return snapshot.series;
    }

    /** Shortest honest spelling: integers without ".0", everything else as Java spells it. */
    static String formatValue(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        long whole = (long) value;
        if (value == whole && Math.abs(value) < 1e15) {
            return String.valueOf(whole);
        }
        return Double.toString(value);
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Stops future loads and notifications; in-flight decodes abandon their result. */
    public void dispose() {
        disposed = true;
        listeners.clear();
    }
}
