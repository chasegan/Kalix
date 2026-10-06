package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogTest {

    @TempDir
    File dir;

    @Test
    void formatsFullDateTimeWithMilliseconds() {
        String line = ErrorLog.format(LocalDateTime.of(2026, 10, 6, 9, 5, 3, 7_000_000), "boom");
        assertEquals("2026-10-06 09:05:03.007  boom\n", line);
    }

    @Test
    void createsNoFileUntilFirstError() {
        assertNull(new ErrorLog(dir).file());
        assertEquals(0, dir.list().length);
    }

    @Test
    void appendsLinesInOrderToOneFile() throws IOException {
        ErrorLog log = new ErrorLog(dir);
        String first = log.append("one");
        String second = log.append("two");

        assertNotNull(first);
        assertEquals(first + second, Files.readString(log.file().toPath()));
        assertEquals(1, dir.list().length);
    }

    @Test
    void concurrentFirstErrorsShareOneFile() throws Exception {
        ErrorLog log = new ErrorLog(dir);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String message = "error " + i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                log.append(message);
            });
            workers.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }

        assertEquals(1, dir.list().length);
        assertEquals(threads, Files.readAllLines(log.file().toPath()).size());
    }

    @Test
    void failedWriteReturnsNull() {
        ErrorLog log = new ErrorLog(new File(dir, "missing-subdirectory"));
        assertNull(log.append("cannot be written"));
        assertTrue(dir.list().length == 0);
    }
}
