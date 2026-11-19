package com.kalix.ide.preferences.ui;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.rendering.NodeLabelMode;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Node diagram preferences page: map display options.
 */
public class NodeDiagramPreferencePage extends AbstractPreferencePage {

    private final Consumer<Boolean> onScrollPansMapChanged;

    private JCheckBox gridlinesCheckBox;
    private JComboBox<NodeLabelMode> labelModeComboBox;
    private JCheckBox scrollPansMapCheckBox;

    /**
     * @param onScrollPansMapChanged given the new value of the scroll setting; it
     *                               applies the value to the open maps and saves it
     */
    public NodeDiagramPreferencePage(Consumer<Boolean> onScrollPansMapChanged) {
        super("Node Diagram");
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
        gridlinesCheckBox = new JCheckBox("Show gridlines on new maps");
        gridlinesCheckBox.setSelected(PreferenceKeys.MAP_SHOW_GRIDLINES.get());
        // Open maps keep their own gridlines; only new maps take this.
        gridlinesCheckBox.addActionListener(
            e -> PreferenceKeys.MAP_SHOW_GRIDLINES.set(gridlinesCheckBox.isSelected()));
        formPanel.add(gridlinesCheckBox, gbc);

        // Like gridlines: open maps keep their own labels; only new maps take this.
        gbc.gridy = 1;
        labelModeComboBox = new JComboBox<>(NodeLabelMode.values());
        labelModeComboBox.setRenderer(labelModeRenderer());
        labelModeComboBox.setSelectedItem(NodeLabelMode.saved());
        labelModeComboBox.addActionListener(e -> {
            NodeLabelMode mode = (NodeLabelMode) labelModeComboBox.getSelectedItem();
            if (mode != null) {
                mode.save();
            }
        });
        JLabel labelModeLabel = new JLabel("Node labels on new maps:");
        labelModeLabel.setLabelFor(labelModeComboBox);
        labelModeLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        JPanel labelModeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        labelModeRow.add(labelModeLabel);
        labelModeRow.add(labelModeComboBox);
        formPanel.add(labelModeRow, gbc);

        // The same setting as View > Scroll Pans Map.
        gbc.gridy = 2;
        scrollPansMapCheckBox = new JCheckBox("Scroll pans the map (hold Ctrl or Cmd to zoom)");
        scrollPansMapCheckBox.setSelected(PreferenceKeys.MAP_TRACKPAD_NAVIGATION.get());
        scrollPansMapCheckBox.addActionListener(
            e -> onScrollPansMapChanged.accept(scrollPansMapCheckBox.isSelected()));
        formPanel.add(scrollPansMapCheckBox, gbc);

        add(formPanel, BorderLayout.NORTH);
    }

    private static DefaultListCellRenderer labelModeRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof NodeLabelMode mode) {
                    setText(mode.displayName());
                }
                return this;
            }
        };
    }
}
