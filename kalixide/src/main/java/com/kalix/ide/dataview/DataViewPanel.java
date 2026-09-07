package com.kalix.ide.dataview;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.constants.UIConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * The table projection mounted as a data document's contextual view: a virtual
 * {@link JTable} over the {@link DataViewSession}, with a status strip that
 * declares the sniffer's decisions — delimiter, encoding, line endings, row
 * count — so the interpretation is visible, never silent. While the background
 * index runs the strip carries a live progress figure and the table grows.
 */
public final class DataViewPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(DataViewPanel.class);

    /** Non-final: replaced with a fresh session after the file is rebuilt from disk. */
    private DataViewSession session;
    private final JTable table;
    private final JLabel status = new JLabel();
    private final JPanel statusBar = new JPanel(new BorderLayout());

    // Context-menu handles: the viz mount inserts its items around these.
    private JPopupMenu tableMenu;
    private JMenuItem showInFileItem;
    private JMenuItem copyItem;

    // Unified Find (dialog lazily created — it needs a display).
    private DataFindDialog findDialog;
    /** Column of the last find landing on a header, or null; header positions precede all cells. */
    private Integer lastHeaderLanding;
    /** True while find moves the selection itself, so the re-anchor listener ignores it. */
    private boolean programmaticFindSelection;

    /** Receives the data-region physical line for "Show in file" (wired by the host document). */
    private LongConsumer showInFileHandler;
    /** One search / one line-mapping at a time: holding F3 must not stack full-file scans. */
    private final AtomicBoolean searchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean lineMapInFlight = new AtomicBoolean(false);

    public DataViewPanel(DataViewSession session) {
        super(new BorderLayout());
        this.session = session;

        table = new JTable(new VirtualDataTableModel(session));
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
        installInteractions();
        add(new JScrollPane(table), BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        statusBar.add(status, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        refreshStatus();
        registerStatusListener();
    }

    /**
     * Installs a leading accessory in the status strip — the data-viz mount's
     * status note (refusals, skipped-row counts) lives here, beside the
     * dialect facts, rather than as a full-width row that would read as a title.
     */
    public void setStatusAccessory(JComponent accessory) {
        statusBar.add(accessory, BorderLayout.WEST);
        statusBar.revalidate();
    }

    private void registerStatusListener() {
        session.addListener(new DataViewSession.Listener() {
            @Override
            public void onProgress(long rows, long lines, long indexedBytes, long totalBytes, boolean complete) {
                refreshStatus();
            }

            @Override
            public void onStructureKnown() {
                refreshStatus();
            }
        });
    }

    /**
     * Swaps in a freshly opened session after the file was rebuilt from disk
     * (stale checkpoints would otherwise parse the new bytes at old offsets).
     * EDT only; the caller ({@code DataDocument.refreshDataViewFromDisk}) closes
     * the old session after this returns.
     */
    public void replaceSession(DataViewSession fresh) {
        this.session = fresh;
        table.setModel(new VirtualDataTableModel(fresh));
        registerStatusListener();
        refreshStatus();
    }

    private void refreshStatus() {
        CsvDialect dialect = session.dialect();
        String delimiter = switch (dialect.delimiter()) {
            case '\t' -> "tab";
            default -> "\"" + dialect.delimiter() + "\"";
        };
        long dataRows = Math.max(0, session.rowCount() - (session.headerRowInData() ? 1 : 0));

        StringBuilder text = new StringBuilder();
        text.append("delimiter ").append(delimiter)
            .append("  ·  quote ").append(dialect.quote())
            .append("  ·  ").append(dialect.charset().name())
            .append("  ·  ").append(dialect.lineEndingLabel())
            .append("  ·  ").append(String.format("%,d rows", dataRows));
        if (!session.isIndexingComplete() && session.totalBytes() > 0) {
            text.append(String.format("  ·  indexing %d%%",
                100 * session.indexedBytes() / session.totalBytes()));
        }
        status.setText(text.toString());
    }

    /** Wires the host's "Show in File" navigation (receives a data-region physical line, on the EDT). */
    public void setShowInFileHandler(LongConsumer handler) {
        this.showInFileHandler = handler;
    }

    /** Context menu + keyboard interactions: Find, Show in File, Copy. */
    private void installInteractions() {
        JPopupMenu menu = new JPopupMenu();
        this.tableMenu = menu;
        JMenuItem find = new JMenuItem("Find…"); // one Find: dates, values and columns are dialog scopes
        find.addActionListener(e -> showFindDialog());
        JMenuItem showInFile = new JMenuItem("Show in file"); // sentence case per context-menu-style §2.1
        this.showInFileItem = showInFile;
        showInFile.addActionListener(e -> showSelectedRowInFile());
        JMenuItem copy = new JMenuItem("Copy");
        this.copyItem = copy;
        copy.addActionListener(e -> {
            // JTable's built-in copy: selected cells as tab-delimited lines.
            Action builtIn = table.getActionMap().get("copy");
            if (builtIn != null) {
                builtIn.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });
        menu.add(find);
        menu.addSeparator();
        menu.add(showInFile);
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
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, AppShortcut.menuMask()), "data-find");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "data-find-next");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "data-find-previous");
        table.getActionMap().put("data-find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showFindDialog();
            }
        });
        table.getActionMap().put("data-find-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findAgain(true);
            }
        });
        table.getActionMap().put("data-find-previous", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findAgain(false);
            }
        });

        // A manual selection change repositions the find origin (mirroring the
        // editor's caret re-anchor) and retires any header landing.
        javax.swing.event.ListSelectionListener reanchorFind = e -> {
            if (!programmaticFindSelection) {
                lastHeaderLanding = null;
            }
        };
        table.getSelectionModel().addListSelectionListener(reanchorFind);
        table.getColumnModel().getSelectionModel().addListSelectionListener(reanchorFind);
    }

    /** Shows the unified Find dialog, pre-filled from the selected cell. */
    private void showFindDialog() {
        if (findDialog == null) {
            findDialog = new DataFindDialog(this);
        }
        String prefill = null;
        int viewRow = table.getSelectedRow();
        int viewColumn = table.getSelectedColumn();
        if (viewRow >= 0 && viewColumn >= 0) {
            Object value = table.getValueAt(viewRow, viewColumn);
            prefill = value != null ? value.toString() : null;
        }
        findDialog.showOver(prefill);
    }

    /** F3 / Shift-F3: repeat the last search, or open the dialog when there is none. */
    private void findAgain(boolean forward) {
        if (findDialog == null || findDialog.queryText().isBlank()) {
            showFindDialog();
        } else {
            runFind(forward);
        }
    }

    /**
     * One find step: snapshot the spec and origin on the EDT, stream the scan
     * on a worker, choose the landing (header columns first, then cells — see
     * {@link DataFindNavigator}) and land it back on the EDT with inline
     * status, editor-style.
     */
    void runFind(boolean forward) {
        DataViewSession.FindSpec spec = findDialog.spec();
        if (spec.query().isEmpty()) {
            findDialog.setStatus(" ", false);
            return;
        }
        if (!searchInFlight.compareAndSet(false, true)) {
            return; // a scan is already running ("a 1GB file takes a few seconds")
        }
        List<Integer> headerCols = findDialog.columnsScope() ? matchingColumns(spec) : List.of();
        boolean wrap = findDialog.wrapEnabled();
        long headerOffset = session.headerRowInData() ? 1 : 0;
        long fromRow;
        int fromColumn;
        if (lastHeaderLanding != null) {
            fromRow = -1;
            fromColumn = lastHeaderLanding;
        } else if (table.getSelectedRow() >= 0) {
            fromRow = table.getSelectedRow() + headerOffset;
            fromColumn = Math.max(0, selectedModelColumn());
        } else {
            fromRow = forward ? -2 : Long.MAX_VALUE;
            fromColumn = 0;
        }
        DataViewSession target = session;
        long fromRowFinal = fromRow;
        int fromColumnFinal = fromColumn;
        Thread searcher = new Thread(() -> {
            try {
                DataViewSession.FindScan scan = target.scanForMatches(spec, fromRowFinal, fromColumnFinal);
                SwingUtilities.invokeLater(() -> {
                    if (target == session) {
                        applyLanding(DataFindNavigator.choose(
                            scan, headerCols, fromRowFinal, fromColumnFinal, forward, wrap), wrap);
                    }
                });
            } catch (IOException e) {
                logger.warn("Data find failed: {}", e.getMessage());
            } finally {
                searchInFlight.set(false);
            }
        }, "kalix-dataview-find");
        searcher.setDaemon(true);
        searcher.start();
    }

    /** Header model columns whose name matches the spec, ascending. */
    private List<Integer> matchingColumns(DataViewSession.FindSpec spec) {
        List<Integer> matches = new ArrayList<>();
        String needle = spec.matchCase() ? spec.query() : spec.query().toLowerCase(Locale.ROOT);
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            String name = table.getModel().getColumnName(column);
            String haystack = spec.matchCase() ? name : name.toLowerCase(Locale.ROOT);
            if (spec.wholeCell() ? haystack.equals(needle) : haystack.contains(needle)) {
                matches.add(column);
            }
        }
        return matches;
    }

    /** Lands one find step: selection, scroll, header bookkeeping, inline status. EDT only. */
    private void applyLanding(DataFindNavigator.Landing landing, boolean wrap) {
        if (landing == null) {
            findDialog.setStatus(wrap ? "No results" : "No more results", true);
            if (!findDialog.isShowing()) {
                Toolkit.getDefaultToolkit().beep(); // F3 with the dialog closed still gets feedback
            }
            return;
        }
        programmaticFindSelection = true;
        try {
            if (landing.row() == -1) {
                lastHeaderLanding = landing.column();
                int viewColumn = table.convertColumnIndexToView(landing.column());
                if (table.getRowCount() > 0 && viewColumn >= 0) {
                    table.changeSelection(0, viewColumn, false, false);
                    table.scrollRectToVisible(table.getCellRect(0, viewColumn, true));
                }
            } else {
                lastHeaderLanding = null;
                long headerOffset = session.headerRowInData() ? 1 : 0;
                int viewRow = (int) Math.min(Integer.MAX_VALUE, landing.row() - headerOffset);
                int viewColumn = table.convertColumnIndexToView(Math.max(0, landing.column()));
                if (viewRow >= 0 && viewRow < table.getRowCount() && viewColumn >= 0) {
                    table.changeSelection(viewRow, viewColumn, false, false);
                    table.scrollRectToVisible(table.getCellRect(viewRow, viewColumn, true));
                }
            }
        } finally {
            programmaticFindSelection = false;
        }
        if (landing.nearestDate()) {
            findDialog.setStatus("No exact match — nearest later date", false);
        } else {
            findDialog.setStatus(landing.ordinal() + " of " + landing.total()
                + (landing.wrapped() ? " (wrapped)" : ""), false);
        }
    }

    /** Maps the selected row to its physical line on a worker, then hands it to the host. */
    private void showSelectedRowInFile() {
        LongConsumer handler = showInFileHandler;
        int viewRow = table.getSelectedRow();
        if (handler == null || viewRow < 0 || !lineMapInFlight.compareAndSet(false, true)) {
            return;
        }
        long fileRow = viewRow + (session.headerRowInData() ? 1 : 0);
        DataViewSession target = session;
        Thread mapper = new Thread(() -> {
            try {
                long line = target.lineNumberForRow(fileRow);
                SwingUtilities.invokeLater(() -> {
                    if (target == session) {
                        handler.accept(line);
                    }
                });
            } catch (IOException e) {
                logger.warn("Show in file failed: {}", e.getMessage());
            } finally {
                lineMapInFlight.set(false);
            }
        }, "kalix-dataview-line-map");
        mapper.setDaemon(true);
        mapper.start();
    }

    /** Actions the data-viz mount contributes to the table's context menu. */
    public interface PlotActions {
        boolean isColumnPlotted(int modelColumn);

        void togglePlotted(int modelColumn);

        /** Centres the plot on the datapoint at (file row, model column). */
        void showInPlot(long fileRow, int modelColumn);
    }

    /**
     * Installs the viz mount's context-menu items: "Show in plot" beside
     * "Show in file", and the dynamic Plot ⁄ Unplot toggle for the selected
     * column. Items that cannot apply to the current selection are hidden,
     * not greyed (context-menu-style §4); the toggle quotes its target (§5).
     */
    public void installPlotActions(PlotActions actions) {
        JMenuItem showInPlot = new JMenuItem("Show in plot");
        showInPlot.addActionListener(e -> {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0) {
                actions.showInPlot(viewRow + (session.headerRowInData() ? 1 : 0), selectedModelColumn());
            }
        });
        tableMenu.insert(showInPlot, tableMenu.getComponentIndex(showInFileItem) + 1);

        JPopupMenu.Separator plotSeparator = new JPopupMenu.Separator();
        JMenuItem plotToggle = new JMenuItem();
        plotToggle.addActionListener(e -> {
            int column = selectedModelColumn();
            if (column > 0) {
                actions.togglePlotted(column);
            }
        });
        int copyIndex = tableMenu.getComponentIndex(copyItem);
        tableMenu.insert(plotSeparator, copyIndex);
        tableMenu.insert(plotToggle, copyIndex); // lands before its separator: … | Plot "col" | Copy

        tableMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                int column = selectedModelColumn();
                boolean dataColumn = column > 0; // column 0 is the date axis
                plotSeparator.setVisible(dataColumn);
                plotToggle.setVisible(dataColumn);
                if (dataColumn) {
                    String name = table.getModel().getColumnName(column);
                    plotToggle.setText(
                        (actions.isColumnPlotted(column) ? "Unplot \"" : "Plot \"") + name + "\"");
                }
                showInPlot.setVisible(table.getSelectedRow() >= 0);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /** The model index of the selected column, or -1. */
    private int selectedModelColumn() {
        int viewColumn = table.getSelectedColumn();
        return viewColumn >= 0 ? table.convertColumnIndexToModel(viewColumn) : -1;
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
        table.setGridColor(gridColor != null ? gridColor : UIConstants.TableView.FALLBACK_GRID_COLOR);
    }

    /** The underlying table — package-private, for tests. */
    JTable getTable() {
        return table;
    }

    /** The status strip's current text — package-private, for tests. */
    String getStatusText() {
        return status.getText();
    }
}
