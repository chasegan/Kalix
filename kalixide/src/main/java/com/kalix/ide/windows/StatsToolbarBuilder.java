package com.kalix.ide.windows;

import com.kalix.ide.flowviz.PlotState;
import com.kalix.ide.flowviz.stats.MaskMode;
import com.kalix.ide.flowviz.transform.AggregationMethod;
import com.kalix.ide.flowviz.transform.AggregationPeriod;
import com.kalix.ide.filedialog.FileDialogFilter;
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
import java.util.function.Consumer;

/**
 * Builder for a stats-view tab's toolbar (save, undo/redo, aggregation, mask).
 * The controls drive the tab's state-owning {@code PlotPanel} — so every change
 * here is undoable and shared with the plot projection — and report application
 * through {@code onStateApplied} so the owner reprojects the stats table.
 * Sizing and the aggregation option lists come from {@link ToolbarConstants},
 * also used by {@link PlotToolbarBuilder}, so the two toolbars stay consistent.
 */
class StatsToolbarBuilder {
    private final JToolBar toolbar;
    private final VisualizationTabManager.TabInfo tabInfo;
    private final JTable statsTable;

    private Consumer<PlotState> onUndoRedo;
    private Runnable onStateApplied;

    // Store dropdown references for coordinated updates
    private JComboBox<String> aggregationPeriodCombo;
    private JComboBox<String> aggregationMethodCombo;
    private JComboBox<String> maskCombo;

    StatsToolbarBuilder(VisualizationTabManager.TabInfo tabInfo, JTable statsTable) {
        this.tabInfo = tabInfo;
        this.statsTable = statsTable;
        this.toolbar = new JToolBar();
        this.toolbar.setFloatable(false);
        this.toolbar.setRollover(true);
    }

    StatsToolbarBuilder setOnUndoRedo(Consumer<PlotState> callback) {
        this.onUndoRedo = callback;
        return this;
    }

    /** Invoked after a control applied a state change, so the owner reprojects the stats table. */
    StatsToolbarBuilder setOnStateApplied(Runnable callback) {
        this.onStateApplied = callback;
        return this;
    }

    StatsToolbarBuilder addSaveButton() {
        JButton button = createIconButton(FontAwesomeSolid.SAVE, "Save Data", this::saveStatsData);
        toolbar.add(button);
        return this;
    }

    StatsToolbarBuilder addUndoRedoButtons() {
        JButton undoButton = createIconButton(FontAwesomeSolid.UNDO, "Undo", () -> {
            PlotState state = tabInfo.plotPanel.undo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });
        JButton redoButton = createIconButton(FontAwesomeSolid.REDO, "Redo", () -> {
            PlotState state = tabInfo.plotPanel.redo();
            if (state != null && onUndoRedo != null) {
                onUndoRedo.accept(state);
            }
        });

        // Initialise from the panel: a duplicated tab has history before its toolbar exists.
        undoButton.setEnabled(tabInfo.plotPanel.canUndo());
        redoButton.setEnabled(tabInfo.plotPanel.canRedo());
        tabInfo.plotPanel.setOnHistoryChanged(() -> {
            undoButton.setEnabled(tabInfo.plotPanel.canUndo());
            redoButton.setEnabled(tabInfo.plotPanel.canRedo());
        });

        toolbar.add(undoButton);
        toolbar.add(redoButton);
        return this;
    }

    StatsToolbarBuilder addAggregationControls() {
        // Resolution label
        toolbar.add(new JLabel("Resolution:"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        // Aggregation period dropdown, initialised from the state-owning panel
        aggregationPeriodCombo = createDropdown(ToolbarConstants.AGGREGATION_OPTIONS,
            ToolbarConstants.WIDE_DROPDOWN_SIZE, "Aggregation");
        aggregationPeriodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationPeriod().getDisplayName());
        aggregationPeriodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationPeriodCombo);

        // "by" label
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("by"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        // Aggregation method dropdown
        aggregationMethodCombo = createDropdown(ToolbarConstants.AGGREGATION_METHOD_OPTIONS,
            ToolbarConstants.NARROW_DROPDOWN_SIZE, "Aggregation method");
        aggregationMethodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationMethod().getDisplayName());
        aggregationMethodCombo.addActionListener(e -> applyAggregation());
        toolbar.add(aggregationMethodCombo);

        return this;
    }

    StatsToolbarBuilder addMaskControls() {
        // Mask label
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));
        toolbar.add(new JLabel("Mask:"));
        toolbar.add(Box.createHorizontalStrut(ToolbarConstants.HORIZONTAL_SPACING));

        // Mask mode dropdown, driving the shared (undoable) state
        String[] maskOptions = {"All", "Each", "None"};
        maskCombo = createDropdown(maskOptions,
            ToolbarConstants.NARROW_DROPDOWN_SIZE, "Mask mode for bivariate statistics");
        maskCombo.setSelectedItem(tabInfo.plotPanel.getMaskMode().getDisplayName());
        maskCombo.addActionListener(e -> {
            String selected = (String) maskCombo.getSelectedItem();
            if (selected != null) {
                tabInfo.plotPanel.setMaskMode(MaskMode.fromDisplayName(selected));
                applied();
            }
        });
        toolbar.add(maskCombo);

        return this;
    }

    /**
     * Resyncs the aggregation and mask dropdowns from the tab's current state — used by
     * in-place Reset and the undo/redo callback. Setting a combo fires its listener,
     * which harmlessly re-applies the value the panel already holds (the push dedupes
     * via PlotState.equals, so no spurious history entry).
     */
    void syncFromTab() {
        if (aggregationPeriodCombo != null) {
            aggregationPeriodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationPeriod().getDisplayName());
        }
        if (aggregationMethodCombo != null) {
            aggregationMethodCombo.setSelectedItem(tabInfo.plotPanel.getAggregationMethod().getDisplayName());
        }
        if (maskCombo != null) {
            maskCombo.setSelectedItem(tabInfo.plotPanel.getMaskMode().getDisplayName());
        }
    }

    private void applyAggregation() {
        if (aggregationPeriodCombo == null || aggregationMethodCombo == null) {
            return;
        }

        String periodStr = (String) aggregationPeriodCombo.getSelectedItem();
        String methodStr = (String) aggregationMethodCombo.getSelectedItem();

        if (periodStr != null && methodStr != null) {
            AggregationPeriod period = AggregationPeriod.fromDisplayName(periodStr);
            AggregationMethod method = AggregationMethod.fromDisplayName(methodStr);
            // The panel owns the state: this pushes one undoable entry shared
            // with the plot projection.
            tabInfo.plotPanel.setAggregation(period, method);
            applied();
        }
    }

    private void applied() {
        if (onStateApplied != null) {
            onStateApplied.run();
        }
    }

    /** Saves stats data to CSV. */
    private void saveStatsData() {
        // The suggested name carries the conventional extension; the dialog takes whatever
        // the user types verbatim and confirms any overwrite itself.
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(statsTable)
            .title("Save Statistics")
            .suggestedName("statistics.csv")
            .filters(FileDialogFilter.of("CSV Files (*.csv)", "csv"))
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
        JButton button = new JButton(FontIcon.of(icon, ToolbarConstants.BUTTON_ICON_SIZE));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setPreferredSize(ToolbarConstants.BUTTON_SIZE);
        button.setMinimumSize(ToolbarConstants.BUTTON_SIZE);
        button.setMaximumSize(ToolbarConstants.BUTTON_SIZE);
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
