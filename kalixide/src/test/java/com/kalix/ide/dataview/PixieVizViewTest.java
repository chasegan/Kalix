package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the pixie plot mount: pool filled from the decoded arrays under stable
 * refs, the first-series default with its zoom fit, undoable header-column
 * toggling, the refusal note withdrawing data, and reload survival.
 */
class PixieVizViewTest {

    @TempDir
    Path tempDir;

    private PixieDataSession session;
    private PixieDataPanel panel;

    @AfterEach
    void disposeSession() {
        // Hygiene in the shared suite JVM: no leaked decode workers or stale
        // listener chains left behind to congest later tests' EDT.
        if (session != null) {
            session.dispose();
        }
    }

    /**
     * Polls a condition to become true (async decode pipeline settling). The
     * condition is evaluated ON THE EDT: the EDT runs one runnable at a time,
     * so a check can only ever observe between-runnable states — never the
     * middle of the publish runnable that fills the pool and then applies the
     * default selection — and each {@code invokeAndWait} hands this thread a
     * happens-before edge over everything the EDT published. The previous
     * helper evaluated the condition on the test thread (an unsynchronized
     * read racing a mid-flight {@code onLoaded}), which was this class's
     * suite-load flake: "pool filled" was visible before the same runnable's
     * default selection.
     */
    private static void await(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    /** Evaluates the condition on the EDT (drain + happens-before, see {@link #await}). */
    private static boolean onEdt(BooleanSupplier condition) throws Exception {
        boolean[] value = new boolean[1];
        SwingUtilities.invokeAndWait(() -> value[0] = condition.getAsBoolean());
        return value[0];
    }

    private static TimeSeriesData daily(double... values) {
        LocalDateTime[] times = new LocalDateTime[values.length];
        for (int i = 0; i < values.length; i++) {
            times[i] = LocalDateTime.of(2020, 1, 1, 0, 0).plusDays(i);
        }
        return new TimeSeriesData(times, values);
    }

    private File writePixie(String name, List<NamedSeries> series) throws IOException {
        String base = tempDir.resolve(name).toString();
        new PixieWriter().writeToFile(base, series, true);
        return new File(base + ".pxt");
    }

    private PixieVizView openView(File pxt, long limit) throws Exception {
        session = new PixieDataSession(pxt, () -> limit);
        PixieVizView[] holder = new PixieVizView[1];
        SwingUtilities.invokeAndWait(() -> {
            panel = new PixieDataPanel(session);
            holder[0] = new PixieVizView(panel, session);
        });
        return holder[0];
    }

    private DatasetSeries ref(String name) {
        return new DatasetSeries(session.pxtFile().getAbsolutePath(), name);
    }

    @Test
    void firstSeriesIsPlottedByDefaultFromTheDecodedPool() throws Exception {
        PixieVizView view = openView(writePixie("two", List.of(
            new NamedSeries("node.a.flow", daily(1, 2, 3)),
            new NamedSeries("node.b.flow", daily(10, 20, 30)))), 1000);
        // Await what is asserted: the default SELECTION, not mere pool
        // membership (both land in one EDT runnable, selection last).
        await(() -> view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("node.a.flow")),
            "first-series default selection");
        assertTrue(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("node.a.flow")));
        assertTrue(view.dataSetForTests().hasSeries(ref("node.b.flow")),
            "the whole file decodes into the pool, selected or not");
        assertEquals(3, view.dataSetForTests().getSeries(ref("node.a.flow")).getPointCount());
    }

    @Test
    void headerToggleIsUndoableAndFirstSelectionFits() throws Exception {
        PixieVizView view = openView(writePixie("toggle", List.of(
            new NamedSeries("a", daily(1, 2)),
            new NamedSeries("b", daily(3, 4)))), 1000);
        await(() -> view.dataSetForTests().hasSeries(ref("a")), "pool filled");
        SwingUtilities.invokeAndWait(() -> view.toggleSeriesColumn(2)); // model column 2 = series b
        assertTrue(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("b")));
        assertTrue(view.vizManagerForTests().getTargetVizPanel().canUndo(),
            "a header toggle pushes history like a tree tick");
        SwingUtilities.invokeAndWait(() -> view.toggleSeriesColumn(2));
        assertFalse(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("b")));
    }

    @Test
    void dateColumnCannotBeToggled() throws Exception {
        PixieVizView view = openView(writePixie("date", List.of(
            new NamedSeries("a", daily(1)))), 1000);
        // The baseline must be sampled after the default selection has landed,
        // or a late-landing default reads as the toggle having added a series.
        await(() -> view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("a")),
            "default selection applied");
        int before = view.vizManagerForTests().getTargetTabSelectedSeries().size();
        SwingUtilities.invokeAndWait(() -> view.toggleSeriesColumn(0));
        assertEquals(before, view.vizManagerForTests().getTargetTabSelectedSeries().size());
    }

    @Test
    void refusalShowsInTheStatusStripOnceAndWithdrawsData() throws Exception {
        PixieVizView view = openView(writePixie("big", List.of(
            new NamedSeries("a", daily(1, 2, 3, 4, 5)))), 4);
        await(() -> panel.getStatusText().contains("exceeds"), "gate note");
        assertTrue(view.dataSetForTests().isEmpty(), "nothing decoded, nothing plotted");
        assertEquals(" ", view.noteText(),
            "the table's strip owns the reason; the viz note must not stutter it");
    }

    @Test
    void reloadReplacesDataUnderTheSameRefs() throws Exception {
        File pxt = writePixie("reload", List.of(new NamedSeries("a", daily(1))));
        PixieVizView view = openView(pxt, 1000);
        await(() -> view.dataSetForTests().hasSeries(ref("a")), "initial pool");

        String base = pxt.getAbsolutePath().substring(0, pxt.getAbsolutePath().length() - 4);
        new PixieWriter().writeToFile(base, List.of(new NamedSeries("a", daily(5, 6))), true);
        session.reloadFromDisk();
        await(() -> view.dataSetForTests().getSeries(ref("a")) != null
            && view.dataSetForTests().getSeries(ref("a")).getPointCount() == 2, "re-decode");
        assertEquals(5.0, view.dataSetForTests().getSeries(ref("a")).getValues()[0],
            "the same ref carries the rewritten data");
    }
}
