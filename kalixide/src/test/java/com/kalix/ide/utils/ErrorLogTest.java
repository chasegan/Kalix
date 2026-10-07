package com.kalix.ide.utils;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLogTest {

    @Test
    void formatsFullDateTimeWithMilliseconds() {
        String line = ErrorLog.format(LocalDateTime.of(2026, 10, 6, 9, 5, 3, 7_000_000), "boom");
        assertEquals("2026-10-06 09:05:03.007  boom\n", line);
    }

    @Test
    void emptyUntilFirstError() {
        ErrorLog log = new ErrorLog();
        assertEquals("", log.text());
        assertEquals(0, log.count());
    }

    @Test
    void appendsOneLinePerErrorInOrder() {
        ErrorLog log = new ErrorLog();
        log.append("one");
        log.append("two");

        List<String> lines = log.text().lines().toList();
        assertEquals(2, lines.size());
        assertEquals(2, log.count());
        assertTrue(lines.get(0).endsWith("  one"), lines.get(0));
        assertTrue(lines.get(1).endsWith("  two"), lines.get(1));
        assertTrue(log.text().endsWith("\n"));
    }

    @Test
    void multiLineMessageIsOneLine() {
        ErrorLog log = new ErrorLog();
        log.append("Failed to launch:\n\nPlease check the command.\r\nCurrent command: x");

        List<String> lines = log.text().lines().toList();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("Failed to launch: Please check the command. Current command: x"));
    }

    @Test
    void concurrentErrorsAreAllKept() throws Exception {
        ErrorLog log = new ErrorLog();
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

        List<String> lines = log.text().lines().toList();
        assertEquals(threads, lines.size());
        for (String line : lines) {
            assertTrue(line.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}  error \\d"), line);
        }
    }
}
