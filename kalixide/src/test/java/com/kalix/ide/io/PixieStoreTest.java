package com.kalix.ide.io;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixieStoreTest {

    @TempDir
    Path tempDir;

    private final PixieStore store = new PixieStore();

    @Test
    void openListsTheIndexAndGetDecodesEachSeries() throws Exception {
        List<NamedSeries> written = List.of(
            new NamedSeries("node.a.dsflow", daily(10, 1.0)),
            new NamedSeries("node.b.dsflow", daily(400, 2.0)));
        File pxt = write("pair", written);

        List<PixieSeriesKey> keys = store.open(pxt);
        assertEquals(2, keys.size());
        assertEquals(List.of("node.a.dsflow", "node.b.dsflow"),
            keys.stream().map(k -> store.info(k).name).toList());
        assertEquals(400, store.info(keys.get(1)).pointCount);

        for (int i = 0; i < keys.size(); i++) {
            TimeSeriesData data = await(store.get(keys.get(i)));
            assertArrayEquals(written.get(i).data().getTimestamps(), data.getTimestamps());
            assertArrayEquals(written.get(i).data().getValues(), data.getValues(), 0.0);
        }
    }

    /** The issue's guard: opening must not depend on, or read, the .pxb's contents. */
    @Test
    void openReadsOnlyTheIndex() throws Exception {
        File pxt = write("lazy", List.of(new NamedSeries("s", daily(10, 1.0))));
        Path pxb = Path.of(pxt.getPath().replace(".pxt", ".pxb"));
        byte[] junk = new byte[(int) Files.size(pxb)];
        Files.write(pxb, junk); // same size, unreadable blocks

        List<PixieSeriesKey> keys = store.open(pxt);
        assertEquals("s", store.info(keys.get(0)).name);
    }

    @Test
    void repeatRequestsShareOneDecodedSeries() throws Exception {
        File pxt = write("cached", List.of(new NamedSeries("s", daily(10, 1.0))));
        PixieSeriesKey key = store.open(pxt).get(0);

        TimeSeriesData first = await(store.get(key));
        assertSame(first, await(store.get(key)), "held series is served from the cache");
    }

    @Test
    void eitherHalfOpensTheSamePairAndKeepsItsCache() throws Exception {
        File pxt = write("halves", List.of(new NamedSeries("s", daily(10, 1.0))));
        File pxb = new File(pxt.getPath().replace(".pxt", ".pxb"));

        PixieSeriesKey viaPxt = store.open(pxt).get(0);
        TimeSeriesData data = await(store.get(viaPxt));
        PixieSeriesKey viaPxb = store.open(pxb).get(0);

        assertEquals(viaPxt, viaPxb);
        assertSame(data, await(store.get(viaPxb)), "re-opening an unchanged pair keeps what was decoded");
    }

    @Test
    void rewrittenPairFailsStaleUntilReopened() throws Exception {
        File pxt = write("rewritten", List.of(new NamedSeries("s", daily(10, 1.0))));
        PixieSeriesKey key = store.open(pxt).get(0);

        write("rewritten", List.of(new NamedSeries("s", daily(5, 9.0))));
        bumpModified(pxt);

        ExecutionException stale = assertThrows(ExecutionException.class, () -> await(store.get(key)));
        assertInstanceOf(PixieStore.StaleIndexException.class, stale.getCause());

        PixieSeriesKey reopened = store.open(pxt).get(0);
        TimeSeriesData fresh = await(store.get(reopened));
        assertEquals(5, fresh.getPointCount());
        assertEquals(36.0, fresh.getValues()[4], 0.0);
    }

    @Test
    void closeForgetsThePair() throws Exception {
        File pxt = write("closed", List.of(new NamedSeries("s", daily(10, 1.0))));
        PixieSeriesKey key = store.open(pxt).get(0);
        assertTrue(store.isOpen(pxt));

        store.close(pxt);

        assertFalse(store.isOpen(pxt));
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(store.get(key)));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void infoIsACopy() throws Exception {
        File pxt = write("copy", List.of(new NamedSeries("s", daily(10, 1.0))));
        PixieSeriesKey key = store.open(pxt).get(0);

        store.info(key).offset = 999_999;

        assertEquals(10, await(store.get(key)).getPointCount(), "store decodes from its own offsets");
    }

    @Test
    void missingHalfIsRefusedOnOpen() throws Exception {
        File pxt = write("lonely", List.of(new NamedSeries("s", daily(3, 1.0))));
        Files.delete(Path.of(pxt.getPath().replace(".pxt", ".pxb")));

        assertThrows(IOException.class, () -> store.open(pxt));
    }

    private File write(String name, List<NamedSeries> series) throws Exception {
        String base = tempDir.resolve(name).toString();
        new PixieWriter().writeToFile(base, series, true);
        return new File(base + ".pxt");
    }

    /** Rewrites can land within the filesystem's timestamp resolution; make the change visible. */
    private static void bumpModified(File pxt) {
        File pxb = new File(pxt.getPath().replace(".pxt", ".pxb"));
        long later = System.currentTimeMillis() + 10_000;
        pxt.setLastModified(later);
        pxb.setLastModified(later);
    }

    private static TimeSeriesData await(CompletableFuture<TimeSeriesData> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static TimeSeriesData daily(int days, double scale) {
        LocalDateTime[] times = new LocalDateTime[days];
        double[] values = new double[days];
        for (int i = 0; i < days; i++) {
            times[i] = LocalDateTime.of(2020, 1, 1, 0, 0).plusDays(i);
            values[i] = scale * i;
        }
        return new TimeSeriesData(times, values);
    }
}
