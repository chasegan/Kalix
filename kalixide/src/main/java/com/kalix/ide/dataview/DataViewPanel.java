package com.kalix.ide.dataview;

import com.kalix.ide.constants.UIConstants;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

/**
 * The table projection mounted as a data document's contextual view: a virtual
 * {@link JTable} over the {@link DataViewSession}, with a status strip that
 * declares the sniffer's decisions — delimiter, encoding, line endings, row
 * count — so the interpretation is visible, never silent. While the background
 * index runs the strip carries a live progress figure and the table grows.
 */
public final class DataViewPanel extends JPanel {

    /** Non-final: replaced with a fresh session after a save rewrites the file. */
    private DataViewSession session;
    private final JTable table;
    private final JLabel status = new JLabel();

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
        add(new JScrollPane(table), BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(status, BorderLayout.SOUTH);
        refreshStatus();
        registerStatusListener();
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
     * Swaps in a freshly opened session after a save rewrote the file (stale
     * checkpoints would otherwise parse the new bytes at old offsets). EDT only;
     * the caller ({@code KalixDocument.refreshDataViewAfterSave}) closes the old
     * session after this returns.
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
