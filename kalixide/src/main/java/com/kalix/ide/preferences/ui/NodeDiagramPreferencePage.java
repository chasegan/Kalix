package com.kalix.ide.preferences.ui;

import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Node diagram preferences page: map display options.
 */
public class NodeDiagramPreferencePage extends AbstractPreferencePage {

    private final Runnable onMapPreferencesChanged;
    private final Consumer<Boolean> onGridlinesChanged;
    private final Consumer<Boolean> onScrollPansMapChanged;

    private JCheckBox gridlinesCheckBox;
    private JCheckBox scrollPansMapCheckBox;

    /**
     * @param onMapPreferencesChanged notified after a map preference changes, so
     *                                the map display can be updated
     * @param onGridlinesChanged      notified with the new value after the gridlines
     *                                preference changes, so the toolbar toggle can sync
     * @param onScrollPansMapChanged  given the new value of the scroll setting; it
     *                                applies the value to the open maps and saves it
     */
    public NodeDiagramPreferencePage(Runnable onMapPreferencesChanged, Consumer<Boolean> onGridlinesChanged,
                                     Consumer<Boolean> onScrollPansMapChanged) {
        super("Node Diagram");
        this.onMapPreferencesChanged = onMapPreferencesChanged;
        this.onGridlinesChanged = onGridlinesChanged;
        this.onScrollPansMapChanged = onScrollPansMapChanged;
        initializePanel();
    }

    @Override
    public String id() {
        return "nodediagram";
    }

    @Override
    public String treePath() {
        return "Editor/Node Diagram";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Map gridlines setting
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gridlinesCheckBox = new JCheckBox("Show gridlines on map");
        gridlinesCheckBox.setSelected(PreferenceKeys.MAP_SHOW_GRIDLINES.get());
        gridlinesCheckBox.addActionListener(e -> {
            boolean enabled = gridlinesCheckBox.isSelected();
            PreferenceKeys.MAP_SHOW_GRIDLINES.set(enabled);

            // Notify callbacks to update map display and toolbar button
            onMapPreferencesChanged.run();
            onGridlinesChanged.accept(enabled);
        });
        formPanel.add(gridlinesCheckBox, gbc);

        // The same setting as View > Scroll Pans Map.
        gbc.gridy = 1;
        scrollPansMapCheckBox = new JCheckBox("Scroll pans the map (hold Ctrl or Cmd to zoom)");
        scrollPansMapCheckBox.setSelected(PreferenceKeys.MAP_TRACKPAD_NAVIGATION.get());
        scrollPansMapCheckBox.addActionListener(
            e -> onScrollPansMapChanged.accept(scrollPansMapCheckBox.isSelected()));
        formPanel.add(scrollPansMapCheckBox, gbc);

        add(formPanel, BorderLayout.NORTH);
    }
}
