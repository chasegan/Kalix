package com.kalix.ide.editor.autocomplete;

import com.kalix.ide.constants.UIConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Maintains a cached listing of the data files under the model's base directory,
 * for the {@code [data]} autocomplete popup.
 *
 * <p>Lists what {@code [data]} actually accepts: CSVs and the {@code .pxt} half
 * of Pixie pairs. The {@code .pxb} half is deliberately left out — the engine
 * reads it alongside its {@code .pxt} and rejects it if named directly, so
 * offering it would only be offering a mistake.</p>
 *
 * <p>The recursive directory walk used to run synchronously on the EDT every time
 * the popup opened. This class follows the {@link InputDataRegistry} pattern next
 * door: callers get the last-completed listing instantly and each call triggers a
 * coalesced background rescan (at most one queued at a time), so the popup is
 * never blocked on the filesystem — a freshly created file simply appears the
 * next time the popup opens.</p>
 */
public class InputFileScanner {

    private static final Logger logger = LoggerFactory.getLogger(InputFileScanner.class);

    private final Supplier<File> baseDirectorySupplier;
    private final ExecutorService executor;

    /** Whether a rescan is already queued; further requests coalesce into it. */
    private final AtomicBoolean scanQueued = new AtomicBoolean(false);

    /**
     * Last-completed listing: forward-slash relative paths under the base
     * directory, immutable. Replaced wholesale by the background scan.
     */
    private volatile List<String> relativePaths = List.of();

    public InputFileScanner(Supplier<File> baseDirectorySupplier) {
        this.baseDirectorySupplier = baseDirectorySupplier;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "InputFileScanner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns the last scanned data-file listing immediately (never touches the
     * filesystem) and queues a background rescan so the next request sees
     * current contents.
     *
     * @return immutable forward-slash relative paths, possibly stale, never null
     */
    public List<String> getRelativePathsAndRefresh() {
        if (scanQueued.compareAndSet(false, true)) {
            executor.submit(this::scan);
        }
        return relativePaths;
    }

    /** Shuts down the background executor. */
    public void dispose() {
        executor.shutdownNow();
    }

    private void scan() {
        // Cleared first: a request arriving mid-scan queues a fresh scan that
        // will observe any files the current walk raced past.
        scanQueued.set(false);
        try {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir == null || !baseDir.isDirectory()) {
                relativePaths = List.of();
                return;
            }

            List<File> dataFiles = new ArrayList<>();
            collectDataFiles(baseDir, 0, dataFiles);

            List<String> paths = new ArrayList<>(dataFiles.size());
            for (File dataFile : dataFiles) {
                // Forward slashes for consistency across platforms
                paths.add(baseDir.toPath().relativize(dataFile.toPath())
                        .toString().replace('\\', '/'));
            }
            relativePaths = List.copyOf(paths);
        } catch (Exception e) {
            logger.debug("Input file scan failed: {}", e.getMessage());
        }
    }

    private static void collectDataFiles(File directory, int depth, List<File> results) {
        if (depth > UIConstants.AutoComplete.MAX_INPUT_FILE_SCAN_DEPTH
                || results.size() >= UIConstants.AutoComplete.MAX_INPUT_FILE_COUNT) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (results.size() >= UIConstants.AutoComplete.MAX_INPUT_FILE_COUNT) {
                return;
            }
            // A .pxb is intentionally not offerable: [data] takes the .pxt half
            // of the pair, and the .pxb rides along with it.
            if (file.isFile() && isOfferable(file.getName())) {
                results.add(file);
            } else if (file.isDirectory() && !file.getName().startsWith(".")) {
                collectDataFiles(file, depth + 1, results);
            }
        }
    }

    /** Whether a file name is one {@code [data]} will accept. */
    private static boolean isOfferable(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".csv") || lower.endsWith(".pxt");
    }
}
