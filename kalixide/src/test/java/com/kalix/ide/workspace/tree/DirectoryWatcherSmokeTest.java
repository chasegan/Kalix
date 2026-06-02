package com.kalix.ide.workspace.tree;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the native directory watcher (FSEvents on macOS via io.methvin:directory-watcher)
 * actually delivers change events on this platform — the basis for the project tree's live
 * updates. Pure I/O, no Swing, so it runs headless.
 */
class DirectoryWatcherSmokeTest {

    @Test
    void deliversCreateEvent(@TempDir Path dir) throws Exception {
        CountDownLatch sawCreate = new CountDownLatch(1);
        AtomicBoolean error = new AtomicBoolean(false);

        DirectoryWatcher watcher = DirectoryWatcher.builder()
            .path(dir)
            .listener((DirectoryChangeEvent event) -> {
                if (event.eventType() == DirectoryChangeEvent.EventType.CREATE) {
                    sawCreate.countDown();
                }
            })
            .build();

        watcher.watchAsync().exceptionally(t -> {
            error.set(true);
            return null;
        });

        try {
            // Give the watcher a moment to register before mutating the directory.
            Thread.sleep(500);
            Files.writeString(dir.resolve("new-model.ini"), "[node.x]\n");

            assertTrue(sawCreate.await(10, TimeUnit.SECONDS),
                "Expected a CREATE event from the directory watcher");
            assertTrue(!error.get(), "Watcher reported an error");
        } finally {
            watcher.close();
        }
    }
}
