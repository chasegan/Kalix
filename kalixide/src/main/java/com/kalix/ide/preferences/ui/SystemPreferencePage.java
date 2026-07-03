package com.kalix.ide.preferences.ui;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.preferences.PreferenceManager;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.io.File;

/**
 * General (system) preferences page: application info, the preference file
 * location, and clearing app data.
 */
public class SystemPreferencePage extends AbstractPreferencePage {

    private final Runnable onClearAppDataRequested;

    /**
     * @param onClearAppDataRequested invoked after the user confirms clearing app
     *                                data; the application performs the actual clearing
     */
    public SystemPreferencePage(Runnable onClearAppDataRequested) {
        super("General");
        this.onClearAppDataRequested = onClearAppDataRequested;
        initializePanel();
    }

    @Override
    public String id() {
        return "system";
    }

    @Override
    public String treePath() {
        return "General";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // System info (moved to top)
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0; gbc.weighty = 0;
        JTextArea systemInfo = new JTextArea();
        systemInfo.setEditable(false);
        systemInfo.setOpaque(false);
        systemInfo.setText("Application: " + AppConstants.APP_NAME + " " + AppConstants.APP_VERSION + "\n" +
            "Java Version: " + System.getProperty("java.version") + "\n" +
            "Operating System: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n" +
            "User Directory: " + System.getProperty("user.dir"));
        formPanel.add(systemInfo, gbc);

        // Preference file location
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.insets = new Insets(15, 5, 5, 5); // Extra top margin for separation
        formPanel.add(new JLabel("Preferences File:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        gbc.insets = new Insets(15, 5, 5, 5);
        JTextField prefFileField = new JTextField(PreferenceManager.getPreferenceFilePath());
        prefFileField.setEditable(false);
        formPanel.add(prefFileField, gbc);

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        gbc.insets = new Insets(15, 5, 5, 5);
        JButton locateButton = new JButton("Locate");
        locateButton.addActionListener(e -> {
            try {
                File prefFile = new File(PreferenceManager.getPreferenceFilePath());
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(prefFile.getParentFile());
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Could not open file location: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        formPanel.add(locateButton, gbc);

        // Clear app data section
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.insets = new Insets(15, 5, 5, 5); // Extra top margin for separation

        JPanel clearDataPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton clearAppDataButton = new JButton("Clear App Data...");
        clearAppDataButton.addActionListener(e -> clearAppData());
        clearDataPanel.add(clearAppDataButton);

        JLabel clearDataLabel = new JLabel("Clear Kalix application preferences from operating system.");
        clearDataLabel.setFont(clearDataLabel.getFont().deriveFont(Font.ITALIC));
        clearDataPanel.add(clearDataLabel);

        formPanel.add(clearDataPanel, gbc);

        add(formPanel, BorderLayout.NORTH);
    }

    private void clearAppData() {
        // Show confirmation dialog
        int result = JOptionPane.showConfirmDialog(
            this,
            "This will clear all Kalix IDE application data including:\n\n" +
            "• Theme preferences\n" +
            "• Node theme preferences\n" +
            "• Recent files list\n" +
            "• Window position and size settings\n" +
            "• Split pane divider positions\n" +
            "• All other saved preferences\n\n" +
            "Are you sure you want to continue?\n\n" +
            "Note: The application will restart after clearing data.",
            "Clear App Data",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (result == JOptionPane.YES_OPTION) {
            // Notify the main application to handle the clearing
            onClearAppDataRequested.run();
        }
    }
}
