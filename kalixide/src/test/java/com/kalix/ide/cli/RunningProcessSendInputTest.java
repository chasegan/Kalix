package com.kalix.ide.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The STDIO protocol is line-delimited, and sends reach the process from several
 * threads at once. Every line must arrive whole: a burst of concurrent sends once
 * interleaved as {@code {A}{B}\n\n}, which the engine rejected and the requests
 * behind it were lost. This pins "one message is one write" on
 * {@link ProcessExecutor.RunningProcess#sendInput}.
 */
class RunningProcessSendInputTest {

    private static final int THREADS = 8;
    private static final int LINES_PER_THREAD = 200;

    @Test
    void concurrentSendsArriveAsWholeLines() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProcessExecutor.RunningProcess process =
            new ProcessExecutor.RunningProcess(new FakeProcess(stdin), new CompletableFuture<>(), true);

        Map<String, Integer> expected = new HashMap<>();
        List<List<String>> perThread = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < LINES_PER_THREAD; i++) {
                // Varying lengths, some longer than BufferedWriter's 8192-char buffer, so
                // the writer's own chunking is exercised too.
                String line = "{\"m\":\"cmd\",\"t\":" + t + ",\"i\":" + i
                    + ",\"pad\":\"" + "x".repeat((i * 97) % 12000) + "\"}";
                lines.add(line);
                expected.merge(line, 1, Integer::sum);
            }
            perThread.add(lines);
        }

        CyclicBarrier start = new CyclicBarrier(THREADS);
        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (List<String> lines : perThread) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (String line : lines) {
                        process.sendInput(line);
                    }
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(failures.isEmpty(), "sender threads failed: " + failures);

        String received = stdin.toString(StandardCharsets.UTF_8);
        assertFalse(received.contains("\r"), "terminator must be \\n on every platform");
        assertTrue(received.endsWith("\n"), "stream must end with a terminator");

        Map<String, Integer> actual = new HashMap<>();
        for (String line : received.substring(0, received.length() - 1).split("\n", -1)) {
            assertTrue(expected.containsKey(line),
                "received a line that was never sent (interleaved or empty): "
                    + line.substring(0, Math.min(line.length(), 80)));
            actual.merge(line, 1, Integer::sum);
        }
        assertEquals(expected, actual, "every sent line must arrive exactly once");
    }

    @Test
    void trailingNewlineIsNotDoubled() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProcessExecutor.RunningProcess process =
            new ProcessExecutor.RunningProcess(new FakeProcess(stdin), new CompletableFuture<>(), true);

        process.sendInput("{\"m\":\"term\"}\n");
        process.sendInput("{\"m\":\"term\"}");

        assertEquals("{\"m\":\"term\"}\n{\"m\":\"term\"}\n", stdin.toString(StandardCharsets.UTF_8));
    }

    /** A process whose stdin is a byte buffer; nothing else is exercised. */
    private static final class FakeProcess extends Process {
        private final OutputStream stdin;

        FakeProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
