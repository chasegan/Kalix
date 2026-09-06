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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
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

    private String lastSearch = "";
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
     * "Plot" toggle lives here, beside the dialect facts, rather than as a
     * full-width row above the table that would read as a title.
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
        JMenuItem find = new JMenuItem("Find…");
        find.addActionListener(e -> promptFind());
        JMenuItem findNext = new JMenuItem("Find next");
        findNext.addActionListener(e -> findNext());
        JMenuItem showInFile = new JMenuItem("Show in file"); // sentence case per context-menu-style §2.1
        showInFile.addActionListener(e -> showSelectedRowInFile());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(e -> {
            // JTable's built-in copy: selected cells as tab-delimited lines.
            Action builtIn = table.getActionMap().get("copy");
            if (builtIn != null) {
                builtIn.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });
        menu.add(find);
        menu.add(findNext);
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
        table.getActionMap().put("data-find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                promptFind();
            }
        });
        table.getActionMap().put("data-find-next", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext();
            }
        });
    }

    private void promptFind() {
        String input = (String) JOptionPane.showInputDialog(this, "Find in data:", "Find",
            JOptionPane.PLAIN_MESSAGE, null, null, lastSearch);
        if (input == null || input.isEmpty()) {
            return;
        }
        lastSearch = input;
        runSearch(input);
    }

    private void findNext() {
        if (lastSearch.isEmpty()) {
            promptFind();
        } else {
            runSearch(lastSearch);
        }
    }

    /** Streams the search on a worker thread; the EDT only receives the landing row. */
    private void runSearch(String needle) {
        if (!searchInFlight.compareAndSet(false, true)) {
            return; // a scan is already running ("a 1GB file takes a few seconds")
        }
        long headerOffset = session.headerRowInData() ? 1 : 0;
        int selectedView = table.getSelectedRow();
        long fromExclusive = selectedView >= 0 ? selectedView + headerOffset : headerOffset - 1;
        DataViewSession target = session;
        Thread searcher = new Thread(() -> {
            try {
                long found = target.findNextRow(fromExclusive, needle);
                SwingUtilities.invokeLater(() -> {
                    if (target != session) {
                        return; // the session was swapped mid-search
                    }
                    if (found < 0) {
                        Toolkit.getDefaultToolkit().beep();
                    } else {
                        scrollToFileRow(found);
                    }
                });
            } catch (IOException e) {
                logger.warn("Data search failed: {}", e.getMessage());
            } finally {
                searchInFlight.set(false);
            }
        }, "kalix-dataview-search");
        searcher.setDaemon(true);
        searcher.start();
    }

    private void scrollToFileRow(long fileRow) {
        int viewRow = (int) Math.min(Integer.MAX_VALUE, fileRow - (session.headerRowInData() ? 1 : 0));
        if (viewRow < 0 || viewRow >= table.getRowCount()) {
            return;
        }
        table.changeSelection(viewRow, 0, false, false);
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
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
