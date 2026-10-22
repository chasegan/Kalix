package com.kalix.ide.flowviz;

import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.models.StatsTableModel;
import com.kalix.ide.io.CsvZipFormat;
import com.kalix.ide.utils.DialogUtils;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.Optional;

/**
 * A visualization tab's Stats page: one row of statistics per selected series,
 * computed from the aggregated data the tab's shared controls describe.
 *
 * <p>The page owns its table and its Save; the settings it reflects (aggregation,
 * mask, seasonal mask) belong to the tab, and the tab manager projects them into
 * {@link #model()}.
 */
public final class StatsVizPanel extends JPanel {

    private final StatsTableModel model = new StatsTableModel();
    private final JTable table;

    public StatsVizPanel() {
        super(new BorderLayout());

        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowSelectionAllowed(false);
        applyStatsTableRenderer(table);
        // Narrow index column (0); wider Series column (1) for longer series names.
        if (table.getColumnCount() > 1) {
            table.getColumnModel().getColumn(0).setMaxWidth(48);
            table.getColumnModel().getColumn(0).setPreferredWidth(40);
            table.getColumnModel().getColumn(1).setPreferredWidth(200);
        }
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    /** The statistics this page shows. */
    public StatsTableModel model() {
        return model;
    }

    public void setLabelResolver(LabelResolver labelResolver) {
        model.setLabelResolver(labelResolver);
    }

    /** Saves the statistics to CSV. */
    public void saveData() {
        // The suggested name carries the conventional extension; the dialog takes whatever
        // the user types verbatim and confirms any overwrite itself.
        Optional<File> chosen = KalixFileDialog.saveFile(table)
            .title("Save Statistics")
            .suggestedName("statistics.csv")
            .filters(
                FileDialogFilter.of("CSV Files (*.csv)", "csv"),
                FileDialogFilter.of("Zipped CSV (*.csv.zip)", "csv.zip"))
            .show();
        if (chosen.isPresent()) {
            File file = chosen.get();

            // The extension is the format: a name ending .csv.zip writes a
            // one-entry zip (and either way the text is UTF-8, like the data
            // exporter).
            try (Writer writer = CsvZipFormat.newUtf8Writer(file)) {
                // Write header (dynamic columns from table)
                for (int col = 0; col < table.getColumnCount(); col++) {
                    if (col > 0) writer.write(",");
                    writer.write(table.getColumnName(col));
                }
                writer.write("\n");

                // Write data rows
                for (int row = 0; row < table.getRowCount(); row++) {
                    for (int col = 0; col < table.getColumnCount(); col++) {
                        if (col > 0) writer.write(",");
                        Object value = table.getValueAt(row, col);
                        writer.write(value != null ? value.toString() : "");
                    }
                    writer.write("\n");
                }

                JOptionPane.showMessageDialog(table,
                    "Statistics saved successfully to:\n" + file.getAbsolutePath(),
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException ex) {
                DialogUtils.showError(table,
                    "Error saving statistics: " + ex.getMessage(),
                    "Save Error");
            }
        }
    }

    /** The underlying table — package-private, for tests. */
    JTable getTable() {
        return table;
    }

    /**
     * Applies a custom cell renderer to the stats table that colors statistic values.
     * The index (column 0) and Series (column 1) columns remain default, while statistic
     * columns use a muted theme color.
     */
    private static void applyStatsTableRenderer(JTable table) {
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);

                if (c instanceof JLabel) {
                    JLabel label = (JLabel) c;

                    if (!isSelected) {
                        if (column <= 1) {
                            // Index and Series columns - use default foreground color
                            Color defaultColor = UIManager.getColor("Table.foreground");
                            if (defaultColor != null) {
                                label.setForeground(defaultColor);
                            }
                        } else {
                            // Statistic columns - use muted theme color
                            Color statsColor = UIManager.getColor("Label.disabledForeground");
                            if (statsColor == null) {
                                // Fallback to a semi-transparent default text color
                                Color defaultColor = UIManager.getColor("Table.foreground");
                                if (defaultColor != null) {
                                    statsColor = new Color(
                                        defaultColor.getRed(),
                                        defaultColor.getGreen(),
                                        defaultColor.getBlue(),
                                        160  // ~63% opacity
                                    );
                                }
                            }
                            if (statsColor != null) {
                                label.setForeground(statsColor);
                            }
                        }
                    }
                }

                return c;
            }
        });
    }
}
