package com.kalix.gui.dialogs;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;
import com.kalix.gui.managers.ThemeManager;
import com.kalix.gui.preferences.PreferenceManager;
import com.kalix.gui.preferences.PreferenceKeys;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Optional;

/**
 * Professional preferences dialog with tree-based navigation.
 * Organizes preferences into hierarchical categories with immediate application of changes.
 */
public class PreferencesDialog extends JDialog {

    private final JFrame parent;
    private final ThemeManager themeManager;
    private final EnhancedTextEditor textEditor;

    // Callback interface for preference changes
    public interface PreferenceChangeCallback {
        void onAutoReloadChanged(boolean enabled);
        void onFlowVizPreferencesChanged();
        void onMapPreferencesChanged();
        void onSystemActionRequested(String action);
    }

    private PreferenceChangeCallback changeCallback;

    // Main components
    private JTree preferencesTree;
    private JPanel contentPanel;
    private CardLayout cardLayout;

    // Preference panels
    private ThemePreferencePanel themePanel;
    private EditorPreferencePanel editorPanel;
    private FilePreferencePanel filePanel;
    private KalixCliPreferencePanel kalixCliPanel;
    private CompressionPreferencePanel compressionPanel;
    private SystemPreferencePanel systemPanel;

    // Panel identifiers
    private static final String THEME_PANEL = "theme";
    private static final String EDITOR_PANEL = "editor";
    private static final String FILE_PANEL = "file";
    private static final String KALIXCLI_PANEL = "kalixcli";
    private static final String COMPRESSION_PANEL = "compression";
    private static final String SYSTEM_PANEL = "system";

    /**
     * Creates a new professional preferences dialog.
     */
    public PreferencesDialog(JFrame parent, ThemeManager themeManager, EnhancedTextEditor textEditor) {
        super(parent, "Preferences", true);
        this.parent = parent;
        this.themeManager = themeManager;
        this.textEditor = textEditor;

        initializeDialog();
    }

    /**
     * Creates a new professional preferences dialog with callback.
     */
    public PreferencesDialog(JFrame parent, ThemeManager themeManager, EnhancedTextEditor textEditor,
                           PreferenceChangeCallback changeCallback) {
        super(parent, "Preferences", true);
        this.parent = parent;
        this.themeManager = themeManager;
        this.textEditor = textEditor;
        this.changeCallback = changeCallback;

        initializeDialog();
    }

    /**
     * Initializes the dialog layout and components.
     */
    private void initializeDialog() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Create tree navigation
        createPreferencesTree();
        JScrollPane treeScrollPane = new JScrollPane(preferencesTree);
        treeScrollPane.setPreferredSize(new Dimension(200, 0));
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 5));

        // Create content panel with card layout
        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 10));

        // Create and add preference panels
        createPreferencePanels();

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(treeScrollPane);
        splitPane.setRightComponent(contentPanel);
        splitPane.setDividerLocation(200);
        splitPane.setDividerSize(6);
        splitPane.setBorder(null);

        add(splitPane, BorderLayout.CENTER);

        // Create button panel
        add(createButtonPanel(), BorderLayout.SOUTH);

        // Set dialog properties
        setSize(700, 500);
        setLocationRelativeTo(parent);
        setResizable(true);

        // Select first item by default
        preferencesTree.setSelectionRow(1); // Select "Theme" initially
    }

    /**
     * Creates the preferences tree structure.
     */
    private void createPreferencesTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Preferences");

        // Appearance branch
        DefaultMutableTreeNode appearance = new DefaultMutableTreeNode("Appearance");
        appearance.add(new DefaultMutableTreeNode("Theme"));
        appearance.add(new DefaultMutableTreeNode("Editor"));
        root.add(appearance);

        // File branch
        root.add(new DefaultMutableTreeNode("File"));

        // Kalix branch
        DefaultMutableTreeNode kalix = new DefaultMutableTreeNode("Kalix");
        kalix.add(new DefaultMutableTreeNode("Kalixcli"));
        kalix.add(new DefaultMutableTreeNode("Data & Visualization"));
        root.add(kalix);

        // System branch
        root.add(new DefaultMutableTreeNode("System"));

        preferencesTree = new JTree(new DefaultTreeModel(root));
        preferencesTree.setRootVisible(false);
        preferencesTree.setShowsRootHandles(true);
        preferencesTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Expand all nodes
        for (int i = 0; i < preferencesTree.getRowCount(); i++) {
            preferencesTree.expandRow(i);
        }

        // Add selection listener
        preferencesTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) preferencesTree.getLastSelectedPathComponent();
            if (node == null) return;

            String nodeName = node.toString();
            switch (nodeName) {
                case "Theme":
                    cardLayout.show(contentPanel, THEME_PANEL);
                    break;
                case "Editor":
                    cardLayout.show(contentPanel, EDITOR_PANEL);
                    break;
                case "File":
                    cardLayout.show(contentPanel, FILE_PANEL);
                    break;
                case "Kalixcli":
                    cardLayout.show(contentPanel, KALIXCLI_PANEL);
                    break;
                case "Data & Visualization":
                    cardLayout.show(contentPanel, COMPRESSION_PANEL);
                    break;
                case "System":
                    cardLayout.show(contentPanel, SYSTEM_PANEL);
                    break;
            }
        });
    }

    /**
     * Creates all preference panels and adds them to the card layout.
     */
    private void createPreferencePanels() {
        themePanel = new ThemePreferencePanel();
        editorPanel = new EditorPreferencePanel();
        filePanel = new FilePreferencePanel();
        kalixCliPanel = new KalixCliPreferencePanel();
        compressionPanel = new CompressionPreferencePanel();
        systemPanel = new SystemPreferencePanel();

        contentPanel.add(themePanel, THEME_PANEL);
        contentPanel.add(editorPanel, EDITOR_PANEL);
        contentPanel.add(filePanel, FILE_PANEL);
        contentPanel.add(kalixCliPanel, KALIXCLI_PANEL);
        contentPanel.add(compressionPanel, COMPRESSION_PANEL);
        contentPanel.add(systemPanel, SYSTEM_PANEL);
    }

    /**
     * Creates the button panel.
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());

        buttonPanel.add(closeButton);
        getRootPane().setDefaultButton(closeButton);

        return buttonPanel;
    }

    /**
     * Shows the preferences dialog.
     * @return true if any preferences were changed, false otherwise
     */
    public boolean showDialog() {
        setVisible(true);
        // Since preferences apply immediately, we return false for now
        // Future implementation could track changes for status updates
        return false;
    }

    /**
     * Base class for all preference panels.
     */
    private abstract class PreferencePanel extends JPanel {
        public PreferencePanel(String title) {
            setLayout(new BorderLayout());
            setBorder(BorderFactory.createTitledBorder(title));
        }

        protected JPanel createFormPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            return panel;
        }
    }

    /**
     * Theme preferences panel.
     */
    private class ThemePreferencePanel extends PreferencePanel {
        private JComboBox<String> themeComboBox;
        private JComboBox<com.kalix.gui.themes.NodeTheme.Theme> nodeThemeComboBox;

        public ThemePreferencePanel() {
            super("Theme Settings");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Application theme selection
            gbc.gridx = 0; gbc.gridy = 0;
            formPanel.add(new JLabel("Application Theme:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            themeComboBox = new JComboBox<>(AppConstants.AVAILABLE_THEMES);
            themeComboBox.setSelectedItem(themeManager.getCurrentTheme());
            themeComboBox.addActionListener(e -> {
                String selectedTheme = (String) themeComboBox.getSelectedItem();
                if (!selectedTheme.equals(themeManager.getCurrentTheme())) {
                    themeManager.switchTheme(selectedTheme);
                }
            });
            formPanel.add(themeComboBox, gbc);

            // Node theme selection
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            formPanel.add(new JLabel("Node Theme:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            nodeThemeComboBox = new JComboBox<>(com.kalix.gui.themes.NodeTheme.getAllThemes());

            // Get current node theme from preference or default
            String currentNodeThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
            com.kalix.gui.themes.NodeTheme.Theme currentNodeTheme = com.kalix.gui.themes.NodeTheme.themeFromString(currentNodeThemeName);
            nodeThemeComboBox.setSelectedItem(currentNodeTheme);

            nodeThemeComboBox.addActionListener(e -> {
                com.kalix.gui.themes.NodeTheme.Theme selectedNodeTheme = (com.kalix.gui.themes.NodeTheme.Theme) nodeThemeComboBox.getSelectedItem();
                if (selectedNodeTheme != null) {
                    // Save preference
                    PreferenceManager.setFileString(PreferenceKeys.UI_NODE_THEME, selectedNodeTheme.name());

                    // Notify callback to update map display
                    if (changeCallback != null) {
                        changeCallback.onMapPreferencesChanged();
                    }
                }
            });
            formPanel.add(nodeThemeComboBox, gbc);

            // Description
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            JTextArea description = new JTextArea();
            description.setText("Application Theme: Choose the visual theme for the application interface.\n\n" +
                "Node Theme: Select the color scheme and styling for map nodes and connections.\n\n" +
                "Changes are applied immediately and saved automatically.");
            description.setEditable(false);
            description.setOpaque(false);
            description.setWrapStyleWord(true);
            description.setLineWrap(true);
            formPanel.add(description, gbc);

            add(formPanel, BorderLayout.CENTER);
        }
    }

    /**
     * Editor preferences panel.
     */
    private class EditorPreferencePanel extends PreferencePanel {
        private JCheckBox gridlinesCheckBox;

        public EditorPreferencePanel() {
            super("Editor Settings");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Map gridlines setting
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
            gridlinesCheckBox = new JCheckBox("Show gridlines on map");
            gridlinesCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true));
            gridlinesCheckBox.addActionListener(e -> {
                boolean enabled = gridlinesCheckBox.isSelected();
                PreferenceManager.setFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, enabled);

                // Notify callback to update map display
                if (changeCallback != null) {
                    changeCallback.onMapPreferencesChanged();
                }
            });
            formPanel.add(gridlinesCheckBox, gbc);

            // Future editor settings placeholder
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            JTextArea placeholder = new JTextArea();
            placeholder.setText("Additional editor preferences will be added here in future updates.\n\n" +
                "This could include:\n" +
                "• Font size and family\n" +
                "• Line wrapping settings\n" +
                "• Syntax highlighting preferences\n" +
                "• Tab size and indentation\n" +
                "• Auto-completion settings");
            placeholder.setEditable(false);
            placeholder.setOpaque(false);
            placeholder.setWrapStyleWord(true);
            placeholder.setLineWrap(true);
            formPanel.add(placeholder, gbc);

            add(formPanel, BorderLayout.CENTER);
        }
    }

    /**
     * File preferences panel.
     */
    private class FilePreferencePanel extends PreferencePanel {
        private JCheckBox autoReloadCheckBox;

        public FilePreferencePanel() {
            super("File Settings");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Auto-reload setting
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
            autoReloadCheckBox = new JCheckBox("Auto-reload clean files when changed externally");
            autoReloadCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, false));
            autoReloadCheckBox.addActionListener(e -> {
                boolean enabled = autoReloadCheckBox.isSelected();
                PreferenceManager.setFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, enabled);

                // Notify callback to update file watching
                if (changeCallback != null) {
                    changeCallback.onAutoReloadChanged(enabled);
                }
            });
            formPanel.add(autoReloadCheckBox, gbc);

            // Description
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            JTextArea description = new JTextArea();
            description.setText("When enabled, clean (unchanged) files will automatically reload if modified " +
                "by external programs. Files with unsaved changes will not be automatically reloaded to " +
                "prevent data loss.");
            description.setEditable(false);
            description.setOpaque(false);
            description.setWrapStyleWord(true);
            description.setLineWrap(true);
            formPanel.add(description, gbc);

            add(formPanel, BorderLayout.CENTER);
        }
    }

    /**
     * Kalix CLI preferences panel.
     */
    private class KalixCliPreferencePanel extends PreferencePanel {
        private JTextField binaryPathField;
        private JButton browseButton;
        private JButton testButton;
        private JLabel statusLabel;

        public KalixCliPreferencePanel() {
            super("Kalixcli Settings");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Binary path
            gbc.gridx = 0; gbc.gridy = 0;
            formPanel.add(new JLabel("Binary Path:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            binaryPathField = new JTextField(PreferenceManager.getFileString(PreferenceKeys.CLI_BINARY_PATH, ""));
            binaryPathField.setToolTipText("Leave empty to use kalixcli from system PATH");
            formPanel.add(binaryPathField, gbc);

            gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            browseButton = new JButton("Browse...");
            browseButton.addActionListener(this::browseBinary);
            formPanel.add(browseButton, gbc);

            // Test and status
            gbc.gridx = 0; gbc.gridy = 1;
            testButton = new JButton("Test");
            testButton.addActionListener(this::testConnection);
            formPanel.add(testButton, gbc);

            gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            statusLabel = new JLabel("Status: Not tested");
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
            formPanel.add(statusLabel, gbc);

            // Info area
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            JTextArea infoArea = new JTextArea();
            infoArea.setEditable(false);
            infoArea.setOpaque(false);
            infoArea.setWrapStyleWord(true);
            infoArea.setLineWrap(true);
            infoArea.setText("Configure the path to the kalixcli binary. If left empty, the system will " +
                "search for 'kalixcli' in the system PATH and common installation directories.");
            formPanel.add(infoArea, gbc);

            add(formPanel, BorderLayout.CENTER);
        }

        private void browseBinary(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select KalixCLI Binary");
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

            String currentPath = binaryPathField.getText().trim();
            if (!currentPath.isEmpty()) {
                fileChooser.setSelectedFile(new File(currentPath));
            }

            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String newPath = fileChooser.getSelectedFile().getAbsolutePath();
                binaryPathField.setText(newPath);
                PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, newPath);
                statusLabel.setText("Status: Path changed - click Test");
                statusLabel.setForeground(Color.BLUE);
            }
        }

        private void testConnection(ActionEvent e) {
            String path = binaryPathField.getText().trim();

            // Save the path first
            PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, path);

            testButton.setEnabled(false);
            statusLabel.setText("Status: Testing...");
            statusLabel.setForeground(Color.BLUE);

            SwingUtilities.invokeLater(() -> {
                try {
                    if (path.isEmpty()) {
                        Optional<com.kalix.gui.cli.KalixCliLocator.CliLocation> location =
                            com.kalix.gui.cli.KalixCliLocator.findKalixCli();

                        if (location.isPresent()) {
                            statusLabel.setText("Status: ✓ Found - " + location.get().getVersion());
                            statusLabel.setForeground(new Color(0, 128, 0));
                        } else {
                            statusLabel.setText("Status: ✗ Not found in system");
                            statusLabel.setForeground(Color.RED);
                        }
                    } else {
                        java.nio.file.Path binaryPath = java.nio.file.Paths.get(path);
                        if (com.kalix.gui.cli.KalixCliLocator.validateKalixCli(binaryPath)) {
                            statusLabel.setText("Status: ✓ Valid binary");
                            statusLabel.setForeground(new Color(0, 128, 0));
                        } else {
                            statusLabel.setText("Status: ✗ Invalid or inaccessible");
                            statusLabel.setForeground(Color.RED);
                        }
                    }
                } catch (Exception ex) {
                    statusLabel.setText("Status: ✗ Test failed");
                    statusLabel.setForeground(Color.RED);
                } finally {
                    testButton.setEnabled(true);
                }
            });
        }
    }

    /**
     * Compression preferences panel.
     */
    private class CompressionPreferencePanel extends PreferencePanel {
        private JCheckBox precision64CheckBox;
        private JCheckBox showCoordinatesCheckBox;
        private JCheckBox autoYModeCheckBox;

        public CompressionPreferencePanel() {
            super("Data & Visualization Settings");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // 64-bit precision setting
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
            precision64CheckBox = new JCheckBox("Use 64-bit precision for data export");
            precision64CheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, true));
            precision64CheckBox.addActionListener(e -> {
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, precision64CheckBox.isSelected());

                // Notify callback to update FlowViz windows
                if (changeCallback != null) {
                    changeCallback.onFlowVizPreferencesChanged();
                }
            });
            formPanel.add(precision64CheckBox, gbc);

            // Show coordinates setting
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
            showCoordinatesCheckBox = new JCheckBox("Show coordinates in FlowViz");
            showCoordinatesCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, false));
            showCoordinatesCheckBox.addActionListener(e -> {
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_SHOW_COORDINATES, showCoordinatesCheckBox.isSelected());

                // Notify callback to update FlowViz windows
                if (changeCallback != null) {
                    changeCallback.onFlowVizPreferencesChanged();
                }
            });
            formPanel.add(showCoordinatesCheckBox, gbc);

            // Auto-Y mode setting
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
            autoYModeCheckBox = new JCheckBox("Enable Auto-Y mode in FlowViz");
            autoYModeCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true));
            autoYModeCheckBox.addActionListener(e -> {
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, autoYModeCheckBox.isSelected());

                // Notify callback to update FlowViz windows
                if (changeCallback != null) {
                    changeCallback.onFlowVizPreferencesChanged();
                }
            });
            formPanel.add(autoYModeCheckBox, gbc);

            // Description
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            JTextArea description = new JTextArea();
            description.setText("Data Export:\n" +
                "• 64-bit precision provides higher accuracy but larger file sizes\n" +
                "• 32-bit precision is sufficient for most hydrological applications\n\n" +
                "FlowViz Display:\n" +
                "• Show coordinates displays current cursor position\n" +
                "• Auto-Y mode automatically adjusts Y-axis scaling");
            description.setEditable(false);
            description.setOpaque(false);
            description.setWrapStyleWord(true);
            description.setLineWrap(true);
            formPanel.add(description, gbc);

            add(formPanel, BorderLayout.CENTER);
        }
    }

    /**
     * System preferences panel.
     */
    private class SystemPreferencePanel extends PreferencePanel {
        public SystemPreferencePanel() {
            super("System Information");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Preference file location
            gbc.gridx = 0; gbc.gridy = 0;
            formPanel.add(new JLabel("Preferences File:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            JTextField prefFileField = new JTextField(PreferenceManager.getPreferenceFilePath());
            prefFileField.setEditable(false);
            formPanel.add(prefFileField, gbc);

            gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
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
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0; gbc.weighty = 0;
            gbc.insets = new Insets(15, 5, 5, 5); // Extra top margin for separation

            JPanel clearDataPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton clearAppDataButton = new JButton("Clear App Data...");
            clearAppDataButton.addActionListener(e -> clearAppData());
            clearDataPanel.add(clearAppDataButton);

            JLabel clearDataLabel = new JLabel("Clear Kalix application preferences from operating system.");
            clearDataLabel.setFont(clearDataLabel.getFont().deriveFont(Font.ITALIC));
            clearDataPanel.add(clearDataLabel);

            formPanel.add(clearDataPanel, gbc);

            // System info
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            gbc.insets = new Insets(10, 5, 5, 5);
            JTextArea systemInfo = new JTextArea();
            systemInfo.setEditable(false);
            systemInfo.setOpaque(false);
            systemInfo.setText("Application: " + AppConstants.APP_NAME + " " + AppConstants.APP_VERSION + "\n" +
                "Java Version: " + System.getProperty("java.version") + "\n" +
                "Operating System: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n" +
                "User Directory: " + System.getProperty("user.dir"));
            formPanel.add(systemInfo, gbc);

            add(formPanel, BorderLayout.CENTER);
        }

        private void clearAppData() {
            // Show confirmation dialog
            int result = JOptionPane.showConfirmDialog(
                this,
                "This will clear all Kalix GUI application data including:\n\n" +
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
                if (changeCallback != null) {
                    changeCallback.onSystemActionRequested("clearAppData");
                }
            }
        }
    }
}