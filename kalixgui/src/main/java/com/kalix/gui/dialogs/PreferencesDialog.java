package com.kalix.gui.dialogs;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;
import com.kalix.gui.managers.ThemeManager;
import com.kalix.gui.preferences.PreferenceManager;
import com.kalix.gui.preferences.PreferenceKeys;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import java.util.Map;

/**
 * Professional preferences dialog with tree-based navigation for application configuration.
 * Provides centralized access to all application preferences organized by category.
 */
public class PreferencesDialog extends JDialog {

    // Category nodes for the tree
    private enum CategoryNode {
        ROOT("Preferences", null),
        APPEARANCE("Appearance", ROOT),
        THEME("Theme", APPEARANCE),
        EDITOR("Editor", APPEARANCE),
        FILE("File", ROOT),
        KALIX("Kalix", ROOT),
        KALIXCLI("Kalixcli", KALIX),
        COMPRESSION("Compression", KALIX),
        SYSTEM("System", ROOT);

        private final String displayName;
        private final CategoryNode parent;

        CategoryNode(String displayName, CategoryNode parent) {
            this.displayName = displayName;
            this.parent = parent;
        }

        public String getDisplayName() {
            return displayName;
        }

        public CategoryNode getParent() {
            return parent;
        }
    }

    private final JFrame parent;
    private final ThemeManager themeManager;
    private final EnhancedTextEditor textEditor;

    // Main components
    private JTree categoryTree;
    private JPanel rightPanel;
    private CardLayout cardLayout;
    private JPanel cardPanel;

    // Settings panels
    private Map<String, PreferencePanel> preferencePanels;

    // Dialog result
    private boolean settingsChanged = false;
    
    /**
     * Creates a new PreferencesDialog.
     */
    public PreferencesDialog(JFrame parent, ThemeManager themeManager, EnhancedTextEditor textEditor) {
        super(parent, "Preferences", true);
        this.parent = parent;
        this.themeManager = themeManager;
        this.textEditor = textEditor;
        this.preferencePanels = new HashMap<>();

        initializeDialog();
    }
    
    /**
     * Initializes the dialog layout and components.
     */
    private void initializeDialog() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.0); // Keep left panel fixed width

        // Create left panel with tree
        JPanel leftPanel = createTreePanel();
        splitPane.setLeftComponent(leftPanel);

        // Create right panel with card layout for preference panes
        rightPanel = createRightPanel();
        splitPane.setRightComponent(rightPanel);

        add(splitPane, BorderLayout.CENTER);

        // Create button panel
        add(createButtonPanel(), BorderLayout.SOUTH);

        // Set dialog properties
        setSize(700, 500);
        setLocationRelativeTo(parent);
        setResizable(true);
        setMinimumSize(new Dimension(600, 400));

        // Load current settings
        loadSettings();

        // Select the first leaf node by default (Theme)
        selectDefaultCategory();
    }
    
    /**
     * Creates the left panel containing the category tree.
     */
    private JPanel createTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 5));

        // Create tree model
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(CategoryNode.ROOT.getDisplayName());
        buildTreeNodes(rootNode);

        // Create tree
        categoryTree = new JTree(rootNode);
        categoryTree.setRootVisible(false);
        categoryTree.setShowsRootHandles(true);
        categoryTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Add selection listener
        categoryTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) categoryTree.getLastSelectedPathComponent();
            if (node != null && node.isLeaf()) {
                String categoryName = node.toString();
                showPreferencePanel(categoryName);
            }
        });

        // Expand all nodes
        expandAllNodes(categoryTree, 0, categoryTree.getRowCount());

        // Add to scroll pane
        JScrollPane treeScrollPane = new JScrollPane(categoryTree);
        treeScrollPane.setPreferredSize(new Dimension(180, 0));
        panel.add(treeScrollPane, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Builds the tree node structure based on CategoryNode enum.
     */
    private void buildTreeNodes(DefaultMutableTreeNode rootNode) {
        Map<CategoryNode, DefaultMutableTreeNode> nodeMap = new HashMap<>();
        nodeMap.put(CategoryNode.ROOT, rootNode);

        // Create nodes in order
        for (CategoryNode category : CategoryNode.values()) {
            if (category == CategoryNode.ROOT) continue;

            DefaultMutableTreeNode node = new DefaultMutableTreeNode(category.getDisplayName());
            nodeMap.put(category, node);

            DefaultMutableTreeNode parentNode = nodeMap.get(category.getParent());
            if (parentNode != null) {
                parentNode.add(node);
            }
        }
    }

    /**
     * Expands all nodes in the tree.
     */
    private void expandAllNodes(JTree tree, int startingIndex, int rowCount) {
        for (int i = startingIndex; i < rowCount; ++i) {
            tree.expandRow(i);
        }
        if (tree.getRowCount() != rowCount) {
            expandAllNodes(tree, rowCount, tree.getRowCount());
        }
    }

    /**
     * Creates the right panel with card layout for preference panes.
     */
    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 10));

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        // Create preference panels
        createPreferencePanels();

        panel.add(cardPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Creates all preference panels and adds them to the card layout.
     */
    private void createPreferencePanels() {
        // Theme panel
        ThemePanel themePanel = new ThemePanel();
        preferencePanels.put("Theme", themePanel);
        cardPanel.add(themePanel, "Theme");

        // Editor panel
        EditorPanel editorPanel = new EditorPanel();
        preferencePanels.put("Editor", editorPanel);
        cardPanel.add(editorPanel, "Editor");

        // File panel
        FilePanel filePanel = new FilePanel();
        preferencePanels.put("File", filePanel);
        cardPanel.add(filePanel, "File");

        // KalixCLI panel
        KalixCliPanel kalixCliPanel = new KalixCliPanel();
        preferencePanels.put("Kalixcli", kalixCliPanel);
        cardPanel.add(kalixCliPanel, "Kalixcli");

        // Compression panel
        CompressionPanel compressionPanel = new CompressionPanel();
        preferencePanels.put("Compression", compressionPanel);
        cardPanel.add(compressionPanel, "Compression");

        // System panel
        SystemPanel systemPanel = new SystemPanel();
        preferencePanels.put("System", systemPanel);
        cardPanel.add(systemPanel, "System");
    }

    /**
     * Shows the specified preference panel.
     */
    private void showPreferencePanel(String categoryName) {
        cardLayout.show(cardPanel, categoryName);
    }

    /**
     * Selects the default category (first leaf node - Theme).
     */
    private void selectDefaultCategory() {
        TreeNode root = (TreeNode) categoryTree.getModel().getRoot();
        TreeNode appearance = root.getChildAt(0); // Appearance
        TreeNode theme = appearance.getChildAt(0); // Theme

        TreePath themePath = new TreePath(new Object[]{root, appearance, theme});
        categoryTree.setSelectionPath(themePath);
    }
    
    /**
     * Creates the button panel with Apply, OK, Cancel buttons (standard order).
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        JButton applyButton = new JButton("Apply");
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        // Set preferred button size for consistency
        Dimension buttonSize = new Dimension(80, 28);
        applyButton.setPreferredSize(buttonSize);
        okButton.setPreferredSize(buttonSize);
        cancelButton.setPreferredSize(buttonSize);

        applyButton.addActionListener(e -> applySettings());

        okButton.addActionListener(e -> {
            if (applySettings()) {
                settingsChanged = true;
                dispose();
            }
        });

        cancelButton.addActionListener(e -> {
            settingsChanged = false;
            dispose();
        });

        // Standard order: Apply, OK, Cancel
        buttonPanel.add(applyButton);
        buttonPanel.add(Box.createHorizontalStrut(5));
        buttonPanel.add(okButton);
        buttonPanel.add(Box.createHorizontalStrut(5));
        buttonPanel.add(cancelButton);

        // Set OK as default button
        getRootPane().setDefaultButton(okButton);

        return buttonPanel;
    }
    
    /**
     * Loads current settings into all panels.
     */
    private void loadSettings() {
        for (PreferencePanel panel : preferencePanels.values()) {
            panel.loadSettings();
        }
    }
    
    /**
     * Applies settings from all panels.
     *
     * @return true if settings were applied successfully
     */
    private boolean applySettings() {
        try {
            boolean anyChanged = false;

            for (PreferencePanel panel : preferencePanels.values()) {
                if (panel.applySettings()) {
                    anyChanged = true;
                }
            }

            if (anyChanged) {
                settingsChanged = true;
            }

            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Error applying settings: " + e.getMessage(),
                "Settings Error",
                JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
    
    /**
     * Shows the settings dialog and returns whether settings were changed.
     */
    public boolean showDialog() {
        setVisible(true);
        return settingsChanged;
    }
    
    /**
     * Base class for preference panels.
     */
    private abstract class PreferencePanel extends JPanel {
        protected static final Insets FIELD_INSETS = new Insets(5, 5, 5, 5);
        protected static final Insets SECTION_INSETS = new Insets(10, 5, 5, 5);

        public abstract void loadSettings();
        public abstract boolean applySettings();

        /**
         * Creates a titled section in the preference panel.
         */
        protected JPanel createSection(String title, Component... components) {
            JPanel section = new JPanel(new GridBagLayout());
            section.setBorder(BorderFactory.createTitledBorder(title));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = FIELD_INSETS;

            for (int i = 0; i < components.length; i++) {
                gbc.gridx = 0;
                gbc.gridy = i;
                if (i == components.length - 1) {
                    gbc.weighty = 1.0; // Last component gets remaining space
                }
                section.add(components[i], gbc);
            }

            return section;
        }

        /**
         * Creates a horizontal field panel with label and component.
         */
        protected JPanel createFieldPanel(String labelText, Component component) {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 0;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(0, 0, 0, 10);
            panel.add(new JLabel(labelText), gbc);

            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            gbc.insets = new Insets(0, 0, 0, 0);
            panel.add(component, gbc);

            return panel;
        }
    }
    
    /**
     * Panel for theme settings.
     */
    private class ThemePanel extends PreferencePanel {
        private JComboBox<String> themeComboBox;
        private JComboBox<String> nodeThemeComboBox;
        
        public ThemePanel() {
            initializeThemePanel();
        }
        
        private void initializeThemePanel() {
            setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = SECTION_INSETS;

            // Application theme section
            themeComboBox = new JComboBox<>(AppConstants.AVAILABLE_THEMES);
            JPanel appThemePanel = createSection("Application Theme",
                createFieldPanel("Theme:", themeComboBox),
                new JLabel("<html><small>Changes the overall appearance of the application.</small></html>")
            );

            gbc.gridx = 0; gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            mainPanel.add(appThemePanel, gbc);

            // Node theme section
            com.kalix.gui.themes.NodeTheme.Theme[] nodeThemes = com.kalix.gui.themes.NodeTheme.getAllThemes();
            String[] nodeThemeNames = new String[nodeThemes.length];
            for (int i = 0; i < nodeThemes.length; i++) {
                nodeThemeNames[i] = nodeThemes[i].getDisplayName();
            }
            nodeThemeComboBox = new JComboBox<>(nodeThemeNames);
            JPanel nodeThemePanel = createSection("Map Node Theme",
                createFieldPanel("Node Theme:", nodeThemeComboBox),
                new JLabel("<html><small>Changes the color scheme of nodes displayed on the map.</small></html>")
            );

            gbc.gridy = 1;
            mainPanel.add(nodeThemePanel, gbc);

            // Spacer
            gbc.gridy = 2;
            gbc.weighty = 1.0;
            mainPanel.add(new JPanel(), gbc);

            add(mainPanel, BorderLayout.CENTER);
        }
        
        @Override
        public void loadSettings() {
            String currentTheme = themeManager.getCurrentTheme();
            themeComboBox.setSelectedItem(currentTheme);

            String nodeTheme = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
            nodeThemeComboBox.setSelectedItem(nodeTheme);
        }
        
        @Override
        public boolean applySettings() {
            boolean changed = false;

            // Apply application theme
            String selectedTheme = (String) themeComboBox.getSelectedItem();
            if (!selectedTheme.equals(themeManager.getCurrentTheme())) {
                themeManager.switchTheme(selectedTheme);
                changed = true;
            }

            // Apply node theme
            String selectedNodeTheme = (String) nodeThemeComboBox.getSelectedItem();
            String currentNodeTheme = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
            if (!selectedNodeTheme.equals(currentNodeTheme)) {
                // Apply the theme immediately by calling the parent's setNodeTheme method
                if (parent instanceof com.kalix.gui.KalixGUI) {
                    com.kalix.gui.themes.NodeTheme.Theme theme = com.kalix.gui.themes.NodeTheme.themeFromString(selectedNodeTheme);
                    ((com.kalix.gui.KalixGUI) parent).setNodeTheme(theme);
                } else {
                    // Fallback: just save the preference (will be applied on restart)
                    PreferenceManager.setFileString(PreferenceKeys.UI_NODE_THEME, selectedNodeTheme);
                }
                changed = true;
            }

            return changed;
        }
    }
    
    
    /**
     * Panel for KalixCLI settings.
     */
    private class KalixCliPanel extends PreferencePanel {
        private JTextField binaryPathField;
        private JButton browseButton;
        private JButton testButton;
        private JLabel statusLabel;
        private JTextArea infoArea;
        
        
        public KalixCliPanel() {
            initializeCliPanel();
        }
        
        private void initializeCliPanel() {
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            
            // Binary path selection
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("KalixCLI Binary Path:"), gbc);
            
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            binaryPathField = new JTextField();
            binaryPathField.setToolTipText("Leave empty to use kalixcli from system PATH");
            add(binaryPathField, gbc);
            
            // Browse button
            gbc.gridx = 2; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            browseButton = new JButton("Browse...");
            browseButton.addActionListener(this::browseBinary);
            add(browseButton, gbc);
            
            // Test button and status
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
            testButton = new JButton("Test Connection");
            testButton.addActionListener(this::testConnection);
            add(testButton, gbc);
            
            gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            statusLabel = new JLabel("Status: Not tested");
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC));
            add(statusLabel, gbc);
            
            // Info area
            gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            
            infoArea = new JTextArea();
            infoArea.setEditable(false);
            infoArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            infoArea.setBackground(getBackground());
            infoArea.setText("Configure the path to the kalixcli binary.\n\n" +
                "If left empty, the system will search for 'kalixcli' in:\n" +
                "• System PATH\n" +
                "• Common installation directories\n" +
                "• Relative to the GUI application\n\n" +
                "You can specify a full path to the binary if it's installed\n" +
                "in a non-standard location.");
            
            JScrollPane scrollPane = new JScrollPane(infoArea);
            scrollPane.setPreferredSize(new Dimension(400, 150));
            scrollPane.setBorder(BorderFactory.createTitledBorder("Information"));
            add(scrollPane, gbc);
        }
        
        private void browseBinary(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select KalixCLI Binary");
            fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            
            // Set current path if exists
            String currentPath = binaryPathField.getText().trim();
            if (!currentPath.isEmpty()) {
                fileChooser.setSelectedFile(new java.io.File(currentPath));
            }
            
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                binaryPathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
                statusLabel.setText("Status: Path changed - click Test Connection");
                statusLabel.setForeground(Color.BLUE);
            }
        }
        
        private void testConnection(ActionEvent e) {
            String path = binaryPathField.getText().trim();
            testButton.setEnabled(false);
            statusLabel.setText("Status: Testing...");
            statusLabel.setForeground(Color.BLUE);
            
            // Test in background thread
            SwingUtilities.invokeLater(() -> {
                try {
                    if (path.isEmpty()) {
                        // Test auto-discovery
                        java.util.Optional<com.kalix.gui.cli.KalixCliLocator.CliLocation> location = 
                            com.kalix.gui.cli.KalixCliLocator.findKalixCli();
                        
                        if (location.isPresent()) {
                            statusLabel.setText("Status: ✓ Found kalixcli - " + location.get().getVersion());
                            statusLabel.setForeground(new Color(0, 128, 0));
                        } else {
                            statusLabel.setText("Status: ✗ kalixcli not found in system");
                            statusLabel.setForeground(Color.RED);
                        }
                    } else {
                        // Test specific path
                        java.nio.file.Path binaryPath = java.nio.file.Paths.get(path);
                        if (com.kalix.gui.cli.KalixCliLocator.validateKalixCli(binaryPath)) {
                            statusLabel.setText("Status: ✓ Valid kalixcli binary");
                            statusLabel.setForeground(new Color(0, 128, 0));
                        } else {
                            statusLabel.setText("Status: ✗ Invalid or inaccessible binary");
                            statusLabel.setForeground(Color.RED);
                        }
                    }
                } catch (Exception ex) {
                    statusLabel.setText("Status: ✗ Test failed - " + ex.getMessage());
                    statusLabel.setForeground(Color.RED);
                } finally {
                    testButton.setEnabled(true);
                }
            });
        }
        
        @Override
        public void loadSettings() {
            String savedPath = PreferenceManager.getFileString(PreferenceKeys.CLI_BINARY_PATH, "");
            binaryPathField.setText(savedPath);
            statusLabel.setText("Status: Not tested");
            statusLabel.setForeground(Color.GRAY);
        }
        
        @Override
        public boolean applySettings() {
            String newPath = binaryPathField.getText().trim();
            String currentPath = PreferenceManager.getFileString(PreferenceKeys.CLI_BINARY_PATH, "");

            if (!newPath.equals(currentPath)) {
                PreferenceManager.setFileString(PreferenceKeys.CLI_BINARY_PATH, newPath);
                return true;
            }

            return false;
        }
        
        /**
         * Gets the configured CLI path.
         */
        public String getConfiguredCliPath() {
            return PreferenceManager.getFileString(PreferenceKeys.CLI_BINARY_PATH, "");
        }
    }
    
    /**
     * Panel for compression settings.
     */
    private class CompressionPanel extends PreferencePanel {
        private JCheckBox precision64CheckBox;

        public CompressionPanel() {
            initializePanel();
        }

        private void initializePanel() {
            setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = SECTION_INSETS;

            // Compression settings section
            precision64CheckBox = new JCheckBox("Use 64-bit precision for FlowViz data export");
            JPanel compressionPanel = createSection("Data Export",
                precision64CheckBox,
                new JLabel("<html><small>Use 64-bit precision for exported data. May increase file size but improves accuracy.</small></html>")
            );

            gbc.gridx = 0; gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            mainPanel.add(compressionPanel, gbc);

            // Spacer
            gbc.gridy = 1;
            gbc.weighty = 1.0;
            mainPanel.add(new JPanel(), gbc);

            add(mainPanel, BorderLayout.CENTER);
        }

        @Override
        public void loadSettings() {
            precision64CheckBox.setSelected(
                PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, true));
        }

        @Override
        public boolean applySettings() {
            boolean changed = false;

            // Apply precision setting
            boolean newPrecision = precision64CheckBox.isSelected();
            boolean currentPrecision = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, true);
            if (newPrecision != currentPrecision) {
                PreferenceManager.setFileBoolean(PreferenceKeys.FLOWVIZ_PRECISION64, newPrecision);
                changed = true;
            }

            return changed;
        }
    }

    /**
     * Panel for system settings.
     */
    private class SystemPanel extends PreferencePanel {
        private JButton openPrefsFileButton;
        private JLabel prefsFileLocationLabel;

        public SystemPanel() {
            initializePanel();
        }

        private void initializePanel() {
            setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = SECTION_INSETS;

            // Preferences file section
            prefsFileLocationLabel = new JLabel();
            openPrefsFileButton = new JButton("Open Preferences File Location");
            openPrefsFileButton.addActionListener(e -> openPreferencesFileLocation());

            JPanel prefsPanel = createSection("Preferences File",
                new JLabel("Preferences file location:"),
                prefsFileLocationLabel,
                openPrefsFileButton,
                new JLabel("<html><small>The kalix_prefs.json file contains your portable preferences.</small></html>")
            );

            gbc.gridx = 0; gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            mainPanel.add(prefsPanel, gbc);

            // Spacer
            gbc.gridy = 1;
            gbc.weighty = 1.0;
            mainPanel.add(new JPanel(), gbc);

            add(mainPanel, BorderLayout.CENTER);
        }

        private void openPreferencesFileLocation() {
            try {
                String prefsPath = PreferenceManager.getPreferenceFilePath();
                java.io.File prefsFile = new java.io.File(prefsPath);

                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(prefsFile.getParentFile());
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Cannot open file location. Path: " + prefsFile.getParentFile().getAbsolutePath(),
                        "Information",
                        JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                    "Failed to open preferences file location: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }

        @Override
        public void loadSettings() {
            String prefsPath = PreferenceManager.getPreferenceFilePath();
            prefsFileLocationLabel.setText("<html><small>" + prefsPath + "</small></html>");
        }

        @Override
        public boolean applySettings() {
            // System panel doesn't have settings to apply
            return false;
        }
    }

    /**
     * Panel for editor settings.
     */
    private class EditorPanel extends PreferencePanel {
        private JCheckBox fileAutoReloadCheckBox;
        private JSpinner fontSizeSpinner;

        public EditorPanel() {
            initializePanel();
        }

        private void initializePanel() {
            setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = SECTION_INSETS;

            // File behavior section
            fileAutoReloadCheckBox = new JCheckBox("Auto-reload clean files when changed externally");
            JPanel fileBehaviorPanel = createSection("File Behavior",
                fileAutoReloadCheckBox,
                new JLabel("<html><small>Automatically reload files that have no unsaved changes when they are modified externally.</small></html>")
            );

            gbc.gridx = 0; gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            mainPanel.add(fileBehaviorPanel, gbc);

            // Editor appearance section
            fontSizeSpinner = new JSpinner(new SpinnerNumberModel(12, 8, 32, 1));
            JPanel appearancePanel = createSection("Appearance",
                createFieldPanel("Font Size:", fontSizeSpinner),
                new JLabel("<html><small>Size of the text in the code editor.</small></html>")
            );

            gbc.gridy = 1;
            mainPanel.add(appearancePanel, gbc);

            // Spacer
            gbc.gridy = 2;
            gbc.weighty = 1.0;
            mainPanel.add(new JPanel(), gbc);

            add(mainPanel, BorderLayout.CENTER);
        }

        @Override
        public void loadSettings() {
            fileAutoReloadCheckBox.setSelected(
                PreferenceManager.getFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, false));

            // Load font size from preferences or current editor font size
            int currentFontSize = textEditor.getFontSize();
            int savedFontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, currentFontSize);
            fontSizeSpinner.setValue(savedFontSize);
        }

        @Override
        public boolean applySettings() {
            boolean changed = false;

            // Apply auto-reload setting
            boolean newAutoReload = fileAutoReloadCheckBox.isSelected();
            boolean currentAutoReload = PreferenceManager.getFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, false);
            if (newAutoReload != currentAutoReload) {
                // Apply the setting immediately
                if (parent instanceof com.kalix.gui.KalixGUI) {
                    ((com.kalix.gui.KalixGUI) parent).setAutoReloadEnabled(newAutoReload);
                } else {
                    // Fallback: just save the preference (will be applied on restart)
                    PreferenceManager.setFileBoolean(PreferenceKeys.FILE_AUTO_RELOAD, newAutoReload);
                }
                changed = true;
            }

            // Apply font size
            int newFontSize = (Integer) fontSizeSpinner.getValue();
            int currentFontSize = textEditor.getFontSize();
            if (newFontSize != currentFontSize) {
                textEditor.setFontSize(newFontSize);
                PreferenceManager.setFileInt(PreferenceKeys.EDITOR_FONT_SIZE, newFontSize);
                changed = true;
            }

            return changed;
        }
    }

    /**
     * Panel for file settings.
     */
    private class FilePanel extends PreferencePanel {
        private JTextField lastDirectoryField;
        private JButton browseDirectoryButton;
        private JButton clearRecentButton;
        private JLabel recentFilesCountLabel;

        public FilePanel() {
            initializePanel();
        }

        private void initializePanel() {
            setLayout(new BorderLayout());

            JPanel mainPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.anchor = GridBagConstraints.NORTHWEST;
            gbc.insets = SECTION_INSETS;

            // Default directory section
            lastDirectoryField = new JTextField();
            lastDirectoryField.setEditable(false);
            browseDirectoryButton = new JButton("Browse...");
            browseDirectoryButton.addActionListener(e -> browseForDirectory());

            JPanel directoryPanel = new JPanel(new GridBagLayout());
            GridBagConstraints dgbc = new GridBagConstraints();
            dgbc.fill = GridBagConstraints.HORIZONTAL;
            dgbc.weightx = 1.0;
            directoryPanel.add(lastDirectoryField, dgbc);
            dgbc.fill = GridBagConstraints.NONE;
            dgbc.weightx = 0.0;
            dgbc.insets = new Insets(0, 5, 0, 0);
            directoryPanel.add(browseDirectoryButton, dgbc);

            JPanel defaultDirPanel = createSection("Default Directory",
                new JLabel("Last used directory:"),
                directoryPanel,
                new JLabel("<html><small>The directory that will be opened by default in file dialogs.</small></html>")
            );

            gbc.gridx = 0; gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            mainPanel.add(defaultDirPanel, gbc);

            // Recent files section
            recentFilesCountLabel = new JLabel();
            clearRecentButton = new JButton("Clear Recent Files");
            clearRecentButton.addActionListener(e -> clearRecentFiles());

            JPanel recentPanel = createSection("Recent Files",
                recentFilesCountLabel,
                clearRecentButton,
                new JLabel("<html><small>Manage the list of recently opened files.</small></html>")
            );

            gbc.gridy = 1;
            mainPanel.add(recentPanel, gbc);

            // Spacer
            gbc.gridy = 2;
            gbc.weighty = 1.0;
            mainPanel.add(new JPanel(), gbc);

            add(mainPanel, BorderLayout.CENTER);
        }

        private void browseForDirectory() {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

            String currentDir = lastDirectoryField.getText();
            if (!currentDir.isEmpty()) {
                chooser.setCurrentDirectory(new java.io.File(currentDir));
            }

            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                lastDirectoryField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        }

        private void clearRecentFiles() {
            int result = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to clear all recent files?",
                "Clear Recent Files",
                JOptionPane.YES_NO_OPTION);

            if (result == JOptionPane.YES_OPTION) {
                // Clear recent files from KalixGUI preferences node (same system used by RecentFilesManager)
                java.util.prefs.Preferences kalixGuiPrefs = java.util.prefs.Preferences.userNodeForPackage(com.kalix.gui.KalixGUI.class);
                for (int i = 0; i < PreferenceKeys.MAX_RECENT_FILES; i++) {
                    kalixGuiPrefs.remove(AppConstants.RECENT_FILE_PREF_PREFIX + i);
                }
                updateRecentFilesCount();
            }
        }

        private void updateRecentFilesCount() {
            // Count recent files from KalixGUI preferences node (same system used by RecentFilesManager)
            java.util.prefs.Preferences kalixGuiPrefs = java.util.prefs.Preferences.userNodeForPackage(com.kalix.gui.KalixGUI.class);
            int count = 0;
            for (int i = 0; i < PreferenceKeys.MAX_RECENT_FILES; i++) {
                String filePath = kalixGuiPrefs.get(AppConstants.RECENT_FILE_PREF_PREFIX + i, null);
                if (filePath != null && !filePath.isEmpty()) {
                    count++;
                }
            }
            recentFilesCountLabel.setText("Recent files count: " + count);
        }

        @Override
        public void loadSettings() {
            String lastDir = PreferenceManager.getFileString(PreferenceKeys.DATA_LAST_DIRECTORY, "./");
            lastDirectoryField.setText(lastDir);
            updateRecentFilesCount();
        }

        @Override
        public boolean applySettings() {
            boolean changed = false;

            // Apply last directory
            String newLastDir = lastDirectoryField.getText();
            String currentLastDir = PreferenceManager.getFileString(PreferenceKeys.DATA_LAST_DIRECTORY, "./");
            if (!newLastDir.equals(currentLastDir)) {
                PreferenceManager.setFileString(PreferenceKeys.DATA_LAST_DIRECTORY, newLastDir);
                changed = true;
            }

            return changed;
        }
    }

    /**
     * Gets the configured CLI binary path.
     */
    public String getConfiguredCliPath() {
        KalixCliPanel cliPanel = (KalixCliPanel) preferencePanels.get("Kalixcli");
        return cliPanel != null ? cliPanel.getConfiguredCliPath() : "";
    }
}