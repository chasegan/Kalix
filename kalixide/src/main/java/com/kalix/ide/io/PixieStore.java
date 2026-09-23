package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A static, shared store of open Pixie pairs that serves series lazily.
 *
 * <p>{@link #open} reads only the {@code .pxt} index; nothing is decoded until a
 * caller asks for a series with {@link #get}, which decodes that one block from the
 * {@code .pxb} (via {@link PixieReader#readSeries(String, PixieReader.SeriesInfo)})
 * on a background thread. So opening costs the size of the index, not the file, and
 * a pair opened from several windows is decoded once.
 *
 * <p><b>Memory.</b> Decoded series are cached behind {@link SoftReference}s. A series
 * stays in memory for as long as any caller holds it (a plot, a stats tab); once none
 * does, it stays cached while the heap has room, and the GC may clear it before it
 * would otherwise run out of memory. A cleared series is simply decoded again on the
 * next request. Callers must therefore not keep their own long-lived strong caches of
 * decoded data, or nothing can be reclaimed; they should hold a {@link PixieSeriesKey}
 * and ask the store.
 *
 * <p><b>Changed files.</b> An index is only valid for the bytes it was read from.
 * {@link #open} stamps the pair's sizes and modification times, and every decode
 * checks the stamp first: if the pair has changed, the request fails with
 * {@link StaleIndexException} rather than decoding the new bytes against the old
 * offsets. The caller re-opens the file to pick up the new index. (A rewrite within
 * the filesystem's timestamp resolution that also keeps both sizes slips past the
 * stamp; {@code PixieReader}'s bounds and point-count checks are the backstop.)
 *
 * <p>Thread-safe. Futures from {@link #get} complete on the store's decode thread;
 * callers hop to the EDT themselves.
 */
public final class PixieStore {

    /** A request against an index whose pair has changed on disk since {@link #open}. */
    public static final class StaleIndexException extends IOException {
        StaleIndexException(File pxtFile) {
            super("Pixie file has changed since it was opened: " + pxtFile.getName());
        }
    }

    private static final PixieStore SHARED = new PixieStore();

    /** The IDE-wide store. */
    public static PixieStore shared() {
        return SHARED;
    }

    /** Size and modification time of both halves of a pair. */
    private record Stamp(long pxtSize, long pxtModified, long pxbSize, long pxbModified) {
        static Stamp of(File pxt, File pxb) {
            return new Stamp(pxt.length(), pxt.lastModified(), pxb.length(), pxb.lastModified());
        }
    }

    /**
     * One open pair: its index, the generation and stamp it was read under, and what
     * has been decoded from it.
     */
    private static final class Entry {
        final File pxtFile;
        final String basePath;
        final long generation;
        final Stamp stamp;
        final Map<Integer, PixieReader.SeriesInfo> index; // insertion order = .pxt order
        final Map<Integer, SoftReference<TimeSeriesData>> decoded = new HashMap<>();
        final Map<Integer, CompletableFuture<TimeSeriesData>> inFlight = new HashMap<>();

        Entry(File pxtFile, String basePath, long generation, Stamp stamp,
              Map<Integer, PixieReader.SeriesInfo> index) {
            this.pxtFile = pxtFile;
            this.basePath = basePath;
            this.generation = generation;
            this.stamp = stamp;
            this.index = index;
        }
    }

    private final Map<File, Entry> entries = new HashMap<>();

    /** Next index generation; every index read gets its own, store-wide. */
    private long nextGeneration = 1;

    /**
     * One decode at a time: a long series is tens of MB decoded, so concurrent decodes
     * would stack, and bounding peak memory is the point of the store. Daemon, so it
     * never keeps the IDE from exiting.
     */
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kalix-pixie-decode");
        thread.setDaemon(true);
        return thread;
    });

    /** Public for tests; the IDE uses {@link #shared()}. */
    public PixieStore() {
    }

    // --- whole-file memory check (issue #430) ---
    //
    // Shared by the views that decode every series of a pair (the data viewer and the
    // FlowViz window), so a file is judged the same way wherever it is opened. Reads
    // the index only. A file over budget should be refused before decoding, pointing
    // the user at the Run Manager, which decodes only the series that are plotted.

    /**
     * Estimated peak bytes per point for a whole-file load: 17 held per point
     * (timestamp, value, validity flag) plus 8 for the view's own working
     * structures (the data viewer's union time index; plot headroom in FlowViz).
     */
    private static final long WHOLE_LOAD_BYTES_PER_POINT = 25;

    /**
     * Transient bytes per point of the series being decoded: the float codec's float[]
     * before it is widened (decoded arrays are adopted, not copied, by TimeSeriesData).
     */
    private static final long DECODE_BYTES_PER_POINT = 4;

    /** Estimated peak memory to decode and hold every series in {@code index}. */
    public static long estimateWholeLoadBytes(List<PixieReader.SeriesInfo> index) {
        long totalPoints = 0;
        long longestSeries = 0;
        for (PixieReader.SeriesInfo info : index) {
            totalPoints += info.pointCount;
            longestSeries = Math.max(longestSeries, info.pointCount);
        }
        return totalPoints * WHOLE_LOAD_BYTES_PER_POINT + longestSeries * DECODE_BYTES_PER_POINT;
    }

    /** The default whole-load budget: half the heap, leaving the rest to runs and other documents. */
    public static long defaultWholeLoadBudget() {
        return Runtime.getRuntime().maxMemory() / 2;
    }

    /** Sizes for refusal messages: one decimal of GB, or whole MB below that. */
    public static String formatBytes(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format("%.1f GB", gb) : String.format("%,d MB", bytes / (1024 * 1024));
    }

    /**
     * Opens a pair from either half, reading only its {@code .pxt} index. Re-opening
     * a pair that is unchanged on disk returns the same keys and keeps what has been
     * decoded; re-opening one that has changed reads the new index under a new
     * generation and drops the old decoded series. Keys from the old generation then
     * fail as stale for every caller holding them: a re-open by one window never
     * silently hands another window different data. Blocking (reads the index file);
     * call off the EDT.
     *
     * @return the pair's series keys, in {@code .pxt} order
     * @throws IOException if either half is missing or the index cannot be read
     */
    public List<PixieSeriesKey> open(File pixieFile) throws IOException {
        File pxtFile = pairFile(pixieFile, ".pxt").getCanonicalFile();
        File pxbFile = pairFile(pixieFile, ".pxb").getCanonicalFile();
        if (!pxtFile.isFile() || !pxbFile.isFile()) {
            throw new IOException("Both .pxt and .pxb files are required. Missing: "
                + (!pxtFile.isFile() ? pxtFile.getName() : pxbFile.getName()));
        }

        synchronized (this) {
            Entry existing = entries.get(pxtFile);
            if (existing != null && existing.stamp.equals(Stamp.of(pxtFile, pxbFile))) {
                return keysOf(existing);
            }
        }

        // Read outside the lock; stamp BEFORE reading so a rewrite that lands
        // mid-read fails the next stamp check instead of being trusted.
        Stamp stamp = Stamp.of(pxtFile, pxbFile);
        String basePath = basePathOf(pxtFile);
        Map<Integer, PixieReader.SeriesInfo> index = new LinkedHashMap<>();
        for (PixieReader.SeriesInfo info : new PixieReader().getSeriesInfo(basePath)) {
            index.put(info.index, info);
        }
        synchronized (this) {
            // A concurrent open of the same bytes may have installed an entry while we
            // read. Reuse it: replacing it would start a new generation and make the
            // other caller's keys stale although nothing changed on disk.
            Entry concurrent = entries.get(pxtFile);
            if (concurrent != null && concurrent.stamp.equals(stamp)) {
                return keysOf(concurrent);
            }
            Entry entry = new Entry(pxtFile, basePath, nextGeneration++, stamp,
                Collections.unmodifiableMap(index));
            entries.put(pxtFile, entry);
            return keysOf(entry);
        }
    }

    /**
     * The index row for a series: name, point count, time span, timestep. A copy,
     * so callers cannot disturb the offsets the store decodes from.
     *
     * @throws IllegalArgumentException if the key's pair is not open, the key is from an
     *                                  older generation of its index, or it has no such series
     */
    public synchronized PixieReader.SeriesInfo info(PixieSeriesKey key) {
        return copyOf(indexRow(key));
    }

    /**
     * The decoded series, from the cache if it is still there, otherwise decoded on
     * the store's background thread. Concurrent requests for one series share a
     * single decode. The future fails with {@link StaleIndexException} if the pair
     * changed since the key's {@link #open}, whether or not it has been re-opened since,
     * and with {@link IllegalArgumentException} if the key's pair is not open or has no
     * such series.
     */
    public CompletableFuture<TimeSeriesData> get(PixieSeriesKey key) {
        synchronized (this) {
            Entry current = entries.get(key.pxtFile());
            if (current != null && current.generation != key.generation()) {
                return CompletableFuture.failedFuture(new StaleIndexException(key.pxtFile()));
            }
            Entry entry;
            PixieReader.SeriesInfo info;
            try {
                entry = entryFor(key);
                info = indexRow(key);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.failedFuture(e);
            }

            SoftReference<TimeSeriesData> cached = entry.decoded.get(key.index());
            TimeSeriesData data = cached != null ? cached.get() : null;
            if (data != null) {
                return CompletableFuture.completedFuture(data);
            }
            CompletableFuture<TimeSeriesData> pending = entry.inFlight.get(key.index());
            if (pending != null) {
                return pending;
            }

            CompletableFuture<TimeSeriesData> decode = new CompletableFuture<>();
            entry.inFlight.put(key.index(), decode);
            decoder.execute(() -> decodeInto(entry, info, decode));
            return decode;
        }
    }

    /**
     * {@link #get}, waited for: for worker threads that need a series before going on.
     * Never call on the EDT. Failures surface as thrown: an {@link IOException}
     * (including {@link StaleIndexException}), a {@link RuntimeException}, or an
     * {@link Error} such as running out of memory.
     */
    public TimeSeriesData read(PixieSeriesKey key) throws IOException {
        try {
            return get(key).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while decoding", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    /** Decode thread: check the stamp, decode, cache, settle the future. */
    private void decodeInto(Entry entry, PixieReader.SeriesInfo info, CompletableFuture<TimeSeriesData> decode) {
        try {
            if (!entry.stamp.equals(Stamp.of(entry.pxtFile, new File(entry.basePath + ".pxb")))) {
                throw new StaleIndexException(entry.pxtFile);
            }
            TimeSeriesData data = new PixieReader().readSeries(entry.basePath, info);
            synchronized (this) {
                // Caching into an entry that has since been replaced or closed is
                // harmless: nothing can reach it any more.
                entry.decoded.put(info.index, new SoftReference<>(data));
                entry.inFlight.remove(info.index);
            }
            decode.complete(data);
        } catch (Throwable e) {
            // Every future must settle, errors included: a decode that runs out of memory
            // would otherwise leave its series "loading" forever, and the stuck in-flight
            // entry would hand the same unsettled future to every later request.
            synchronized (this) {
                entry.inFlight.remove(info.index);
            }
            decode.completeExceptionally(e);
        }
    }

    /**
     * Forgets a pair, its index and everything decoded from it. Series already
     * handed out stay valid for whoever holds them. Either half may be passed.
     */
    public void close(File pixieFile) throws IOException {
        File pxtFile = pairFile(pixieFile, ".pxt").getCanonicalFile();
        synchronized (this) {
            entries.remove(pxtFile);
        }
    }

    /** Whether the pair is open in this store. Either half may be passed. */
    public boolean isOpen(File pixieFile) throws IOException {
        File pxtFile = pairFile(pixieFile, ".pxt").getCanonicalFile();
        synchronized (this) {
            return entries.containsKey(pxtFile);
        }
    }

    // --- helpers (callers hold the lock where they touch entries) ---

    private Entry entryFor(PixieSeriesKey key) {
        Entry entry = entries.get(key.pxtFile());
        if (entry == null) {
            throw new IllegalArgumentException("Pixie file is not open: " + key.pxtFile());
        }
        if (entry.generation != key.generation()) {
            throw new IllegalArgumentException("Key is from an older index of " + key.pxtFile().getName()
                + "; the file has been re-opened since");
        }
        return entry;
    }

    private PixieReader.SeriesInfo indexRow(PixieSeriesKey key) {
        PixieReader.SeriesInfo info = entryFor(key).index.get(key.index());
        if (info == null) {
            throw new IllegalArgumentException("No series " + key.index() + " in " + key.pxtFile().getName());
        }
        return info;
    }

    private static List<PixieSeriesKey> keysOf(Entry entry) {
        List<PixieSeriesKey> keys = new ArrayList<>(entry.index.size());
        for (Integer index : entry.index.keySet()) {
            keys.add(new PixieSeriesKey(entry.pxtFile, entry.generation, index));
        }
        return Collections.unmodifiableList(keys);
    }

    private static PixieReader.SeriesInfo copyOf(PixieReader.SeriesInfo source) {
        PixieReader.SeriesInfo copy = new PixieReader.SeriesInfo();
        copy.index = source.index;
        copy.offset = source.offset;
        copy.name = source.name;
        copy.pointCount = source.pointCount;
        copy.startTime = source.startTime;
        copy.endTime = source.endTime;
        copy.timestepSeconds = source.timestepSeconds;
        return copy;
    }

    /** The named half of the pair {@code pixieFile} belongs to. */
    private static File pairFile(File pixieFile, String extension) {
        return new File(basePathOf(pixieFile) + extension);
    }

    /** Path without its .pxt/.pxb extension; PixieReader appends the extension itself. */
    private static String basePathOf(File pixieFile) {
        String path = pixieFile.getPath();
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pxt") || lower.endsWith(".pxb")
            ? path.substring(0, path.length() - 4) : path;
    }
}
