package com.kalix.ide.preferences.ui;

import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Load and Save preferences page: auto-reload of externally changed files and
 * the prompt-to-save-on-exit behaviour.
 */
public class LoadSavePreferencePage extends AbstractPreferencePage {

    private final Consumer<Boolean> onAutoReloadChanged;

    private JCheckBox autoReloadCheckBox;
    private JCheckBox promptSaveOnExitCheckBox;
    private JSpinner largeFileGateSpinner;

    /**
     * @param onAutoReloadChanged notified with the new value after the auto-reload
     *                            preference changes, so file watching can be updated
     */
    public LoadSavePreferencePage(Consumer<Boolean> onAutoReloadChanged) {
        super("Load and Save");
        this.onAutoReloadChanged = onAutoReloadChanged;
        initializePanel();
    }

    @Override
    public String id() {
        return "loadsave";
    }

    @Override
    public String treePath() {
        return "Editor/Load and Save";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Auto-reload setting
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        autoReloadCheckBox = new JCheckBox("Auto-reload clean files when changed externally");
        autoReloadCheckBox.setSelected(PreferenceKeys.FILE_AUTO_RELOAD.get());
        autoReloadCheckBox.setToolTipText("Automatically reload clean (unchanged) files when modified by external programs. Files with unsaved changes will not be reloaded to prevent data loss.");
        autoReloadCheckBox.addActionListener(e -> {
            boolean enabled = autoReloadCheckBox.isSelected();
            PreferenceKeys.FILE_AUTO_RELOAD.set(enabled);

            // Notify callback to update file watching
            onAutoReloadChanged.accept(enabled);
        });
        formPanel.add(autoReloadCheckBox, gbc);

        // Prompt save on exit setting
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        promptSaveOnExitCheckBox = new JCheckBox("Prompt to save unsaved changes before closing");
        promptSaveOnExitCheckBox.setSelected(PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT.get());
        promptSaveOnExitCheckBox.setToolTipText("Show a confirmation dialog when closing the application with unsaved changes, giving you the option to save your work.");
        promptSaveOnExitCheckBox.addActionListener(e -> {
            boolean enabled = promptSaveOnExitCheckBox.isSelected();
            PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT.set(enabled);
        });
        formPanel.add(promptSaveOnExitCheckBox, gbc);

        // Large data file gate: above this size, data files open as read-only
        // virtual views instead of an editable text buffer.
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JLabel gateLabel = new JLabel("Open data files read-only above (MB):");
        gateLabel.setToolTipText("Data files (.csv) larger than this open as fast read-only views "
            + "(raw text + table) instead of an editable text buffer. Editing very large files in "
            + "a text buffer costs several times the file size in memory and makes typing sluggish.");
        formPanel.add(gateLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 2;
        largeFileGateSpinner = new JSpinner(new SpinnerNumberModel(
            (int) PreferenceKeys.EDITOR_LARGE_FILE_GATE_MB.get(), 1, 4000, 10));
        largeFileGateSpinner.setToolTipText(gateLabel.getToolTipText());
        largeFileGateSpinner.addChangeListener(e ->
            PreferenceKeys.EDITOR_LARGE_FILE_GATE_MB.set((Integer) largeFileGateSpinner.getValue()));
        formPanel.add(largeFileGateSpinner, gbc);

        add(formPanel, BorderLayout.NORTH);
    }
}
