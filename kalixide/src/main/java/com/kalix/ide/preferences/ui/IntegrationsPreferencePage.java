package com.kalix.ide.preferences.ui;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.Platform;
import com.kalix.ide.utils.PlatformUtils;
import com.kalix.ide.utils.TerminalLauncher;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;

/**
 * Integrations preferences page: external editor and terminal launch commands.
 */
public class IntegrationsPreferencePage extends AbstractPreferencePage {

    private JTextField externalEditorField;
    private JTextField activationField;
    private JTextField macosTerminalAppField;

    public IntegrationsPreferencePage() {
        super("Integrations");
        initializePanel();
    }

    @Override
    public String id() {
        return "integrations";
    }

    @Override
    public String treePath() {
        return "Integrations";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // External editor command
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        formPanel.add(new JLabel("External Editor Command:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        externalEditorField = new JTextField(PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND.get());
        externalEditorField.setToolTipText("Command to launch an external editor. Use <folder_path> for the folder containing the current file and <file_path> for the full path to the current file.");
        commitOnFocusLostAndClose(externalEditorField, this::saveExternalEditorCommand);
        formPanel.add(externalEditorField, gbc);

        // Terminal activation command (per-platform). Shows the effective value, which on
        // Windows includes the migrated legacy command; saving writes the new per-platform key.
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        formPanel.add(new JLabel("Terminal Activation Command:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        activationField = new JTextField(TerminalLauncher.getActivationCommand());
        activationField.setToolTipText("Shell command(s) run after entering the working directory, e.g. to "
            + "activate a Python/conda environment (\"conda activate myenv\"). Leave blank for a plain terminal.");
        commitOnFocusLostAndClose(activationField, this::saveActivationCommand);
        formPanel.add(activationField, gbc);

        // macOS terminal application (only relevant on macOS).
        if (PlatformUtils.getCurrentPlatform() == Platform.MACOS) {
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            formPanel.add(new JLabel("macOS Terminal App:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            macosTerminalAppField = new JTextField(PreferenceKeys.FILE_MACOS_TERMINAL_APP.get());
            macosTerminalAppField.setToolTipText("Terminal application to launch on macOS. "
                + "\"Terminal\" and \"iTerm\" support activation; others (Warp, Ghostty, …) open at the folder only.");
            commitOnFocusLostAndClose(macosTerminalAppField, this::saveMacosTerminalApp);
            formPanel.add(macosTerminalAppField, gbc);
        }

        add(formPanel, BorderLayout.NORTH);
    }

    private void saveExternalEditorCommand() {
        String command = externalEditorField.getText().trim();
        if (!command.equals(PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND.get())) {
            PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND.set(command);
        }
    }

    private void saveActivationCommand() {
        String command = activationField.getText().trim();
        // Compare against the effective command (which the field was seeded with),
        // so an untouched field never writes - in particular it does not turn the
        // Windows legacy-key fallback into an explicit per-platform value.
        if (!command.equals(TerminalLauncher.getActivationCommand())) {
            TerminalLauncher.activationPreference().set(command);
        }
    }

    private void saveMacosTerminalApp() {
        String app = macosTerminalAppField.getText().trim();
        if (!app.equals(PreferenceKeys.FILE_MACOS_TERMINAL_APP.get())) {
            PreferenceKeys.FILE_MACOS_TERMINAL_APP.set(app);
        }
    }
}
