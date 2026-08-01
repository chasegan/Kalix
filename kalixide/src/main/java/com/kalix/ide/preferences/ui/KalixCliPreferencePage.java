package com.kalix.ide.preferences.ui;

import com.kalix.ide.cli.KalixCliLocator;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.preferences.PreferenceKeys;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Optional;

/**
 * Kalix CLI preferences page: where to find the kalix binary, with a search-path
 * editor and a connection test.
 */
public class KalixCliPreferencePage extends AbstractPreferencePage {

    private JTextField binaryPathField;
    private JButton browseButton;
    private JButton testButton;
    private JLabel statusLabel;
    private JTextArea pathLabel;

    public KalixCliPreferencePage() {
        super("Kalix");
        initializePanel();
    }

    @Override
    public String id() {
        return "kalixcli";
    }

    @Override
    public String treePath() {
        return "Simulation/Kalix";
    }

    private void initializePanel() {
        JPanel formPanel = createFormPanel();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Info area
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0; gbc.weighty = 0;
        JTextArea infoArea = new JTextArea();
        infoArea.setEditable(false);
        infoArea.setOpaque(false);
        infoArea.setWrapStyleWord(true);
        infoArea.setLineWrap(true);
        infoArea.setFocusable(false);
        infoArea.setText(
                "Specify directories to search for the Kalix CLI binary. " +
                "Multiple directories can be specified using ';' as a delimiter " +
                "(e.g., /usr/local/bin;/opt/kalix/bin). " +
                "If left empty, the system will search for 'kalix' in the system PATH.");
        formPanel.add(infoArea, gbc);

        // Binary path
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        formPanel.add(new JLabel("Path:"), gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        binaryPathField = new JTextField(PreferenceKeys.CLI_BINARY_PATH.get());
        binaryPathField.setToolTipText("Leave empty to use kalix from system PATH. Use ';' to separate multiple directories.");
        commitOnFocusLostAndClose(binaryPathField, this::saveBinaryPath);
        formPanel.add(binaryPathField, gbc);

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        browseButton = new JButton("Add...");
        browseButton.addActionListener(this::browseBinary);
        formPanel.add(browseButton, gbc);

        // Test and status
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
        testButton = new JButton("Test");
        testButton.addActionListener(this::testConnection);
        formPanel.add(testButton, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        statusLabel = new JLabel("Status: Not tested");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
        formPanel.add(statusLabel, gbc);

        // Path label (shows actual binary path)
        gbc.gridx = 1; gbc.gridy = 3; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.weighty = 1.0;
        pathLabel = new JTextArea("");
        pathLabel.setEditable(false);
        pathLabel.setOpaque(false);
        pathLabel.setLineWrap(true);
        pathLabel.setWrapStyleWord(false);  // Wrap at character boundaries for paths
        pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 11f));
        pathLabel.setForeground(Color.GRAY);
        pathLabel.setFocusable(false);  // Prevent cursor from appearing
        formPanel.add(pathLabel, gbc);
        add(formPanel, BorderLayout.CENTER);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 0;
        JTextArea exeLocInfoArea = new JTextArea();
        exeLocInfoArea.setEditable(false);
        exeLocInfoArea.setOpaque(false);
        exeLocInfoArea.setWrapStyleWord(true);
        exeLocInfoArea.setLineWrap(true);
        exeLocInfoArea.setFocusable(false);
        StringBuilder exeLocInfoAreaTextBuilder = new StringBuilder();
        Optional<File> exeLoc = KalixCliLocator.getExecutableLocation();
        if (exeLoc.isPresent()) {
            exeLocInfoAreaTextBuilder.append("\n");
            exeLocInfoAreaTextBuilder.append("KalixIDE executable located at: ");
            exeLocInfoAreaTextBuilder.append(exeLoc.get());
        }
        String infoAreaText = exeLocInfoAreaTextBuilder.toString();
        exeLocInfoArea.setText(infoAreaText);
        formPanel.add(exeLocInfoArea, gbc);
    }

    private void saveBinaryPath() {
        String path = binaryPathField.getText().trim();
        if (!path.equals(PreferenceKeys.CLI_BINARY_PATH.get())) {
            PreferenceKeys.CLI_BINARY_PATH.set(path);
        }
    }

    private void browseBinary(ActionEvent e) {
        java.util.Optional<File> chosen =
            KalixFileDialog.chooseFolder(this)
                .title("Add Directory to Search Path")
                .show();
        if (chosen.isPresent()) {
            File selectedDir = chosen.get();

            // Convert to relative path (relative to current working directory)
            java.nio.file.Path currentDir = java.nio.file.Paths.get("").toAbsolutePath();
            java.nio.file.Path selectedPath = selectedDir.toPath().toAbsolutePath();
            java.nio.file.Path relativePath;

            try {
                relativePath = currentDir.relativize(selectedPath);
            } catch (IllegalArgumentException ex) {
                // Paths on different drives (Windows) - use absolute path
                relativePath = selectedPath;
            }

            String pathToAdd = relativePath.toString();

            // Append to existing path with ';' delimiter
            String currentPath = binaryPathField.getText().trim();
            String newPath;
            if (currentPath.isEmpty()) {
                newPath = pathToAdd;
            } else {
                newPath = currentPath + ";" + pathToAdd;
            }

            binaryPathField.setText(newPath);
            PreferenceKeys.CLI_BINARY_PATH.set(newPath);
            statusLabel.setText("Status: Path changed - click Test");
            statusLabel.setForeground(Color.BLUE);
            pathLabel.setText("");
        }
    }

    private void testConnection(ActionEvent e) {
        String path = binaryPathField.getText().trim();

        // Save the path first
        PreferenceKeys.CLI_BINARY_PATH.set(path);

        testButton.setEnabled(false);
        statusLabel.setText("Status: Testing...");
        statusLabel.setForeground(Color.BLUE);
        pathLabel.setText("");

        SwingUtilities.invokeLater(() -> {
            try {
                // Use findKalixCli which handles semicolon-delimited paths
                Optional<KalixCliLocator.CliLocation> location =
                    KalixCliLocator.findKalixCli(path);

                if (location.isPresent()) {
                    statusLabel.setText("Status: ✓ Found - " + location.get().getVersion());
                    statusLabel.setForeground(new Color(0, 128, 0));
                    pathLabel.setText("Path: " + location.get().getPath().toAbsolutePath());
                } else {
                    if (path.isEmpty()) {
                        statusLabel.setText("Status: ✗ Not found in system PATH");
                    } else {
                        statusLabel.setText("Status: ✗ Not found in specified directories");
                    }
                    statusLabel.setForeground(Color.RED);
                    pathLabel.setText("");
                }
            } catch (Exception ex) {
                statusLabel.setText("Status: ✗ Test failed");
                statusLabel.setForeground(Color.RED);
                pathLabel.setText("");
            } finally {
                testButton.setEnabled(true);
            }
        });
    }
}
