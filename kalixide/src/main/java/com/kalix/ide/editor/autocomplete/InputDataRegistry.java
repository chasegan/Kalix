package com.kalix.ide.editor.autocomplete;

import com.kalix.ide.io.CsvHeaderReader;
import com.kalix.ide.io.DataSourceHeaderReader;
import com.kalix.ide.io.PixieHeaderReader;
import com.kalix.ide.io.SourceResCsvHeaderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.io.KalixPath;
import com.kalix.ide.io.KalixPathResolutionException;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Manages background reading and caching of data file headers for autocomplete.
 * Reads column/series names from input files on a background thread and caches them
 * for instant access by the completion provider.
 *
 * <p>The cache is invalidated when the input file list changes (new model load, undo,
 * cut, paste) or when file modification timestamps change.</p>
 */
public class InputDataRegistry {

    private static final Logger logger = LoggerFactory.getLogger(InputDataRegistry.class);

    private final ConcurrentHashMap<String, CachedDataSource> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final List<DataSourceHeaderReader> readers;
    private final Supplier<File> baseDirectorySupplier;

    private volatile List<String> lastInputFiles = List.of();

    /**
     * Cached data for a single input file.
     */
    public static class CachedDataSource {
        private final String filePath;
        private final String cleansedFileName;
        private final List<String> seriesNames;
        private final long lastModified;

        CachedDataSource(String filePath, String cleansedFileName, List<String> seriesNames, long lastModified) {
            this.filePath = filePath;
            this.cleansedFileName = cleansedFileName;
            this.seriesNames = Collections.unmodifiableList(seriesNames);
            this.lastModified = lastModified;
        }

        public String getFilePath() { return filePath; }
        public String getCleansedFileName() { return cleansedFileName; }
        public List<String> getSeriesNames() { return seriesNames; }
    }

    public InputDataRegistry(Supplier<File> baseDirectorySupplier) {
        this.baseDirectorySupplier = baseDirectorySupplier;
        // SourceResCsvHeaderReader must precede CsvHeaderReader: the latter matches any
        // ".csv", which would otherwise swallow the ".res.csv" double extension.
        this.readers = List.of(
                new SourceResCsvHeaderReader(), new CsvHeaderReader(), new PixieHeaderReader());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "InputDataRegistry-reader");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Triggers a non-blocking refresh check. Compares the current input file list
     * with the last-seen list and submits background reads for new or changed files.
     *
     * @param inputFiles the current list of input file paths from [data] section
     */
    public void refresh(List<String> inputFiles) {
        if (inputFiles == null) {
            inputFiles = List.of();
        }

        List<String> current = List.copyOf(inputFiles);

        if (!current.equals(lastInputFiles)) {
            // Input list changed: remove stale entries and submit reads for new
            // files. The volatile list is published BEFORE the removal so a
            // pending background read can never observe the old list after its
            // entry has been removed (see the re-validation in submitRead).
            lastInputFiles = current;
            cache.keySet().removeIf(key -> !current.contains(key));

            for (String filePath : current) {
                if (!cache.containsKey(filePath)) {
                    submitRead(filePath);
                }
            }
        } else {
            // Same list: check timestamps for already-cached files. Negative
            // entries participate too — their timestamp (0 for a missing file)
            // changes when the file appears or is rewritten, triggering a
            // re-read; until then they suppress the read that every popup used
            // to resubmit for missing/unreadable files.
            for (String filePath : current) {
                CachedDataSource cached = cache.get(filePath);
                if (cached != null) {
                    File resolved = resolveFile(filePath);
                    if (resolved != null && resolved.lastModified() != cached.lastModified) {
                        submitRead(filePath);
                    }
                } else {
                    // Not yet cached (still loading, or evicted), resubmit
                    submitRead(filePath);
                }
            }
        }
    }

    /**
     * Returns the current cache snapshot. Never blocks.
     * Files whose background reads are still pending will simply not appear.
     */
    public Map<String, CachedDataSource> getDataSources() {
        return Collections.unmodifiableMap(cache);
    }

    /**
     * Shuts down the background executor.
     */
    public void dispose() {
        executor.shutdownNow();
    }

    private void submitRead(String filePath) {
        executor.submit(() -> {
            // The file may have been removed from [data] while this read sat in
            // the queue — don't do work for it, and above all don't re-insert it.
            if (!lastInputFiles.contains(filePath)) {
                return;
            }

            cache.put(filePath, readDataSource(filePath));

            // Re-validate after the put: refresh() publishes the new list before
            // pruning the cache, so if the file was removed concurrently, either
            // the prune ran after our put (entry already gone) or we observe the
            // new list here and remove our own stale entry.
            if (!lastInputFiles.contains(filePath)) {
                cache.remove(filePath);
            }
        });
    }

    /**
     * Reads the series names for one input file. Never returns {@code null}:
     * a missing, unresolvable, unreadable or unrecognised file yields a
     * <em>negative</em> entry (empty series list) carrying the file's current
     * timestamp, so {@link #refresh} stops resubmitting the read on every
     * completion popup and retries only when the timestamp changes.
     */
    private CachedDataSource readDataSource(String filePath) {
        long lastModified = 0L;
        try {
            File resolved = resolveFile(filePath);
            if (resolved != null) {
                lastModified = resolved.lastModified(); // 0 for a missing file
                String fileName = resolved.getName();
                if (resolved.exists()) {
                    for (DataSourceHeaderReader reader : readers) {
                        if (reader.canRead(fileName)) {
                            List<String> names = reader.readSeriesNames(resolved);
                            return new CachedDataSource(filePath,
                                    DataSourceHeaderReader.cleanseName(fileName),
                                    new ArrayList<>(names), lastModified);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to read headers from {}: {}", filePath, e.getMessage());
        }
        return new CachedDataSource(filePath,
                DataSourceHeaderReader.cleanseName(fileNameOf(filePath)),
                List.of(), lastModified);
    }

    /** The bare file name of a (possibly relative, possibly unresolvable) input path. */
    private static String fileNameOf(String filePath) {
        int lastSlash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return lastSlash >= 0 ? filePath.substring(lastSlash + 1) : filePath;
    }

    private File resolveFile(String filePath) {
        File baseDir = baseDirectorySupplier.get();
        if (baseDir == null) {
            return null;
        }
        try {
            Path resolved = KalixPath.parse(filePath).resolve(baseDir.toPath());
            return resolved.toFile();
        } catch (IllegalArgumentException | KalixPathResolutionException e) {
            logger.debug("Failed to resolve path '{}': {}", filePath, e.getMessage());
            return null;
        }
    }
}
