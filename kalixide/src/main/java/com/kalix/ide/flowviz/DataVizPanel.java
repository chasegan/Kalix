package com.kalix.ide.flowviz;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.dataview.DataFindController;
import com.kalix.ide.dataview.FindableData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.transform.PlotType;
import com.kalix.ide.io.TimeSeriesCsvExporter;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A visualization tab's Data page: the series the plot draws (after aggregation,
 * masking and the plot-type transform) as a table, spelled exactly as the plot's
 * Save Data writes them.
 *
 * <p>The page holds no data of its own. It listens to the tab's {@link FlowVizModel} and,
 * when shown, builds an immutable {@link DataGrid} from the model's display set — the
 * same object the plot draws, so the two agree by construction; while hidden it only
 * notes that it is out of date. The table model and the unified Find both read that
 * one snapshot, and because Find's staleness check is identity, a scan started
 * against a replaced grid is dropped.
 */
public final class DataVizPanel extends JPanel {

    private final DataGridTableModel tableModel = new DataGridTableModel();
    private LabelResolver labelResolver;
    private final JTable table;
    private final FlowVizModel model;
    private boolean shown;
    private boolean dirty = true;
    // What the current grid was built from: an unchanged display set and headers keep
    // the grid, so a running Find survives refreshes that change nothing (new data
    // restarts it; the selection and column widths are carried over). Headers are
    // compared, not assumed: a rename changes the labels under an unchanged display set.
    private DataSet gridDisplay;
    private PlotType gridPlotType;
    private List<String> gridHeaders;

    /** The unified Find, shared by every tabular view via {@link FindableData}. */
    private final DataFindController findController;

    public DataVizPanel(FlowVizModel model) {
        super(new BorderLayout());
        this.model = model;
        model.addListener((old, now, cause) -> {
            if (cause != FlowVizModel.Cause.USER || now.differsInPlotFrom(old)) {
                refresh();
            }
        });

        table = new JTable(tableModel);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // wide files scroll horizontally
        table.setFillsViewportHeight(true);
        // House table styling (matching TableView / the Optimiser tables): faint
        // grid lines for cell visibility. FlatLaf defaults intercell spacing to
        // 0,0, which hides the grid even when shown.
        table.setRowHeight(UIConstants.TableView.ROW_HEIGHT);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        applyGridColor();
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setCellSelectionEnabled(true);
        this.findController = new DataFindController(table, this, tableModel::grid);
        installInteractions();
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    /** Context menu + keyboard interactions: Find, Copy. */
    private void installInteractions() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem find = new JMenuItem("Find…"); // one Find: dates, values and columns are dialog scopes
        find.addActionListener(e -> openFind());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> {
            // JTable's built-in copy: selected cells as tab-delimited lines.
            Action builtIn = table.getActionMap().get("copy");
            if (builtIn != null) {
                builtIn.actionPerformed(
                    new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });
        menu.add(find);
        menu.addSeparator();
        menu.add(copy);
        table.setComponentPopupMenu(menu);

        // Right-clicking outside the selection moves it there first, so the menu
        // acts on the cell under the cursor (matching platform convention).
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeSelect(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeSelect(e);
            }

            private void maybeSelect(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                int column = Math.max(0, table.columnAtPoint(e.getPoint()));
                if (row >= 0 && !table.isCellSelected(row, column)) {
                    table.changeSelection(row, column, false, false);
                }
            }
        });

        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
             .put(
                 KeyStroke.getKeyStroke(
                     KeyEvent.VK_F, AppShortcut.menuMask()), "data-find"
             );
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
             .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "data-find-next");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
             .put(
                 KeyStroke.getKeyStroke(
                     KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "data-find-previous"
             );
        table.getActionMap().put(
            "data-find", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    openFind();
                }
            }
        );
        table.getActionMap().put(
            "data-find-next", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    repeatFind(true);
                }
            }
        );
        table.getActionMap().put(
            "data-find-previous", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    repeatFind(false);
                }
            }
        );
    }

    /** Shows or hides the page; a page shown while out of date rebuilds at once. */
    public void setShown(boolean shown) {
        this.shown = shown;
        if (shown && dirty) {
            rebuild();
        }
    }

    /**
     * Something under the page changed: rebuild now if shown, else when shown. A hidden
     * page lets go of a grid the model no longer holds the display set of (its data was
     * replaced or dropped, e.g. Last moved) or that shows a series the tab no longer
     * selects, so it keeps nothing alive that the pool has released.
     */
    public void refresh() {
        if (shown) {
            rebuild();
        } else {
            dirty = true;
            if (gridDisplay != null && (!model.holdsDisplay(gridDisplay)
                    || !model.current().getVisibleSeries().containsAll(gridDisplay.getSeriesRefs()))) {
                gridDisplay = null;
                tableModel.setGrid(DataGrid.EMPTY);
            }
        }
    }

    /** Whether the hidden page has missed a change — package-private, for tests. */
    boolean isDirty() {
        return dirty;
    }

    private void rebuild() {
        dirty = false;
        DataSet display = model.display();
        PlotType plotType = model.current().getPlotType();
        List<String> headers = TimeSeriesCsvExporter.dataColumnHeaders(display, labelResolver);
        if (display == gridDisplay && plotType == gridPlotType && headers.equals(gridHeaders)) {
            return;
        }
        gridDisplay = display;
        gridPlotType = plotType;
        gridHeaders = headers;
        List<int[]> rows = intervals(table.getSelectionModel());
        List<int[]> columns = intervals(table.getColumnModel().getSelectionModel());
        tableModel.setGrid(DataGrid.of(display, plotType, labelResolver));
        // New data clears the table's selection; put back what still exists.
        restore(table.getSelectionModel(), rows, table.getRowCount());
        restore(table.getColumnModel().getSelectionModel(), columns, table.getColumnCount());
    }

    private static List<int[]> intervals(ListSelectionModel selection) {
        List<int[]> intervals = new ArrayList<>();
        int min = selection.getMinSelectionIndex();
        int max = selection.getMaxSelectionIndex();
        for (int i = min; min >= 0 && i <= max; i++) {
            if (selection.isSelectedIndex(i)) {
                int start = i;
                while (i < max && selection.isSelectedIndex(i + 1)) {
                    i++;
                }
                intervals.add(new int[] {start, i});
            }
        }
        return intervals;
    }

    private static void restore(ListSelectionModel selection, List<int[]> intervals, int count) {
        selection.setValueIsAdjusting(true);
        try {
            for (int[] interval : intervals) {
                if (interval[0] < count) {
                    selection.addSelectionInterval(interval[0], Math.min(interval[1], count - 1));
                }
            }
        } finally {
            selection.setValueIsAdjusting(false);
        }
    }

    /** The snapshot currently shown — package-private, for tests. */
    DataGrid getGrid() {
        return tableModel.grid();
    }

    public void setLabelResolver(LabelResolver labelResolver) {
        this.labelResolver = labelResolver;
        refresh();
    }

    /** Opens the unified Find — public: the above-gate text side and menu routing share it. */
    public void openFind() {
        findController.openFind();
    }

    /** Repeats the last find (F3 semantics), opening the dialog when there is none. */
    public void repeatFind(boolean forward) {
        findController.repeatFind(forward);
    }

    /** Re-resolves the theme's grid colour after a LaF switch. Null-guarded: runs during JPanel's constructor too. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (table != null) {
            applyGridColor();
        }
    }

    private void applyGridColor() {
        Color gridColor = UIManager.getColor("Table.gridColor");
        table.setGridColor(
            gridColor != null ? gridColor : UIConstants.TableView.FALLBACK_GRID_COLOR);
    }

    /** The underlying table — package-private, for tests. */
    JTable getTable() {
        return table;
    }
}
