package com.kalix.ide.flowviz;

import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A visualization tab's Stats page: one row of statistics per selected series,
 * computed from the aggregated data the tab's shared controls describe.
 *
 * <p>The page owns its table and its Save; the settings it reflects (aggregation,
 * mask, seasonal mask) belong to the tab's {@link FlowVizModel}. The page listens to it and
 * projects when shown; while hidden it only notes that it is out of date, and catches up
 * when shown.
 */
public final class StatsVizPanel extends JPanel {

    private final StatsTableModel model = new StatsTableModel();
    private final JTable table;
    private final FlowVizModel tabModel;
    private final Supplier<Map<SeriesRef, SeriesLoadState>> loadStates;
    private boolean shown;
    private boolean dirty = true;

    /**
     * @param loadStates the fetch state of series not yet in the pool, shared by every tab
     */
    public StatsVizPanel(FlowVizModel tabModel, Supplier<Map<SeriesRef, SeriesLoadState>> loadStates) {
        super(new BorderLayout());
        this.tabModel = tabModel;
        this.loadStates = loadStates;
        tabModel.addListener((old, now, cause) -> {
            if (cause != FlowVizModel.Cause.USER || affectsStats(old, now)) {
                refresh();
            }
        });

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

    /** Shows or hides the page; a page shown while out of date projects at once. */
    public void setShown(boolean shown) {
        this.shown = shown;
        if (shown && dirty) {
            project();
        }
    }

    /** Something under the page changed (data, load states): project now if shown, else later. */
    public void refresh() {
        if (shown) {
            project();
        } else {
            dirty = true;
        }
    }

    /** Whether the hidden page has missed a change — package-private, for tests. */
    boolean isDirty() {
        return dirty;
    }

    private void project() {
        dirty = false;
        FlowVizState state = tabModel.current();
        model.project(tabModel.aggregated(), state.getVisibleSeries(), state.getMaskMode(), loadStates.get());
    }

    private static boolean affectsStats(FlowVizState old, FlowVizState now) {
        return !now.getVisibleSeries().equals(old.getVisibleSeries())
            || now.getAggregationPeriod() != old.getAggregationPeriod()
            || now.getAggregationMethod() != old.getAggregationMethod()
            || now.getMaskMode() != old.getMaskMode()
            || !now.getSeasonalMaskMode().equals(old.getSeasonalMaskMode());
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
