package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;

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

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeAndWait(() -> { });
            }
        }
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
        SwingUtilities.invokeAndWait(() ->
            holder[0] = new PixieVizView(new PixieDataPanel(session), session));
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
        await(() -> view.dataSetForTests().hasSeries(ref("node.a.flow")), "pool filled");
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
        await(() -> view.dataSetForTests().hasSeries(ref("a")), "pool filled");
        int before = view.vizManagerForTests().getTargetTabSelectedSeries().size();
        SwingUtilities.invokeAndWait(() -> view.toggleSeriesColumn(0));
        assertEquals(before, view.vizManagerForTests().getTargetTabSelectedSeries().size());
    }

    @Test
    void refusalShowsTheNoteAndWithdrawsData() throws Exception {
        PixieVizView view = openView(writePixie("big", List.of(
            new NamedSeries("a", daily(1, 2, 3, 4, 5)))), 4);
        await(() -> view.noteText().contains("exceeds"), "gate note");
        assertTrue(view.dataSetForTests().isEmpty(), "nothing decoded, nothing plotted");
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
