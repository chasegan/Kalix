package com.kalix.ide.managers;

import com.kalix.ide.preferences.PreferenceKeys;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External-change handling for open documents: an external rename (delete+create the watcher
 * cannot correlate) must surface as a missing-file report for the old path. Pure I/O plus the
 * EDT; no display required. Temporarily forces the auto-reload preference on (restored after),
 * since the watch itself is gated on it.
 */
class FileWatcherManagerTest {

    @Test
    void reportsFileMissingAfterExternalRename() throws Exception {
        boolean originalPref = PreferenceKeys.FILE_AUTO_RELOAD.get();
        PreferenceKeys.FILE_AUTO_RELOAD.set(true);
        Path dir = Files.createTempDirectory("kalix-fwm");
        Path file = Files.writeString(dir.resolve("model.ini"), "[node.x]\n");

        CountDownLatch missing = new CountDownLatch(1);
        List<File> reported = new CopyOnWriteArrayList<>();
        FileWatcherManager manager = new FileWatcherManager(
            reloaded -> { },
            f -> {
                reported.add(f);
                missing.countDown();
            });
        try {
            SwingUtilities.invokeAndWait(() -> manager.setWatchedFiles(List.of(file.toFile())));
            Thread.sleep(1500); // off-EDT watcher init + initial scan

            Files.move(file, dir.resolve("renamed.ini")); // the external rename

            assertTrue(missing.await(10, TimeUnit.SECONDS),
                "expected the missing-file callback for the old path");
            assertEquals(file.toFile(), reported.get(0));
        } finally {
            SwingUtilities.invokeAndWait(manager::shutdown);
            PreferenceKeys.FILE_AUTO_RELOAD.set(originalPref);
        }
    }
}
