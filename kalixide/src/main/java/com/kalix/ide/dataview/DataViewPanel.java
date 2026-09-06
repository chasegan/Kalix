package com.kalix.ide.dataview;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import java.awt.BorderLayout;

/**
 * The table projection mounted as a data document's contextual view: a virtual
 * {@link JTable} over the {@link DataViewSession}, with a status strip that
 * declares the sniffer's decisions — delimiter, encoding, line endings, row
 * count — so the interpretation is visible, never silent. While the background
 * index runs the strip carries a live progress figure and the table grows.
 */
public final class DataViewPanel extends JPanel {

    private final DataViewSession session;
    private final JTable table;
    private final JLabel status = new JLabel();

    public DataViewPanel(DataViewSession session) {
        super(new BorderLayout());
        this.session = session;

        table = new JTable(new VirtualDataTableModel(session));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // wide files scroll horizontally
        table.setFillsViewportHeight(true);
        add(new JScrollPane(table), BorderLayout.CENTER);

        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(status, BorderLayout.SOUTH);
        refreshStatus();

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

    private void refreshStatus() {
        CsvDialect dialect = session.dialect();
        String delimiter = switch (dialect.delimiter()) {
            case '\t' -> "tab";
            default -> "\"" + dialect.delimiter() + "\"";
        };
        long dataRows = Math.max(0, session.rowCount() - (session.headerRowInData() ? 1 : 0));

        StringBuilder text = new StringBuilder();
        text.append("delimiter ").append(delimiter)
            .append("  ·  ").append(dialect.charset().name())
            .append("  ·  ").append(dialect.lineEndingLabel())
            .append("  ·  ").append(String.format("%,d rows", dataRows));
        if (!session.isIndexingComplete() && session.totalBytes() > 0) {
            text.append(String.format("  ·  indexing %d%%",
                100 * session.indexedBytes() / session.totalBytes()));
        }
        status.setText(text.toString());
    }

    /** The underlying table — package-private, for tests. */
    JTable getTable() {
        return table;
    }
}
