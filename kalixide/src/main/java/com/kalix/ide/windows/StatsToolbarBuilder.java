package com.kalix.ide.windows;

import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.filedialog.KalixFileDialog;

import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import java.awt.Dimension;
import java.io.File;

/**
 * Builder for a stats tab's toolbar (save, aggregation, mask controls), extracted
 * from {@link VisualizationTabManager}. Sizing and the aggregation option lists are
 * shared with {@link PlotToolbarBuilder} so the two toolbars stay visually and
 * behaviourally consistent.
 */
class StatsToolbarBuilder {
    private final JToolBar toolbar;
    private final VisualizationTabManager.TabInfo tabInfo;
    private final JTable statsTable;
    private final DataSet dataSet;

    // Store dropdown references for coordinated updates
    private JComboBox<String> aggregationPeriodCombo;
    private JComboBox<String> aggregationMethodCombo;

    StatsToolbarBuilder(VisualizationTabManager.TabInfo tabInfo, JTable statsTable, DataSet dataSet) {
        this.tabInfo = tabInfo;
        this.statsTable = statsTable;
        this.dataSet = dataSet;
        this.toolbar = new JToolBar();
        this.toolbar.setFloatable(false);
        this.toolbar.setRollover(true);
    }

    StatsToolbarBuilder addSaveButton() {
        JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", this::saveStatsData);
        toolbar.add(button);
        return this;
    }

    StatsToolbarBuilder addAggregationControls() {
        // Resolution label
        toolbar.add(new JLabel("Resolution:"));
        toolbar.add(Box.createHorizontalStrut(PlotToolbarBuilder.HORIZONTAL_SPACING));

        // Aggregation period dropdown
        aggregationPeriodCombo = createDropdown(PlotToolbarBuilder.AGGREGATION_OPTIONS,
            PlotToolbarBuilder.WIDE_DROPDOWN_SIZE, "Aggregation");
        // Set initial selection from tab info
        aggregationPeriodCombo.setSelectedItem(tabInfo.statsPeriod.getDisplayName());
        aggregationPeriodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationPeriodCombo);

        // "by" label
        toolbar.add(Box.createHorizontalStrut(PlotToolbarBuilder.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("by"));
        toolbar.add(Box.createHorizontalStrut(PlotToolbarBuilder.HORIZONTAL_SPACING));

        // Aggregation method dropdown
        aggregationMethodCombo = createDropdown(PlotToolbarBuilder.AGGREGATION_METHOD_OPTIONS,
            PlotToolbarBuilder.NARROW_DROPDOWN_SIZE, "Aggregation method");
        // Set initial selection from tab info
        aggregationMethodCombo.setSelectedItem(tabInfo.statsMethod.getDisplayName());
        aggregationMethodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationMethodCombo);

        return this;
    }

    StatsToolbarBuilder addMaskControls() {
        // Mask label
        toolbar.add(Box.createHorizontalStrut(PlotToolbarBuilder.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("Mask:"));
        toolbar.add(Box.createHorizontalStrut(PlotToolbarBuilder.HORIZONTAL_SPACING));

        // Mask mode dropdown
        String[] maskOptions = {"All", "Each", "None"};
        JComboBox<String> maskCombo = createDropdown(maskOptions,
            PlotToolbarBuilder.NARROW_DROPDOWN_SIZE, "Mask mode for bivariate statistics");

        // Set initial selection from stats model
        if (tabInfo.statsModel != null) {
            maskCombo.setSelectedItem(tabInfo.statsModel.getMaskMode().getDisplayName());
        }

        maskCombo.addActionListener(e -> {
            String selected = (String) maskCombo.getSelectedItem();
            if (selected != null && tabInfo.statsModel != null) {
                com.kalix.ide.flowviz.stats.MaskMode mode =
                    com.kalix.ide.flowviz.stats.MaskMode.fromDisplayName(selected);
                tabInfo.statsModel.setMaskMode(mode);
            }
        });
        toolbar.add(maskCombo);

        return this;
    }

    /** Applies current aggregation settings to stats. */
    private void applyAggregation() {
        if (aggregationPeriodCombo == null || aggregationMethodCombo == null) {
            return;
        }

        String periodStr = (String) aggregationPeriodCombo.getSelectedItem();
        String methodStr = (String) aggregationMethodCombo.getSelectedItem();

        if (periodStr != null && methodStr != null) {
            AggregationPeriod period = AggregationPeriod.fromDisplayName(periodStr);
            AggregationMethod method = AggregationMethod.fromDisplayName(methodStr);

            // Update tab info aggregation settings
            tabInfo.statsPeriod = period;
            tabInfo.statsMethod = method;

            // Recompute stats with aggregated data
            recomputeStats();
        }
    }

    /** Recomputes stats with current aggregation settings, filtered to per-tab series. */
    private void recomputeStats() {
        if (tabInfo.statsModel == null || dataSet == null) {
            return;
        }

        tabInfo.statsModel.clear();
        for (SeriesRef ref : tabInfo.selectedSeries) {
            TimeSeriesData originalSeries = dataSet.getSeries(ref);
            if (originalSeries != null) {
                TimeSeriesData aggregatedSeries = com.kalix.ide.flowviz.transform.TimeSeriesAggregator.aggregate(
                    originalSeries, tabInfo.statsPeriod, tabInfo.statsMethod);
                if (aggregatedSeries != null) {
                    tabInfo.statsModel.addOrUpdateSeries(ref, aggregatedSeries);
                }
            }
        }
    }

    /** Saves stats data to CSV. */
    private void saveStatsData() {
        // The suggested name carries the conventional extension; the dialog takes whatever
        // the user types verbatim and confirms any overwrite itself.
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(statsTable)
            .title("Save Statistics")
            .suggestedName("statistics.csv")
            .show();
        if (chosen.isPresent()) {
            File file = chosen.get();

            try (java.io.FileWriter writer = new java.io.FileWriter(file)) {
                // Write header (dynamic columns from table)
                for (int col = 0; col < statsTable.getColumnCount(); col++) {
                    if (col > 0) writer.write(",");
                    writer.write(statsTable.getColumnName(col));
                }
                writer.write("\n");

                // Write data rows
                for (int row = 0; row < statsTable.getRowCount(); row++) {
                    for (int col = 0; col < statsTable.getColumnCount(); col++) {
                        if (col > 0) writer.write(",");
                        Object value = statsTable.getValueAt(row, col);
                        writer.write(value != null ? value.toString() : "");
                    }
                    writer.write("\n");
                }

                JOptionPane.showMessageDialog(statsTable,
                    "Statistics saved successfully to:\n" + file.getAbsolutePath(),
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);

            } catch (java.io.IOException ex) {
                JOptionPane.showMessageDialog(statsTable,
                    "Error saving statistics: " + ex.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    StatsToolbarBuilder addSeparator() {
        toolbar.addSeparator();
        return this;
    }

    JToolBar build() {
        return toolbar;
    }

    /** Creates a standard icon button. */
    private JButton createIconButton(FontAwesomeSolid icon, String tooltip, Runnable action) {
        JButton button = new JButton(FontIcon.of(icon, PlotToolbarBuilder.BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** Creates a standard dropdown. */
    private JComboBox<String> createDropdown(String[] options, Dimension size, String tooltip) {
        JComboBox<String> combo = new JComboBox<>(options);
        combo.setMaximumSize(size);
        combo.setToolTipText(tooltip);
        return combo;
    }
}
