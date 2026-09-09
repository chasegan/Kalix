package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.TimeSeriesData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the plot-above-table mount: always present, the first-column default
 * selection, undoable header-column toggling, per-tab first-data zoom fits,
 * the honest over-limit and non-date refusals, and re-extraction across a
 * session rebuild.
 */
class DataVizViewTest {

    @TempDir
    Path tempDir;

    private DataViewSession session;

    private DataVizView openView(String csv, LongSupplier rowLimit) throws Exception {
        Path file = tempDir.resolve("data.csv");
        Files.writeString(file, csv);
        session = DataViewSession.open(file);
        await(() -> session.isIndexingComplete() && session.columnCount() > 0, "session structure");
        DataVizView[] holder = new DataVizView[1];
        SwingUtilities.invokeAndWait(() ->
            holder[0] = new DataVizView(new DataViewPanel(session), session, rowLimit));
        return holder[0];
    }

    /** The series as the pool holds it, for failure messages. */
    private static String describe(TimeSeriesData series) {
        return series == null ? "no series"
            : "points=" + series.getPointCount()
                + " timestamps=" + Arrays.toString(series.getTimestamps())
                + " values=" + Arrays.toString(series.getValues());
    }

    private DatasetSeries ref(String column) {
        return new DatasetSeries(session.filePath().toAbsolutePath().toString(), column);
    }

    /** Await-what-you-assert, draining the EDT between polls. */
    private static void await(BooleanSupplier condition, String what) throws Exception {
        await(condition, what, () -> "");
    }

    /**
     * As above; on timeout the message carries what the pool held, so a flake diagnoses itself.
     * The condition is evaluated ON THE EDT: checks can only observe between-runnable states,
     * and each check hands the test thread a happens-before edge over what the EDT published.
     * (The prior test-thread evaluation was the same unsynchronized-read flaw that made
     * PixieVizViewTest flaky under suite load.)
     */
    private static void await(BooleanSupplier condition, String what, Supplier<String> observed) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!onEdt(condition)) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what + "; observed " + observed.get());
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

    @Test
    void plotRegionIsAlwaysMounted() throws Exception {
        DataVizView view = openView("date,a\n2020-01-01,1\n", () -> 100);
        try {
            assertNotNull(view.vizManagerForTests(), "the viz unit exists from birth");
        } finally {
            session.close();
        }
    }

    @Test
    void expandPlotsTheFirstDataColumnByDefault() throws Exception {
        DataVizView view = openView("date,a,b\n2020-01-01,1,10\n2020-01-02,2,20\n", () -> 100);
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "first column extracted");
            assertTrue(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("a")));
            assertFalse(view.dataSetForTests().hasSeries(ref("b")), "only the first column is plotted");
            // The constructor owes the first pass and nothing else runs one: the first
            // tab's activation used to schedule a second, redundant pass from inside
            // construction, which is what put a read in flight to be torn by a rewrite.
            assertEquals(1, view.extractionPassesForTests(), "construction runs exactly one extraction pass");
            assertEquals(2, view.dataSetForTests().getSeries(ref("a")).getPointCount());
        } finally {
            session.close();
        }
    }

    @Test
    void headerToggleIsUndoableSelection() throws Exception {
        DataVizView view = openView("date,a,b\n2020-01-01,1,10\n2020-01-02,2,20\n", () -> 100);
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "default extraction");
            SwingUtilities.invokeAndWait(() -> view.toggleColumn(2));
            await(() -> view.dataSetForTests().hasSeries(ref("b")), "toggled column extracted");
            assertTrue(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("b")));
            assertTrue(view.vizManagerForTests().getTargetVizPanel().canUndo(),
                "a header toggle pushes history like a tree tick");

            SwingUtilities.invokeAndWait(() -> view.toggleColumn(2));
            assertFalse(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("b")));
            assertTrue(view.dataSetForTests().hasSeries(ref("b")),
                "the pool keeps deselected data (like the Run Manager's)");
        } finally {
            session.close();
        }
    }

    @Test
    void newTabsFirstColumnsGetTheirOwnZoomFit() throws Exception {
        DataVizView view = openView("date,a,b\n2020-01-01,1,10\n2020-01-02,2,20\n", () -> 100);
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "default extraction");
            SwingUtilities.invokeAndWait(() -> {
                // "View 2": a fresh empty tab, then its first column — the pool
                // is NOT empty (View 1's data), so the fit must be per-tab.
                VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
                settings.selectedSeries = new java.util.LinkedHashSet<>();
                settings.checkedSources = new java.util.LinkedHashSet<>();
                view.vizManagerForTests().addPlotTabFromSettings(settings);
                view.toggleColumn(2);
                org.junit.jupiter.api.Assertions.assertFalse(view.pendingZoomFitForTests().isEmpty(),
                    "the new tab owes a zoom fit");
            });
            await(() -> view.dataSetForTests().hasSeries(ref("b"))
                && view.pendingZoomFitForTests().isEmpty(), "the fit was honoured at publish");
        } finally {
            session.close();
        }
    }

    @Test
    void showInPlotCentresLikeAUserPan() throws Exception {
        DataVizView view = openView("date,a\n2020-01-01,1\n2020-06-01,2\n2020-12-31,3\n", () -> 100);
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "default extraction");
            SwingUtilities.invokeAndWait(() -> {
                // Re-arm the flag so the assertion below proves centerViewportOn ran.
                view.vizManagerForTests().getTargetVizPanel().resetUserViewportTouched();
                view.showInPlot(2, 1); // file row 2 = 2020-06-01
            });
            assertTrue(view.vizManagerForTests().getTargetVizPanel().isUserViewportTouched(),
                "Show in plot centres like a user pan");
        } finally {
            session.close();
        }
    }

    @Test
    void dateAxisColumnCannotBeToggled() throws Exception {
        DataVizView view = openView("date,a\n2020-01-01,1\n", () -> 100);
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "default extraction");
            SwingUtilities.invokeAndWait(() -> view.toggleColumn(0));
            assertFalse(view.vizManagerForTests().getTargetTabSelectedSeries().contains(ref("date")));
        } finally {
            session.close();
        }
    }

    @Test
    void overLimitFilesRefuseHonestly() throws Exception {
        DataVizView view = openView("date,a\n2020-01-01,1\n2020-01-02,2\n2020-01-03,3\n", () -> 2);
        try {
            await(() -> view.noteText().contains("exceeds"), "over-limit note");
            assertTrue(view.noteText().contains("2-row limit"));
            assertTrue(view.dataSetForTests().isEmpty(), "nothing is materialised over the limit");
        } finally {
            session.close();
        }
    }

    @Test
    void nonDateFirstColumnRefusesHonestly() throws Exception {
        StringBuilder csv = new StringBuilder("id,v\n");
        for (int i = 0; i < 30; i++) {
            csv.append("row").append(i).append(",1\n");
        }
        DataVizView view = openView(csv.toString(), () -> 1000);
        try {
            await(() -> view.noteText().contains("does not parse as dates"), "date refusal note");
            assertTrue(view.dataSetForTests().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    void sessionRebuildReExtractsUnderTheSameRefs() throws Exception {
        DataVizView view = openView("date,a\n2020-01-01,1\n", () -> 100);
        DataViewSession fresh = null;
        try {
            await(() -> view.dataSetForTests() != null && view.dataSetForTests().hasSeries(ref("a")),
                "initial extraction");
            assertEquals(1.0, view.dataSetForTests().getSeries(ref("a")).getValues()[0]);

            Path file = session.filePath();
            Files.writeString(file, "date,a\n2020-01-01,42\n2020-01-02,43\n");
            fresh = DataViewSession.open(file);
            DataViewSession freshFinal = fresh;
            await(() -> freshFinal.isIndexingComplete() && freshFinal.columnCount() > 0, "fresh session");
            SwingUtilities.invokeAndWait(() -> view.onSessionReplaced(freshFinal));

            // Await what is asserted - the fresh file's values - not a point count a
            // torn series could satisfy first. The extractor's indexed-extent rule is
            // what makes such a series impossible; this keeps the test honest about
            // what it is waiting for and says what it saw if it ever waits in vain.
            await(() -> {
                TimeSeriesData series = view.dataSetForTests().getSeries(ref("a"));
                return series != null && series.getPointCount() == 2 && series.getValues()[0] == 42.0;
            }, "re-extraction of the fresh session's data",
                () -> describe(view.dataSetForTests().getSeries(ref("a"))));
            TimeSeriesData series = view.dataSetForTests().getSeries(ref("a"));
            assertEquals(43.0, series.getValues()[1],
                "the same ref now carries the fresh session's data; saw " + describe(series));
        } finally {
            session.close();
            if (fresh != null) {
                fresh.close();
            }
        }
    }
}
