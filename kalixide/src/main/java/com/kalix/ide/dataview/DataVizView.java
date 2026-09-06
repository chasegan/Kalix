package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.VizHost;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.style.PaletteSeriesStyleResolver;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.preferences.PreferenceKeys;

import com.formdev.flatlaf.FlatClientProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A data document's contextual view once it can plot: the FlowViz unit above
 * the virtual table, sharing one screen. The plot region is <b>collapsed by
 * default</b> (divider at the top edge, size 0 — the {@code DocumentSplitView}
 * idiom) behind a slim "Plot" header strip; expanding it lazily builds a full
 * {@link VisualizationTabManager} mount — tabs, plot ⁄ stats toggle, shared
 * aggregation, undo — over a private per-mount {@link DataSet}.
 *
 * <h2>Column selection</h2>
 * While the plot is open, clicking a column header toggles that column's
 * plotted state on the target tab (the header renders an accent mark), pushed
 * to the tab's history like any other selection change. Series are identified
 * as {@link DatasetSeries}(absolute path, column name) — stable across session
 * rebuilds, so plot state survives file refreshes. The first data column is
 * plotted by default so the expanded region is never blank.
 *
 * <h2>Materialisation</h2>
 * Plotting needs whole columns in memory (unlike the virtual table), so every
 * pass is bounded by {@link PreferenceKeys#DATAVIEW_PLOT_MAX_ROWS} and refused
 * honestly — a note in the header strip, never a dialog. Extraction runs on a
 * single coalescing worker (the {@code DataDocument} drain-loop pattern): a
 * burst of triggers costs one streamed re-read via
 * {@link ColumnSeriesExtractor}.
 *
 * <h2>Lifecycle</h2>
 * A session rebuild ({@link #onSessionReplaced}) re-extracts every plotted
 * column from the fresh session — {@code DataSet.addSeries} replaces under the
 * same refs, zoom preserved; columns that vanished are scrubbed. The live tail
 * (append-resume) re-extracts on each indexing-complete event.
 */
public final class DataVizView extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(DataVizView.class);

    private static final String COLLAPSED_TEXT = "▸ Plot";
    private static final String EXPANDED_TEXT = "▾ Plot";
    private static final String PLOTTED_MARK = "● ";

    private final DataViewPanel tablePanel;
    private final JSplitPane split;
    private final int defaultDividerSize;
    private final JButton toggleButton = new JButton(COLLAPSED_TEXT);
    private final JLabel note = new JLabel(" ");
    private final LongSupplier rowLimit;

    /** Swapped by {@link #onSessionReplaced}; extraction passes snapshot it. */
    private volatile DataViewSession session;

    // Built lazily on first expand — a collapsed region costs nothing.
    private VisualizationTabManager vizManager;
    private DataSet dataSet;
    private boolean expanded = false;
    /** First-expand default selection still owed (structure unknown at build time). */
    private boolean defaultSelectionPending = false;

    /** Set by every extraction trigger; drained by the single extraction worker. */
    private final AtomicBoolean extractRequested = new AtomicBoolean(false);
    private final AtomicBoolean extractInFlight = new AtomicBoolean(false);
    private volatile boolean disposed = false;

    public DataVizView(DataViewPanel tablePanel, DataViewSession session) {
        this(tablePanel, session, () -> PreferenceKeys.DATAVIEW_PLOT_MAX_ROWS.get());
    }

    /** Test seam: the row limit is injectable so tests need no preference writes. */
    DataVizView(DataViewPanel tablePanel, DataViewSession session, LongSupplier rowLimit) {
        super(new BorderLayout());
        this.tablePanel = tablePanel;
        this.session = session;
        this.rowLimit = rowLimit;

        // Slim header affordance: the toggle plus an honest status note (refusal
        // reasons, skipped-row counts) — information in the strip, never a dialog.
        toggleButton.setFocusable(false);
        toggleButton.setToolTipText("Show or hide the plot region");
        toggleButton.putClientProperty(FlatClientProperties.BUTTON_TYPE,
            FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        toggleButton.addActionListener(e -> setExpanded(!expanded));
        note.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        JPanel strip = new JPanel(new BorderLayout());
        strip.add(toggleButton, BorderLayout.WEST);
        strip.add(note, BorderLayout.CENTER);
        add(strip, BorderLayout.NORTH);

        // The placeholder keeps the split's geometry stable until the viz unit is
        // built; once built, the unit stays mounted and only the divider moves
        // (re-parenting on every toggle would trip panel teardown and flicker).
        JPanel placeholder = new JPanel();
        placeholder.setMinimumSize(new Dimension(0, 0));
        tablePanel.setMinimumSize(new Dimension(0, 0));
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, placeholder, tablePanel);
        split.setResizeWeight(0); // the table absorbs window resizes
        split.setContinuousLayout(true);
        split.setBorder(null);
        this.defaultDividerSize = split.getDividerSize();
        split.setDividerSize(0);
        split.setDividerLocation(0);
        add(split, BorderLayout.CENTER);

        registerSessionListener(session);
    }

    private void registerSessionListener(DataViewSession target) {
        target.addListener(new DataViewSession.Listener() {
            @Override
            public void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
                // Live tail: each completed index pass (initial or append-resume)
                // is the moment the plotted columns may have grown.
                if (complete && vizManager != null && expanded) {
                    scheduleExtraction();
                }
            }

            @Override
            public void onStructureKnown() {
                maybeApplyDefaultSelection();
            }
        });
    }

    /**
     * Follows {@code DataDocument.refreshDataViewFromDisk}'s rebuild path: the
     * fresh session replaces the old one and every plotted column re-extracts
     * from it. Plot state — selection, aggregation, mask, undo history —
     * survives untouched because refs are path+column-name stable. EDT only.
     */
    public void onSessionReplaced(DataViewSession fresh) {
        this.session = fresh;
        registerSessionListener(fresh);
        if (vizManager != null) {
            scheduleExtraction();
        }
    }

    /** Stops future extraction passes; in-flight ones abandon their work. */
    public void dispose() {
        disposed = true;
    }

    // --- Expand / collapse -------------------------------------------------

    void setExpanded(boolean expand) {
        if (expanded == expand) {
            return;
        }
        expanded = expand;
        if (expand) {
            if (vizManager == null) {
                buildViz();
            }
            split.setDividerSize(defaultDividerSize);
            split.setDividerLocation(Math.max(220, (int) (getHeight() * 0.45)));
            scheduleExtraction();
        } else {
            split.setDividerSize(0);
            split.setDividerLocation(0);
        }
        toggleButton.setText(expand ? EXPANDED_TEXT : COLLAPSED_TEXT);
        tablePanel.getTable().getTableHeader().repaint(); // accents show only while open
        revalidate();
        repaint();
    }

    private void buildViz() {
        dataSet = new DataSet();
        vizManager = new VisualizationTabManager(dataSet,
            new PaletteSeriesStyleResolver(new SeriesSlotManager(), PlotPaletteManager.getInstance()));
        LabelResolver labels = new DefaultLabelResolver(id -> null); // no runs here: dataset refs only
        vizManager.setHost(new VizHost() {
            @Override
            public LabelResolver labelResolver() {
                return labels;
            }

            @Override
            public File baseDirectory() {
                DataViewSession current = session;
                return current != null ? current.filePath().toAbsolutePath().getParent().toFile() : null;
            }

            @Override
            public void onActiveTabChanged() {
                // The header accents are this mount's "tree": reproject them, and
                // fetch any selected column (undo/redo can restore one) whose data
                // the pool doesn't hold yet.
                tablePanel.getTable().getTableHeader().repaint();
                for (SeriesRef ref : vizManager.getTargetTabSelectedSeries()) {
                    if (!dataSet.hasSeries(ref)) {
                        scheduleExtraction();
                        return;
                    }
                }
            }
        });

        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>(); // the no-sources host state
        String firstColumn = firstDataColumnName();
        if (firstColumn != null) {
            // First data column plotted by default, so the region is never blank.
            settings.selectedSeries.add(new DatasetSeries(datasetId(), firstColumn));
            defaultSelectionPending = false;
        } else {
            defaultSelectionPending = true; // structure not indexed yet; owed on arrival
        }
        vizManager.addPlotTabFromSettings(settings);

        split.setTopComponent(vizManager.getTabbedPane());
        vizManager.getTabbedPane().setMinimumSize(new Dimension(0, 0));
        installHeaderInteractions();
    }

    private void maybeApplyDefaultSelection() {
        if (vizManager == null || !defaultSelectionPending) {
            return;
        }
        String firstColumn = firstDataColumnName();
        if (firstColumn == null) {
            return;
        }
        defaultSelectionPending = false;
        Set<SeriesRef> selection = new LinkedHashSet<>(vizManager.getTargetTabSelectedSeries());
        selection.add(new DatasetSeries(datasetId(), firstColumn));
        vizManager.setTargetTabSelectedSeries(selection);
        vizManager.pushTargetTabHistory();
        scheduleExtraction();
    }

    private String firstDataColumnName() {
        DataViewSession current = session;
        return current != null && current.columnCount() >= 2
            ? VirtualDataTableModel.columnName(current, 1) : null;
    }

    private String datasetId() {
        return session.filePath().toAbsolutePath().toString();
    }

    // --- Column-header selection ------------------------------------------

    private void installHeaderInteractions() {
        JTableHeader header = tablePanel.getTable().getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || !expanded) {
                    return;
                }
                int viewColumn = header.columnAtPoint(e.getPoint());
                if (viewColumn >= 0) {
                    toggleColumn(tablePanel.getTable().convertColumnIndexToModel(viewColumn));
                }
            }
        });
        // Decorate the theme's own renderer rather than replacing it: plotted
        // columns get an accent mark while the plot region is open.
        TableCellRenderer base = header.getDefaultRenderer();
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            Component c = base.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel label && expanded) {
                int modelColumn = table.convertColumnIndexToModel(column);
                if (modelColumn > 0 && plottedColumnNames().contains(label.getText())) {
                    label.setText(PLOTTED_MARK + label.getText());
                }
            }
            return c;
        });
        header.setToolTipText("Click a column to plot it (or unplot it)");
    }

    /**
     * Toggles a column's plotted state on the target tab — an undoable
     * selection change, exactly like a tree tick in the Run Manager. Column 0
     * is the date axis and cannot be toggled.
     */
    void toggleColumn(int modelColumn) {
        if (vizManager == null || !expanded || modelColumn <= 0) {
            return;
        }
        DataViewSession current = session;
        if (current == null) {
            return;
        }
        DatasetSeries ref = new DatasetSeries(datasetId(),
            VirtualDataTableModel.columnName(current, modelColumn));
        Set<SeriesRef> next = new LinkedHashSet<>(vizManager.getTargetTabSelectedSeries());
        if (!next.remove(ref)) {
            next.add(ref);
        }
        vizManager.setTargetTabSelectedSeries(next);
        vizManager.pushTargetTabHistory();
        scheduleExtraction();
        tablePanel.getTable().getTableHeader().repaint();
    }

    /** Column names plotted on the target tab (for the header accents). */
    private Set<String> plottedColumnNames() {
        Set<String> names = new LinkedHashSet<>();
        if (vizManager != null) {
            String id = datasetId();
            for (SeriesRef ref : vizManager.getTargetTabSelectedSeries()) {
                if (ref instanceof DatasetSeries d && d.datasetId().equals(id)) {
                    names.add(d.baseName());
                }
            }
        }
        return names;
    }

    // --- Extraction --------------------------------------------------------

    /** Requests a (coalesced) extraction pass. Safe from any thread. */
    private void scheduleExtraction() {
        extractRequested.set(true);
        maybeStartExtractWorker();
    }

    private void maybeStartExtractWorker() {
        if (!extractInFlight.compareAndSet(false, true)) {
            return; // the running worker drains extractRequested before exiting
        }
        Thread worker = new Thread(() -> {
            try {
                while (!disposed && extractRequested.getAndSet(false)) {
                    extractOnce();
                }
            } finally {
                extractInFlight.set(false);
                if (extractRequested.get() && !disposed) {
                    maybeStartExtractWorker();
                }
            }
        }, "kalix-dataview-extract");
        worker.setDaemon(true);
        worker.start();
    }

    /** One coalesced pass: snapshot the wanted columns, stream them, publish. Worker thread. */
    private void extractOnce() {
        DataViewSession target = session;
        if (target == null || vizManager == null) {
            return;
        }
        Set<SeriesRef> selected = onEdtGet(() -> new LinkedHashSet<>(vizManager.getAllSelectedSeriesAcrossTabs()));
        if (selected == null) {
            return; // interrupted mid-snapshot; a later trigger will retry
        }

        long limit = rowLimit.getAsLong();
        long dataRows = Math.max(0, target.rowCount() - (target.headerRowInData() ? 1 : 0));
        if (dataRows > limit) {
            setNote(String.format(
                "Plot disabled: %,d rows exceeds the %,d-row limit (Preferences → Editor → Load and Save)",
                dataRows, limit));
            return;
        }

        // Map the wanted refs onto the session's current columns by name; a ref
        // whose column no longer exists (header renamed) is scrubbed below.
        String id = target.filePath().toAbsolutePath().toString();
        List<DatasetSeries> wanted = new ArrayList<>();
        for (SeriesRef ref : selected) {
            if (ref instanceof DatasetSeries d && d.datasetId().equals(id)) {
                wanted.add(d);
            }
        }
        List<DatasetSeries> found = new ArrayList<>();
        List<SeriesRef> vanished = new ArrayList<>();
        int columnCount = target.columnCount();
        int[] indices = new int[wanted.size()];
        int n = 0;
        for (DatasetSeries d : wanted) {
            int index = -1;
            for (int col = 1; col < columnCount; col++) {
                if (VirtualDataTableModel.columnName(target, col).equals(d.baseName())) {
                    index = col;
                    break;
                }
            }
            if (index >= 0) {
                found.add(d);
                indices[n++] = index;
            } else if (columnCount > 0) {
                vanished.add(d);
            }
        }
        int[] columnIndices = java.util.Arrays.copyOf(indices, n);
        if (columnIndices.length == 0 && vanished.isEmpty()) {
            setNote(" ");
            return;
        }

        ColumnSeriesExtractor.Result result = null;
        if (columnIndices.length > 0) {
            try {
                result = ColumnSeriesExtractor.extract(
                    target.filePath(), target.dialect(), target.dataStartOffset(),
                    target.headerRowInData(), columnIndices, limit,
                    () -> disposed || session != target);
            } catch (IOException e) {
                logger.warn("Column extraction failed for {}: {}", target.filePath(), e.getMessage());
                setNote("Plot unavailable: " + e.getMessage());
                return;
            }
            if (result == null) {
                return; // cancelled (disposed or session swapped): a new pass follows
            }
            if (result.refused()) {
                setNote("Plot unavailable: " + result.refusal());
                return;
            }
        }

        publish(target, found, result, vanished);
    }

    /** Lands one pass's outcome on the EDT: replace series data, scrub the vanished, refresh. */
    private void publish(DataViewSession target, List<DatasetSeries> found,
                         ColumnSeriesExtractor.Result result, List<SeriesRef> vanished) {
        SwingUtilities.invokeLater(() -> {
            if (disposed || session != target || vizManager == null) {
                return;
            }
            if (result != null) {
                long[] timestamps = result.timestamps();
                for (int i = 0; i < found.size(); i++) {
                    TimeSeriesData data = new TimeSeriesData(timestamps, result.columns()[i]);
                    dataSet.addSeries(found.get(i), data); // add-replaces: rebuilds keep refs
                    vizManager.updateSeriesInStatsTabsWithAggregation(found.get(i), data);
                }
            }
            if (!vanished.isEmpty()) {
                vizManager.removeSeriesFromAllTabs(vanished);
                for (SeriesRef ref : vanished) {
                    dataSet.removeSeries(ref);
                }
            }
            vizManager.updateAllTabs(false); // zoom preserved across refreshes
            note.setText(result != null && result.badDateRows() > 0
                ? String.format("%,d rows skipped: unparseable dates", result.badDateRows())
                : " ");
            tablePanel.getTable().getTableHeader().repaint();
        });
    }

    private void setNote(String text) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                note.setText(text);
            }
        });
    }

    /** Runs a read on the EDT and returns its result; {@code null} if interrupted. */
    private static <T> T onEdtGet(Supplier<T> read) {
        if (SwingUtilities.isEventDispatchThread()) {
            return read.get();
        }
        List<T> holder = new ArrayList<>(1);
        try {
            SwingUtilities.invokeAndWait(() -> holder.add(read.get()));
        } catch (InterruptedException | InvocationTargetException e) {
            return null;
        }
        return holder.isEmpty() ? null : holder.get(0);
    }

    // --- Test seams --------------------------------------------------------

    /** The viz unit, once built ({@code null} while collapsed) — package-private, for tests. */
    VisualizationTabManager vizManagerForTests() {
        return vizManager;
    }

    /** The private per-mount data pool — package-private, for tests. */
    DataSet dataSetForTests() {
        return dataSet;
    }

    /** The header strip's current note — package-private, for tests. */
    String noteText() {
        return note.getText();
    }

    /** Whether the plot region is currently expanded — package-private, for tests. */
    boolean isExpanded() {
        return expanded;
    }
}
