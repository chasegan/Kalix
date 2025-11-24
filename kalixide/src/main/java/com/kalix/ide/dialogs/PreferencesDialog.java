package com.kalix.ide.dialogs;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.LinterPreferencesPanel;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.managers.ThemeManager;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

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
    private final SchemaManager schemaManager;

    // Callback interface for preference changes
    public interface PreferenceChangeCallback {
        void onAutoReloadChanged(boolean enabled);
        void onLintingChanged(boolean enabled);
        void onGridlinesChanged(boolean visible);
        void onFlowVizPreferencesChanged();
        void onMapPreferencesChanged();
        void onSystemActionRequested(String action);
        void onFontSizeChanged(int fontSize);
    }

    private PreferenceChangeCallback changeCallback;

    // Main components
    private JTree preferencesTree;
    private JPanel contentPanel;
    private CardLayout cardLayout;

    // Preference panels
    private ThemePreferencePanel themePanel;
    private FilePreferencePanel filePanel;
    private LoadSavePreferencePanel loadSavePanel;
    private KalixCliPreferencePanel kalixCliPanel;
    private CompressionPreferencePanel compressionPanel;
    private LinterPreferencesPanel linterPanel;
    private SystemPreferencePanel systemPanel;
    private NodeDiagramPreferencePanel nodeDiagramPanel;
    private FontPreferencePanel fontPanel;

    // Panel identifiers
    private static final String THEME_PANEL = "theme";
    private static final String FILE_PANEL = "file";
    private static final String LOAD_SAVE_PANEL = "loadsave";
    private static final String KALIXCLI_PANEL = "kalixcli";
    private static final String COMPRESSION_PANEL = "compression";
    private static final String LINTER_PANEL = "linter";
    private static final String SYSTEM_PANEL = "system";
    private static final String NODE_DIAGRAM_PANEL = "nodediagram";
    private static final String FONT_PANEL = "font";

    /**
     * Creates a new professional preferences dialog with callback.
     */
    public PreferencesDialog(JFrame parent, ThemeManager themeManager, EnhancedTextEditor textEditor,
                           SchemaManager schemaManager, PreferenceChangeCallback changeCallback) {
        super(parent, "Preferences", true);
        this.parent = parent;
        this.themeManager = themeManager;
        this.textEditor = textEditor;
        this.schemaManager = schemaManager;
        this.changeCallback = changeCallback;

        initializeDialog();
    }

    /**
     * Initializes the dialog layout and components.
     */
    private void initializeDialog() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Ensure tooltips are enabled
        ToolTipManager.sharedInstance().setEnabled(true);
        ToolTipManager.sharedInstance().setInitialDelay(300);
        ToolTipManager.sharedInstance().setDismissDelay(500);

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

        // General branch (first item)
        root.add(new DefaultMutableTreeNode("General"));

        // Editor branch
        DefaultMutableTreeNode editor = new DefaultMutableTreeNode("Editor");
        editor.add(new DefaultMutableTreeNode("Load and Save"));
        editor.add(new DefaultMutableTreeNode("Themes"));
        editor.add(new DefaultMutableTreeNode("Font"));
        editor.add(new DefaultMutableTreeNode("Model Linting"));
        editor.add(new DefaultMutableTreeNode("Node Diagram"));
        root.add(editor);

        // Run Management below Model Linting
        DefaultMutableTreeNode runManagement = new DefaultMutableTreeNode("Run Management");
        runManagement.add(new DefaultMutableTreeNode("Data & Visualization"));
        root.add(runManagement);

        // Simulation branch
        DefaultMutableTreeNode kalix = new DefaultMutableTreeNode("Simulation");
        kalix.add(new DefaultMutableTreeNode("Kalix"));
        root.add(kalix);

        // Integrations at the bottom
        root.add(new DefaultMutableTreeNode("Integrations"));

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
                case "Load and Save":
                    cardLayout.show(contentPanel, LOAD_SAVE_PANEL);
                    break;
                case "Themes":
                    cardLayout.show(contentPanel, THEME_PANEL);
                    break;
                case "Font":
                    cardLayout.show(contentPanel, FONT_PANEL);
                    break;
                case "Integrations":
                    cardLayout.show(contentPanel, FILE_PANEL);
                    break;
                case "Kalix":
                    cardLayout.show(contentPanel, KALIXCLI_PANEL);
                    break;
                case "Data & Visualization":
                    cardLayout.show(contentPanel, COMPRESSION_PANEL);
                    break;
                case "Model Linting":
                    cardLayout.show(contentPanel, LINTER_PANEL);
                    break;
                case "Node Diagram":
                    cardLayout.show(contentPanel, NODE_DIAGRAM_PANEL);
                    break;
                case "General":
                    cardLayout.show(contentPanel, SYSTEM_PANEL);
                    break;
            }
        });

        // Add context menu for tree
        createTreeContextMenu();
    }

    /**
     * Creates all preference panels and adds them to the card layout.
     */
    private void createPreferencePanels() {
        themePanel = new ThemePreferencePanel();
        filePanel = new FilePreferencePanel();
        loadSavePanel = new LoadSavePreferencePanel();
        kalixCliPanel = new KalixCliPreferencePanel();
        compressionPanel = new CompressionPreferencePanel();
        linterPanel = new LinterPreferencesPanel(schemaManager, textEditor.getLinterManager());

        // Set callback to notify when linting is enabled/disabled
        linterPanel.setLintingChangeCallback(enabled -> {
            if (changeCallback != null) {
                changeCallback.onLintingChanged(enabled);
            }
        });

        systemPanel = new SystemPreferencePanel();
        nodeDiagramPanel = new NodeDiagramPreferencePanel();
        fontPanel = new FontPreferencePanel();

        contentPanel.add(themePanel, THEME_PANEL);
        contentPanel.add(filePanel, FILE_PANEL);
        contentPanel.add(loadSavePanel, LOAD_SAVE_PANEL);
        contentPanel.add(kalixCliPanel, KALIXCLI_PANEL);
        contentPanel.add(compressionPanel, COMPRESSION_PANEL);
        contentPanel.add(linterPanel, LINTER_PANEL);
        contentPanel.add(systemPanel, SYSTEM_PANEL);
        contentPanel.add(nodeDiagramPanel, NODE_DIAGRAM_PANEL);
        contentPanel.add(fontPanel, FONT_PANEL);
    }

    /**
     * Creates a context menu for the preferences tree.
     */
    private void createTreeContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem expandAllItem = new JMenuItem("Expand All");
        expandAllItem.addActionListener(e -> expandAllNodes());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse All");
        collapseAllItem.addActionListener(e -> collapseAllNodes());
        contextMenu.add(collapseAllItem);

        preferencesTree.setComponentPopupMenu(contextMenu);
    }

    /**
     * Expands all nodes in the preferences tree.
     */
    private void expandAllNodes() {
        for (int i = 0; i < preferencesTree.getRowCount(); i++) {
            preferencesTree.expandRow(i);
        }
    }

    /**
     * Collapses all nodes in the preferences tree.
     */
    private void collapseAllNodes() {
        for (int i = preferencesTree.getRowCount() - 1; i >= 0; i--) {
            preferencesTree.collapseRow(i);
        }
    }

    /**
     * Creates the button panel.
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            savePendingChanges();
            dispose();
        });

        buttonPanel.add(closeButton);
        getRootPane().setDefaultButton(closeButton);

        return buttonPanel;
    }

    /**
     * Saves any pending changes from text fields that haven't been committed yet.
     */
    private void savePendingChanges() {
        // Save Kalix CLI path if it has been edited
        if (kalixCliPanel != null && kalixCliPanel.binaryPathField != null) {
            String path = kalixCliPanel.binaryPathField.getText().trim();
            PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, path);
        }
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
     * Cleanup when dialog is closed.
     */
    @Override
    public void dispose() {
        // Cleanup linter panel listeners
        if (linterPanel != null) {
            linterPanel.dispose();
        }
        super.dispose();
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
        private JComboBox<com.kalix.ide.themes.NodeTheme.Theme> nodeThemeComboBox;
        private JComboBox<com.kalix.ide.themes.SyntaxTheme.Theme> syntaxThemeComboBox;

        public ThemePreferencePanel() {
            super("Themes");
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
            nodeThemeComboBox = new JComboBox<>(com.kalix.ide.themes.NodeTheme.getAllThemes());

            // Set custom renderer to show display names instead of enum names
            nodeThemeComboBox.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof com.kalix.ide.themes.NodeTheme.Theme) {
                        setText(((com.kalix.ide.themes.NodeTheme.Theme) value).getDisplayName());
                    }
                    return this;
                }
            });

            // Get current node theme from preference or default
            String currentNodeThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
            com.kalix.ide.themes.NodeTheme.Theme currentNodeTheme = com.kalix.ide.themes.NodeTheme.themeFromString(currentNodeThemeName);
            nodeThemeComboBox.setSelectedItem(currentNodeTheme);

            nodeThemeComboBox.addActionListener(e -> {
                com.kalix.ide.themes.NodeTheme.Theme selectedNodeTheme = (com.kalix.ide.themes.NodeTheme.Theme) nodeThemeComboBox.getSelectedItem();
                if (selectedNodeTheme != null) {
                    // Save preference using display name instead of enum name
                    PreferenceManager.setFileString(PreferenceKeys.UI_NODE_THEME, selectedNodeTheme.getDisplayName());

                    // Notify callback to update map display
                    if (changeCallback != null) {
                        changeCallback.onMapPreferencesChanged();
                    }
                }
            });
            formPanel.add(nodeThemeComboBox, gbc);

            // Syntax theme selection
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            formPanel.add(new JLabel("Syntax Theme:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            syntaxThemeComboBox = new JComboBox<>(com.kalix.ide.themes.SyntaxTheme.getAllThemes());

            // Get current syntax theme from preference or default
            String currentSyntaxThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            com.kalix.ide.themes.SyntaxTheme.Theme currentSyntaxTheme = com.kalix.ide.themes.SyntaxTheme.getThemeByName(currentSyntaxThemeName);
            syntaxThemeComboBox.setSelectedItem(currentSyntaxTheme);

            syntaxThemeComboBox.addActionListener(e -> {
                com.kalix.ide.themes.SyntaxTheme.Theme selectedSyntaxTheme = (com.kalix.ide.themes.SyntaxTheme.Theme) syntaxThemeComboBox.getSelectedItem();
                if (selectedSyntaxTheme != null) {
                    // Save preference
                    PreferenceManager.setFileString(PreferenceKeys.UI_SYNTAX_THEME, selectedSyntaxTheme.name());

                    // Notify ThemeManager to update syntax highlighting
                    if (themeManager != null) {
                        themeManager.updateSyntaxTheme(selectedSyntaxTheme);
                    }
                }
            });
            formPanel.add(syntaxThemeComboBox, gbc);


            add(formPanel, BorderLayout.NORTH);
        }
    }

    /**
     * File preferences panel.
     */
    private class FilePreferencePanel extends PreferencePanel {
        private JTextField externalEditorField;
        private JTextField pythonTerminalField;

        public FilePreferencePanel() {
            super("Integrations");
            initializePanel();
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
            externalEditorField = new JTextField(PreferenceManager.getFileString(
                PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND, "code <folder_path> <file_path>"));
            externalEditorField.setToolTipText("Command to launch an external editor. Use <folder_path> for the folder containing the current file and <file_path> for the full path to the current file.");
            externalEditorField.addActionListener(e -> saveExternalEditorCommand());
            externalEditorField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { saveExternalEditorCommand(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { saveExternalEditorCommand(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { saveExternalEditorCommand(); }
            });
            formPanel.add(externalEditorField, gbc);

            // Python terminal command
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            formPanel.add(new JLabel("Terminal Command:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            pythonTerminalField = new JTextField(PreferenceManager.getFileString(
                PreferenceKeys.FILE_PYTHON_TERMINAL_COMMAND, getDefaultPythonTerminalCommand()));
            pythonTerminalField.setToolTipText("Command to launch a Python-enabled terminal (e.g., Anaconda). Leave empty to use regular terminal.");
            pythonTerminalField.addActionListener(e -> savePythonTerminalCommand());
            pythonTerminalField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { savePythonTerminalCommand(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { savePythonTerminalCommand(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { savePythonTerminalCommand(); }
            });
            formPanel.add(pythonTerminalField, gbc);

            add(formPanel, BorderLayout.NORTH);
        }

        private void saveExternalEditorCommand() {
            String command = externalEditorField.getText().trim();
            PreferenceManager.setFileString(PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND, command);
        }

        private void savePythonTerminalCommand() {
            String command = pythonTerminalField.getText().trim();
            PreferenceManager.setFileString(PreferenceKeys.FILE_PYTHON_TERMINAL_COMMAND, command);
        }

        private String getDefaultPythonTerminalCommand() {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                return PreferenceKeys.DEFAULT_PYTHON_TERMINAL_COMMAND_WINDOWS;
            }
            return "";
        }
    }

    /**
     * Load and Save preferences panel.
     */
    private class LoadSavePreferencePanel extends PreferencePanel {
        private JCheckBox autoReloadCheckBox;
        private JCheckBox promptSaveOnExitCheckBox;

        public LoadSavePreferencePanel() {
            super("Load and Save");
            initializePanel();
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
            autoReloadCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, false));
            autoReloadCheckBox.setToolTipText("Automatically reload clean (unchanged) files when modified by external programs. Files with unsaved changes will not be reloaded to prevent data loss.");
            autoReloadCheckBox.addActionListener(e -> {
                boolean enabled = autoReloadCheckBox.isSelected();
                PreferenceManager.setFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, enabled);

                // Notify callback to update file watching
                if (changeCallback != null) {
                    changeCallback.onAutoReloadChanged(enabled);
                }
            });
            formPanel.add(autoReloadCheckBox, gbc);

            // Prompt save on exit setting
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
            gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            promptSaveOnExitCheckBox = new JCheckBox("Prompt to save unsaved changes before closing");
            promptSaveOnExitCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT, true));
            promptSaveOnExitCheckBox.setToolTipText("Show a confirmation dialog when closing the application with unsaved changes, giving you the option to save your work.");
            promptSaveOnExitCheckBox.addActionListener(e -> {
                boolean enabled = promptSaveOnExitCheckBox.isSelected();
                PreferenceManager.setFileBoolean(PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT, enabled);
            });
            formPanel.add(promptSaveOnExitCheckBox, gbc);

            add(formPanel, BorderLayout.NORTH);
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
        private JTextArea pathLabel;

        public KalixCliPreferencePanel() {
            super("Kalix");
            initializePanel();
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
            infoArea.setText("Specify directories to search for the Kalix CLI binary. Multiple directories can be " +
                "specified using ';' as a delimiter (e.g., /usr/local/bin;/opt/kalix/bin). If left empty, the system will " +
                "search for 'kalix' in the system PATH.");
            formPanel.add(infoArea, gbc);

            // Binary path
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            formPanel.add(new JLabel("Path:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            binaryPathField = new JTextField(PreferenceManager.getFileString(PreferenceKeys.CLI_BINARY_PATH, ""));
            binaryPathField.setToolTipText("Leave empty to use kalix from system PATH. Use ';' to separate multiple directories.");
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
        }

        private void browseBinary(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Add Directory to Search Path");
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selectedDir = fileChooser.getSelectedFile();

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
                PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, newPath);
                statusLabel.setText("Status: Path changed - click Test");
                statusLabel.setForeground(Color.BLUE);
                pathLabel.setText("");
            }
        }

        private void testConnection(ActionEvent e) {
            String path = binaryPathField.getText().trim();

            // Save the path first
            PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, path);

            testButton.setEnabled(false);
            statusLabel.setText("Status: Testing...");
            statusLabel.setForeground(Color.BLUE);
            pathLabel.setText("");

            SwingUtilities.invokeLater(() -> {
                try {
                    // Use findKalixCli which handles semicolon-delimited paths
                    Optional<com.kalix.ide.cli.KalixCliLocator.CliLocation> location =
                        com.kalix.ide.cli.KalixCliLocator.findKalixCli(path);

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

    /**
     * Compression preferences panel.
     */
    private class CompressionPreferencePanel extends PreferencePanel {
        private JCheckBox precision64CheckBox;
        private JCheckBox showCoordinatesCheckBox;
        private JCheckBox autoYModeCheckBox;
        private JTextField logScaleMinField;

        public CompressionPreferencePanel() {
            super("Data & Visualization");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // 64-bit precision setting
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            precision64CheckBox = new JCheckBox("Use 64-bit precision for data export");
            precision64CheckBox.setToolTipText("Higher accuracy but larger file sizes; 32-bit is sufficient for most applications");
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
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            showCoordinatesCheckBox = new JCheckBox("Show coordinates in FlowViz");
            showCoordinatesCheckBox.setToolTipText("Display current cursor position in FlowViz charts");
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
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            autoYModeCheckBox = new JCheckBox("Enable Auto-Y mode in FlowViz");
            autoYModeCheckBox.setToolTipText("Automatically adjust Y-axis scaling in FlowViz charts");
            autoYModeCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true));
            autoYModeCheckBox.addActionListener(e -> {
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, autoYModeCheckBox.isSelected());

                // Notify callback to update FlowViz windows
                if (changeCallback != null) {
                    changeCallback.onFlowVizPreferencesChanged();
                }
            });
            formPanel.add(autoYModeCheckBox, gbc);

            // Log scale minimum threshold setting
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
            formPanel.add(new JLabel("Log scale auto-zoom minimum:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            logScaleMinField = new JTextField(String.valueOf(
                PreferenceManager.getFileDouble(PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD, 0.001)), 10);
            logScaleMinField.setToolTipText("Minimum Y value for log scale auto-zoom (prevents excessive zoom-out from tiny values)");
            logScaleMinField.addActionListener(e -> saveLogScaleMinimum());
            logScaleMinField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { saveLogScaleMinimum(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { saveLogScaleMinimum(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { saveLogScaleMinimum(); }
            });
            formPanel.add(logScaleMinField, gbc);

            add(formPanel, BorderLayout.NORTH);
        }

        private void saveLogScaleMinimum() {
            try {
                double value = Double.parseDouble(logScaleMinField.getText().trim());
                if (value > 0) {
                    PreferenceManager.setFileDouble(PreferenceKeys.PLOT_LOG_SCALE_MIN_THRESHOLD, value);

                    // Notify callback to update FlowViz windows
                    if (changeCallback != null) {
                        changeCallback.onFlowVizPreferencesChanged();
                    }
                }
            } catch (NumberFormatException ex) {
                // Invalid input - don't save
            }
        }
    }

    /**
     * System preferences panel.
     */
    private class SystemPreferencePanel extends PreferencePanel {
        public SystemPreferencePanel() {
            super("General");
            initializePanel();
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
                if (changeCallback != null) {
                    changeCallback.onSystemActionRequested("clearAppData");
                }
            }
        }
    }

    /**
     * Node diagram preferences panel.
     */
    private class NodeDiagramPreferencePanel extends PreferencePanel {
        private JCheckBox gridlinesCheckBox;

        public NodeDiagramPreferencePanel() {
            super("Node Diagram");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Map gridlines setting
            gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            gridlinesCheckBox = new JCheckBox("Show gridlines on map");
            gridlinesCheckBox.setSelected(PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true));
            gridlinesCheckBox.addActionListener(e -> {
                boolean enabled = gridlinesCheckBox.isSelected();
                PreferenceManager.setFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, enabled);

                // Notify callback to update map display and toolbar button
                if (changeCallback != null) {
                    changeCallback.onMapPreferencesChanged();
                    changeCallback.onGridlinesChanged(enabled);
                }
            });
            formPanel.add(gridlinesCheckBox, gbc);

            add(formPanel, BorderLayout.NORTH);
        }
    }

    /**
     * Font preferences panel.
     */
    private class FontPreferencePanel extends PreferencePanel {
        private JSpinner fontSizeSpinner;

        public FontPreferencePanel() {
            super("Font");
            initializePanel();
        }

        private void initializePanel() {
            JPanel formPanel = createFormPanel();
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            // Font size setting
            gbc.gridx = 0; gbc.gridy = 0;
            formPanel.add(new JLabel("Font Size:"), gbc);

            gbc.gridx = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            SpinnerNumberModel spinnerModel = new SpinnerNumberModel(
                PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12), // current value
                8,    // minimum
                24,   // maximum
                1     // step
            );
            fontSizeSpinner = new JSpinner(spinnerModel);
            fontSizeSpinner.setToolTipText("Set the font size for the text editor (8-24 points)");

            // Set a reasonable width for the spinner
            JComponent editor = fontSizeSpinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor) {
                ((JSpinner.DefaultEditor) editor).getTextField().setColumns(3);
            }

            fontSizeSpinner.addChangeListener(e -> {
                int fontSize = (Integer) fontSizeSpinner.getValue();
                PreferenceManager.setFileInt(PreferenceKeys.EDITOR_FONT_SIZE, fontSize);

                // Update the text editor font immediately
                if (textEditor != null) {
                    textEditor.updateFontSize(fontSize);
                }

                // Notify callback listeners (e.g., to update MinimalEditorWindows)
                if (changeCallback != null) {
                    changeCallback.onFontSizeChanged(fontSize);
                }
            });
            formPanel.add(fontSizeSpinner, gbc);

            gbc.gridx = 2; gbc.weightx = 0;
            formPanel.add(new JLabel("pt"), gbc);

            // Add a filler to push everything to the left
            gbc.gridx = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
            formPanel.add(Box.createHorizontalGlue(), gbc);

            add(formPanel, BorderLayout.NORTH);
        }
    }
}