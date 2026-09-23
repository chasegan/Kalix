package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.BeforeAll;
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
 * Pins issue #430 under a deliberately small heap: a Pixie file whose series together
 * far exceed the heap must still open and serve every series through
 * {@link PixieStore}, where reading it whole runs out of memory.
 *
 * <p>The test JVM's heap is Gradle's default and cannot be shrunk per test, so each
 * case runs {@link Child} in a fresh JVM with {@code -Xmx} set, on the test classpath.
 * The eager case is the control: it proves the fixture is big enough for the lazy case
 * to mean something.
 */
class PixieStoreHeapTest {

    /** Heap for the child JVM. */
    private static final String HEAP = "-Xmx48m";

    /** 40 series x 250,000 points x ~17 bytes decoded is ~170 MB: several times the heap. */
    private static final int SERIES = 40;
    private static final int POINTS = 250_000;

    /**
     * The out-of-memory case gets its own one-series file, sized so that each of its two
     * arrays alone (2.5M x 8 bytes = 20 MB) is larger than the whole {@link #TINY_HEAP}.
     * Its decode then cannot fit on any JVM or platform. (Sizing it against a single
     * 250,000-point series left a 4 MB series in a 6 MB heap, which fitted on macOS.)
     */
    private static final int ONE_SERIES_POINTS = 2_500_000;
    private static final String TINY_HEAP = "-Xmx16m";

    private static final int EXIT_OK = 0;
    private static final int EXIT_OUT_OF_MEMORY = 3;

    @TempDir
    static Path tempDir;

    private static String basePath;
    private static String oneSeriesBasePath;

    @BeforeAll
    static void writeFixture() throws Exception {
        // One shared TimeSeriesData keeps this JVM's footprint to a single series; constant
        // values Gorilla-compress to almost nothing, so the file on disk stays small while
        // its decoded size does not.
        long[] timestamps = new long[POINTS];
        double[] values = new double[POINTS];
        long day = 86_400_000L;
        for (int i = 0; i < POINTS; i++) {
            timestamps[i] = i * day;
            values[i] = 1.0;
        }
        TimeSeriesData data = new TimeSeriesData(timestamps, values);
        List<NamedSeries> series = new ArrayList<>(SERIES);
        for (int s = 0; s < SERIES; s++) {
            series.add(new NamedSeries("node.n" + s + ".dsflow", data));
        }
        basePath = tempDir.resolve("big").toString();
        new PixieWriter().writeToFile(basePath, series, true);

        long[] oneTimestamps = new long[ONE_SERIES_POINTS];
        double[] oneValues = new double[ONE_SERIES_POINTS];
        for (int i = 0; i < ONE_SERIES_POINTS; i++) {
            oneTimestamps[i] = i * day;
            oneValues[i] = 1.0;
        }
        oneSeriesBasePath = tempDir.resolve("oneBig").toString();
        new PixieWriter().writeToFile(oneSeriesBasePath, List.of(
            new NamedSeries("node.big.dsflow", TimeSeriesData.adopting(oneTimestamps, oneValues))), true);
    }

    @Test
    void readingTheWholeFileRunsOutOfMemory() throws Exception {
        assertEquals(EXIT_OUT_OF_MEMORY, runChild("eager", HEAP, basePath, SERIES, POINTS),
            "control: the fixture must not fit the heap when read whole, or the lazy test proves nothing");
    }

    @Test
    void storeOpensAndServesEverySeriesWithinTheSameHeap() throws Exception {
        assertEquals(EXIT_OK, runChild("lazy", HEAP, basePath, SERIES, POINTS));
    }

    /**
     * A heap smaller than either array of one series: the decode runs out of memory on the
     * store's thread. The request must fail rather than hang, or the Run Manager would
     * show the series as loading forever.
     */
    @Test
    void decodeThatRunsOutOfMemoryFailsTheRequestRatherThanHanging() throws Exception {
        assertEquals(EXIT_OUT_OF_MEMORY,
            runChild("lazy", TINY_HEAP, oneSeriesBasePath, 1, ONE_SERIES_POINTS));
    }

    /** Runs {@link Child} in a fresh JVM under {@code heap}; returns its exit code. */
    private static int runChild(String mode, String heap, String base, int series, int points)
            throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, heap, "-XX:+UseSerialGC", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), mode, base, String.valueOf(series), String.valueOf(points))
            .redirectErrorStream(true)
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "child JVM timed out");
        int exit = process.exitValue();
        if (exit != EXIT_OK && exit != EXIT_OUT_OF_MEMORY) {
            throw new AssertionError("child JVM failed (exit " + exit + "):\n"
                + new String(output, StandardCharsets.UTF_8));
        }
        return exit;
    }

    /**
     * The child JVM's entry point: {@code eager|lazy <basePath> <series> <points>}, the last
     * two being what the file holds; exits 0, or 3 on running out of memory.
     */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            String base = args[1];
            int expectedSeries = Integer.parseInt(args[2]);
            int expectedPoints = Integer.parseInt(args[3]);
            try {
                if (mode.equals("eager")) {
                    List<NamedSeries> all = new PixieReader().readAllSeries(base);
                    System.out.println("read " + all.size() + " series");
                } else {
                    // What the Run Manager does: open the index, then decode series one
                    // at a time as they are plotted, holding none of them afterwards.
                    PixieStore store = new PixieStore();
                    List<PixieSeriesKey> keys = store.open(new File(base + ".pxt"));
                    if (keys.size() != expectedSeries) {
                        throw new AssertionError("expected " + expectedSeries + " series, got " + keys.size());
                    }
                    for (PixieSeriesKey key : keys) {
                        // A bounded wait: a request that never settles fails as a timeout.
                        int points = store.get(key).get(30, TimeUnit.SECONDS).getPointCount();
                        if (points != expectedPoints) {
                            throw new AssertionError("series " + key.index() + " has " + points + " points");
                        }
                    }
                }
            } catch (OutOfMemoryError e) {
                System.exit(EXIT_OUT_OF_MEMORY);
            } catch (Exception e) {
                // An OOM on the store's decode thread arrives wrapped in the future's exception.
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof OutOfMemoryError) {
                        System.exit(EXIT_OUT_OF_MEMORY);
                    }
                }
                throw e;
            }
            System.exit(EXIT_OK);
        }
    }
}
