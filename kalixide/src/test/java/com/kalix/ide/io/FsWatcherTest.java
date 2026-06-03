package com.kalix.ide.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FsWatcher} delivers filesystem changes — confirming the native watcher
 * (FSEvents on macOS) works on this platform and our wrapper dispatches domain events.
 * Pure I/O plus the EDT; no display required.
 */
class FsWatcherTest {

    @Test
    void deliversCreateEventOnTheEdt() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("fswatcher");
        CountDownLatch sawCreate = new CountDownLatch(1);
        List<Boolean> onEdt = new CopyOnWriteArrayList<>();

        FsWatcher watcher = new FsWatcher(event -> {
            onEdt.add(SwingUtilities.isEventDispatchThread());
            if (event.kind() == FsWatcher.Kind.CREATE
                    && event.path().getFileName().toString().equals("new-model.ini")) {
                sawCreate.countDown();
            }
        });

        try {
            SwingUtilities.invokeAndWait(() -> watcher.watch(dir));
            // Let the off-EDT init and initial scan complete before mutating the directory.
            Thread.sleep(1000);

            Files.writeString(dir.resolve("new-model.ini"), "[node.x]\n");

            assertTrue(sawCreate.await(10, TimeUnit.SECONDS),
                "Expected a CREATE event from FsWatcher");
            assertTrue(onEdt.stream().allMatch(Boolean::booleanValue),
                "Events must be delivered on the EDT");
        } finally {
            SwingUtilities.invokeAndWait(watcher::stop);
        }
    }
}
