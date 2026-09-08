package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the decode-once pixie session: the union time index over mixed time
 * bases (absent cells blank, stored NaN spelled NaN), the pre-decode value
 * gate, and the honest refusal when the binary half is missing.
 */
class PixieDataSessionTest {

    @TempDir
    Path tempDir;

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static TimeSeriesData daily(LocalDateTime start, double... values) {
        LocalDateTime[] times = new LocalDateTime[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = start.plusDays(i);
        }
        return new TimeSeriesData(times, values);
    }

    private File writePixie(String name, List<NamedSeries> series) throws IOException {
        String base = tempDir.resolve(name).toString();
        new PixieWriter().writeToFile(base, series, true);
        return new File(base + ".pxt");
    }

    private static PixieDataSession openLoaded(File pxt, long limit) {
        PixieDataSession session = new PixieDataSession(pxt, () -> limit);
        await("decode", session::isLoaded);
        return session;
    }

    @Test
    void alignedSeriesDecodeIntoOneGrid() throws IOException {
        File pxt = writePixie("aligned", List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0, 2.5, 3.0)),
            new NamedSeries("level", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 10.0, 20.0, 30.0))));
        PixieDataSession session = openLoaded(pxt, 1000);
        assertNull(session.refusal());
        assertEquals(2, session.seriesCount());
        assertEquals(3, session.rowCount());
        assertEquals("2020-01-01", session.dateText(0));
        assertEquals("1", session.cellText(0, 0), "whole values spell without .0");
        assertEquals("2.5", session.cellText(1, 0));
        assertEquals("30", session.cellText(2, 1));
        session.dispose();
    }

    @Test
    void mixedTimeBasesUnionWithBlanks() throws IOException {
        File pxt = writePixie("mixed", List.of(
            new NamedSeries("a", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0, 2.0, 3.0)),
            new NamedSeries("b", daily(LocalDateTime.of(2020, 1, 2, 0, 0), 20.0, 30.0))));
        PixieDataSession session = openLoaded(pxt, 1000);
        assertEquals(3, session.rowCount(), "union of Jan 1-3");
        assertEquals("", session.cellText(0, 1), "b has no Jan 1: absent, not NaN");
        assertEquals("20", session.cellText(1, 1));
        assertEquals("3", session.cellText(2, 0));
        session.dispose();
    }

    @Test
    void storedNanSpellsNanNotBlank() throws IOException {
        File pxt = writePixie("gappy", List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0, Double.NaN))));
        PixieDataSession session = openLoaded(pxt, 1000);
        assertEquals("NaN", session.cellText(1, 0), "a stored gap is data about missingness");
        session.dispose();
    }

    @Test
    void gateRefusesBeforeDecoding() throws IOException {
        File pxt = writePixie("big", List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1, 2, 3, 4, 5))));
        PixieDataSession session = openLoaded(pxt, 4);
        assertTrue(session.refusal().contains("exceeds"), "5 values over a 4-value limit");
        assertEquals(0, session.rowCount(), "nothing was decoded");
        session.dispose();
    }

    @Test
    void missingBinaryHalfRefusesHonestly() throws IOException {
        File pxt = writePixie("orphan", List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0))));
        assertTrue(new File(pxt.getAbsolutePath().replace(".pxt", ".pxb")).delete());
        PixieDataSession session = openLoaded(pxt, 1000);
        assertTrue(session.refusal().startsWith("Pixie read failed"));
        session.dispose();
    }

    @Test
    void reloadPicksUpARewrittenPair() throws IOException {
        File pxt = writePixie("rewrite", List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0))));
        PixieDataSession session = openLoaded(pxt, 1000);
        assertEquals(1, session.rowCount());

        String base = pxt.getAbsolutePath().substring(0, pxt.getAbsolutePath().length() - 4);
        new PixieWriter().writeToFile(base, List.of(
            new NamedSeries("flow", daily(LocalDateTime.of(2020, 1, 1, 0, 0), 1.0, 2.0))), true);
        session.reloadFromDisk();
        await("re-decode", () -> session.rowCount() == 2);
        assertEquals("2", session.cellText(1, 0));
        session.dispose();
    }
}
