package com.kalix.ide.dataview;

import com.kalix.ide.flowviz.FlowVizPanel;
import com.kalix.ide.flowviz.VisualizationTabManager;
import com.kalix.ide.flowviz.VizHost;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.DatasetSeries;
import com.kalix.ide.flowviz.data.DefaultLabelResolver;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.flowviz.style.PaletteSeriesStyleResolver;
import com.kalix.ide.flowviz.style.PlotPaletteManager;
import com.kalix.ide.flowviz.style.SeriesSlotManager;
import com.kalix.ide.io.NamedSeries;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Pixie data document's contextual view: the FlowViz unit above the
 * decoded table — the same always-present mount as the CSV viewer
 * ({@link DataVizView}), hosted through the {@link VizHost} seam, but with the
 * CSV extraction machinery gone entirely: pixie data is decoded once by the
 * session, so the plot pool is filled synchronously from the same arrays the
 * table reads. Column-header clicks toggle a series' plotted state (undoable,
 * accent-marked, full-name tooltips); the first series is plotted by default;
 * a reload replaces data under stable {@link DatasetSeries} refs (pxt path +
 * series name), so plot state survives file rewrites.
 */
public final class PixieVizView extends JPanel {

    private static final String PLOTTED_MARK = "● ";

    private final PixieDataPanel tablePanel;
    private final PixieDataSession session;
    private final JSplitPane split;
    private final JLabel note = new JLabel(" ");

    private VisualizationTabManager vizManager;
    private DataSet dataSet;
    private boolean defaultSelectionPending = true;
    /** First layout lands the divider; before it the heights are unknown. */
    private boolean initialDividerApplied = false;
    /** The theme's header renderer we wrapped; refreshed by hand on a LaF switch. */
    private TableCellRenderer wrappedHeaderBase;

    public PixieVizView(PixieDataPanel tablePanel, PixieDataSession session) {
        super(new BorderLayout());
        this.tablePanel = tablePanel;
        this.session = session;

        note.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        tablePanel.setStatusAccessory(note);

        buildViz();
        tablePanel.setMinimumSize(new Dimension(0, 0));
        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vizManager.getTabbedPane(), tablePanel);
        split.setResizeWeight(0); // the table absorbs window resizes
        split.setContinuousLayout(true);
        split.setBorder(null);
        vizManager.getTabbedPane().setMinimumSize(new Dimension(0, 0));
        add(split, BorderLayout.CENTER);

        session.addListener(this::onLoaded);
        if (session.isLoaded()) {
            onLoaded(); // the decode may have finished before this view existed
        }
    }

    /** First layout: land the divider so the plot takes ~45% and the table the rest. */
    @Override
    public void doLayout() {
        if (!initialDividerApplied && getHeight() > 0) {
            initialDividerApplied = true;
            split.setDividerLocation(Math.max(220, (int) (getHeight() * 0.45)));
        }
        super.doLayout();
    }

    /**
     * The wrapped header renderer is not a child component, so a LaF/theme
     * switch never reaches it through the component tree — refresh it by hand.
     */
    @Override
    public void updateUI() {
        super.updateUI();
        if (wrappedHeaderBase instanceof JComponent component) {
            component.updateUI();
        }
    }

    private void buildViz() {
        dataSet = new DataSet();
        vizManager = new VisualizationTabManager(dataSet,
            new PaletteSeriesStyleResolver(new SeriesSlotManager(), PlotPaletteManager.getInstance()));
        LabelResolver labels = new DefaultLabelResolver(id -> null); // dataset refs need no run lookup
        vizManager.setHost(new VizHost() {
            @Override
            public LabelResolver labelResolver() {
                return labels;
            }

            @Override
            public File baseDirectory() {
                return session.pxtFile().getAbsoluteFile().getParentFile();
            }

            @Override
            public void onActiveTabChanged() {
                tablePanel.getTable().getTableHeader().repaint(); // the accents are this mount's tree
            }
        });

        VisualizationTabManager.TabSettings settings = VisualizationTabManager.TabSettings.getDefaults();
        settings.selectedSeries = new LinkedHashSet<>();
        settings.checkedSources = new LinkedHashSet<>(); // the no-sources host state
        vizManager.addPlotTabFromSettings(settings);
        installHeaderInteractions();
    }

    private String datasetId() {
        return session.pxtFile().getAbsolutePath();
    }

    private DatasetSeries ref(String seriesName) {
        return new DatasetSeries(datasetId(), seriesName);
    }

    /**
     * A decode landed (EDT): refresh the pool under stable refs (add-replaces),
     * scrub series the reload no longer carries, apply the first-series default
     * once, and fit tabs that just gained their first data. Synchronous end to
     * end — pixie's one luxury over the CSV mount's coalesced extraction.
     */
    private void onLoaded() {
        if (session.refusal() != null) {
            // The note and the plot must never contradict: withdraw any data.
            note.setText(session.refusal());
            List<SeriesRef> removed = new ArrayList<>(dataSet.getSeriesRefs());
            for (SeriesRef r : removed) {
                dataSet.removeSeries(r);
            }
            for (StatsTableModel model : vizManager.getAllStatsModels()) {
                for (SeriesRef r : removed) {
                    model.removeSeries(r);
                }
            }
            vizManager.updateAllTabs(false);
            tablePanel.getTable().getTableHeader().repaint();
            return;
        }
        note.setText(" ");

        Set<SeriesRef> fresh = new LinkedHashSet<>();
        for (NamedSeries ns : session.decodedSeries()) {
            DatasetSeries r = ref(ns.name());
            fresh.add(r);
            dataSet.addSeries(r, ns.data()); // add-replaces: rewrites keep refs, zoom survives
            vizManager.updateSeriesInStatsTabsWithAggregation(r, ns.data());
        }
        List<SeriesRef> vanished = new ArrayList<>();
        for (SeriesRef r : dataSet.getSeriesRefs()) {
            if (!fresh.contains(r)) {
                vanished.add(r);
            }
        }
        if (!vanished.isEmpty()) {
            vizManager.removeSeriesFromAllTabs(vanished);
            for (SeriesRef r : vanished) {
                dataSet.removeSeries(r);
            }
        }

        if (defaultSelectionPending && session.seriesCount() > 0) {
            // First series plotted by default, so the region is never blank.
            defaultSelectionPending = false;
            DatasetSeries first = ref(session.seriesName(0));
            Set<SeriesRef> selection = new LinkedHashSet<>(vizManager.getTargetTabSelectedSeries());
            selection.add(first);
            vizManager.setTargetTabSelectedSeries(selection);
            vizManager.pushTargetTabHistory();
            vizManager.updateTab(vizManager.getTargetVizPanel(), true); // first data: fit
        }
        vizManager.updateAllTabs(false);
        tablePanel.getTable().getTableHeader().repaint();
    }

    // --- Column-header selection (columns are series: column 0 is the date axis) ---

    private void installHeaderInteractions() {
        JTableHeader header = tablePanel.getTable().getTableHeader();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int viewColumn = header.columnAtPoint(e.getPoint());
                if (viewColumn >= 0) {
                    toggleSeriesColumn(tablePanel.getTable().convertColumnIndexToModel(viewColumn));
                }
            }
        });
        TableCellRenderer base = header.getDefaultRenderer();
        wrappedHeaderBase = base;
        header.setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            Component c = base.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (c instanceof JLabel label) {
                // Full series name as the tooltip: dotted names clip easily.
                label.setToolTipText(label.getText());
                int modelColumn = table.convertColumnIndexToModel(column);
                if (modelColumn > 0 && plottedSeriesNames().contains(label.getText())) {
                    label.setText(PLOTTED_MARK + label.getText());
                }
            }
            return c;
        });
    }

    /**
     * Toggles a series column's plotted state on the target tab — undoable,
     * like the CSV mount's header clicks and the Run Manager's tree ticks. The
     * data is already in the pool, so a first selection fits immediately.
     */
    void toggleSeriesColumn(int modelColumn) {
        if (modelColumn <= 0 || modelColumn > session.seriesCount()) {
            return; // column 0 is the date axis
        }
        DatasetSeries target = ref(session.seriesName(modelColumn - 1));
        Set<SeriesRef> next = new LinkedHashSet<>(vizManager.getTargetTabSelectedSeries());
        boolean wasEmpty = next.isEmpty();
        if (!next.remove(target)) {
            next.add(target);
        }
        vizManager.setTargetTabSelectedSeries(next);
        vizManager.pushTargetTabHistory();
        if (wasEmpty && !next.isEmpty()) {
            vizManager.updateTab(vizManager.getTargetVizPanel(), true); // this tab showed nothing: fit
        }
        tablePanel.getTable().getTableHeader().repaint();
    }

    /** Series names plotted on the target tab (for the header accents). */
    private Set<String> plottedSeriesNames() {
        Set<String> names = new LinkedHashSet<>();
        String id = datasetId();
        for (SeriesRef r : vizManager.getTargetTabSelectedSeries()) {
            if (r instanceof DatasetSeries d && d.datasetId().equals(id)) {
                names.add(d.baseName());
            }
        }
        return names;
    }

    // --- Test seams ---

    /** The viz unit — package-private, for tests. */
    VisualizationTabManager vizManagerForTests() {
        return vizManager;
    }

    /** The private per-mount data pool — package-private, for tests. */
    DataSet dataSetForTests() {
        return dataSet;
    }

    /** The status strip's note — package-private, for tests. */
    String noteText() {
        return note.getText();
    }
}
