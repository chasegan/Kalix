package com.kalix.ide.editor.autocomplete;

import com.kalix.ide.io.CsvHeaderReader;
import com.kalix.ide.io.DataSourceHeaderReader;
import com.kalix.ide.io.KaiHeaderReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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
        this.readers = List.of(new CsvHeaderReader(), new KaiHeaderReader());
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
     * @param inputFiles the current list of input file paths from [inputs] section
     */
    public void refresh(List<String> inputFiles) {
        if (inputFiles == null) {
            inputFiles = List.of();
        }

        List<String> current = List.copyOf(inputFiles);

        if (!current.equals(lastInputFiles)) {
            // Input list changed: remove stale entries and submit reads for new files
            cache.keySet().removeIf(key -> !current.contains(key));

            for (String filePath : current) {
                if (!cache.containsKey(filePath)) {
                    submitRead(filePath);
                }
            }
            lastInputFiles = current;
        } else {
            // Same list: check timestamps for already-cached files
            for (String filePath : current) {
                CachedDataSource cached = cache.get(filePath);
                if (cached != null) {
                    File resolved = resolveFile(filePath);
                    if (resolved != null && resolved.lastModified() != cached.lastModified) {
                        submitRead(filePath);
                    }
                } else {
                    // Not yet cached (maybe still loading), resubmit
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
            try {
                File resolved = resolveFile(filePath);
                if (resolved == null || !resolved.exists()) {
                    return;
                }

                String fileName = resolved.getName();
                for (DataSourceHeaderReader reader : readers) {
                    if (reader.canRead(fileName)) {
                        List<String> names = reader.readSeriesNames(resolved);
                        String cleansedFileName = DataSourceHeaderReader.cleanseName(fileName);
                        cache.put(filePath, new CachedDataSource(
                                filePath, cleansedFileName, new ArrayList<>(names), resolved.lastModified()));
                        break;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read headers from {}: {}", filePath, e.getMessage());
            }
        });
    }

    private File resolveFile(String filePath) {
        File baseDir = baseDirectorySupplier.get();
        if (baseDir == null) {
            return null;
        }
        File file = new File(filePath);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(baseDir, filePath);
    }
}
