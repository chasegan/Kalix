package com.kalix.ide.flowviz;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.dataview.DataFindController;
import com.kalix.ide.dataview.FindableData;
import com.kalix.ide.flowviz.data.LabelResolver;

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

/**
 * A visualization tab's Data page: the series the plot draws (after aggregation,
 * masking and the plot-type transform) as a table, spelled exactly as the plot's
 * Save Data writes them.
 *
 * <p>The page holds no data of its own. The tab manager hands it an immutable
 * {@link DataGrid} on every refresh; the table model and the unified Find both
 * read that one snapshot, and because Find's staleness check is identity, a scan
 * started against a replaced grid is dropped.
 */
public final class DataVizPanel extends JPanel {

    private final DataGridTableModel tableModel = new DataGridTableModel();
    private LabelResolver labelResolver;
    private final JTable table;

    // Context-menu handles: the viz mount inserts its items around these.
    private JPopupMenu tableMenu;
    private JMenuItem copyItem;

    /** The unified Find, shared by every tabular view via {@link FindableData}. */
    private final DataFindController findController;

    public DataVizPanel() {
        super(new BorderLayout());

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
        this.tableMenu = menu;
        JMenuItem find = new JMenuItem("Find…"); // one Find: dates, values and columns are dialog scopes
        find.addActionListener(e -> openFind());
        JMenuItem copy = new JMenuItem("Copy");
        this.copyItem = copy;
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

    /** Shows a new snapshot. EDT only. */
    void setGrid(DataGrid grid) {
        tableModel.setGrid(grid);
    }

    /** The snapshot currently shown — package-private, for tests. */
    DataGrid getGrid() {
        return tableModel.grid();
    }

    public void setLabelResolver(LabelResolver labelResolver) {
        this.labelResolver = labelResolver;
    }

    /** The labels the next snapshot is built with; null leaves the export's fallback. */
    LabelResolver labelResolver() {
        return labelResolver;
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
