package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins issue #430 for the data viewer under a deliberately small heap: a Pixie file
 * that passes the row limit but cannot be decoded whole is refused by the memory gate
 * with its default budget, instead of running out of memory. (That reading such a
 * file whole does run out of memory in this heap is pinned by PixieStoreHeapTest.)
 *
 * <p>Runs {@link Child} in a fresh JVM with {@code -Xmx} set, on the test classpath,
 * since the test JVM's heap cannot be shrunk per test.
 */
class PixieDataSessionHeapTest {

    /** 40 series x 250,000 points is ~250 MB estimated: far over half of a 48 MB heap. */
    private static final int SERIES = 40;
    private static final int POINTS = 250_000;

    private static final int EXIT_REFUSED = 0;
    private static final int EXIT_OUT_OF_MEMORY = 3;
    private static final int EXIT_NOT_REFUSED = 4;

    @TempDir
    Path tempDir;

    @Test
    void viewerRefusesAFileTooBigToDecodeWholeInsteadOfRunningOutOfMemory() throws Exception {
        long[] timestamps = new long[POINTS];
        double[] values = new double[POINTS];
        for (int i = 0; i < POINTS; i++) {
            timestamps[i] = i * 86_400_000L;
            values[i] = 1.0;
        }
        // One shared series keeps this JVM small; constant values keep the file small.
        TimeSeriesData data = new TimeSeriesData(timestamps, values);
        List<NamedSeries> series = new ArrayList<>(SERIES);
        for (int s = 0; s < SERIES; s++) {
            series.add(new NamedSeries("node.n" + s + ".dsflow", data));
        }
        String base = tempDir.resolve("big").toString();
        new PixieWriter().writeToFile(base, series, true);

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx48m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), base + ".pxt")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "child JVM timed out");
        assertEquals(EXIT_REFUSED, process.exitValue(), "child output:\n" + output);
        assertTrue(output.contains("Run Manager"), "refusal should point at the Run Manager:\n" + output);
    }

    /** The child JVM's entry point: opens {@code <pxt>} with the default memory budget. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            try {
                // A row limit the file passes (as #430's did), and the real default budget.
                PixieDataSession session = new PixieDataSession(new File(args[0]), () -> 5_000_000);
                long deadline = System.currentTimeMillis() + 60_000;
                while (!session.isLoaded() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                String refusal = session.refusal();
                System.out.println("refusal: " + refusal);
                System.exit(refusal != null && !refusal.startsWith("Pixie read failed")
                    ? EXIT_REFUSED : EXIT_NOT_REFUSED);
            } catch (OutOfMemoryError e) {
                System.exit(EXIT_OUT_OF_MEMORY);
            }
        }
    }
}
