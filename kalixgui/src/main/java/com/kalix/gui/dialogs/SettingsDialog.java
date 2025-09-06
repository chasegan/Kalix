package com.kalix.gui.dialogs;

import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.editor.EnhancedTextEditor;
import com.kalix.gui.managers.FontDialogManager;
import com.kalix.gui.managers.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.prefs.Preferences;

/**
 * Settings dialog with tabbed interface for application configuration.
 * Provides centralized access to appearance settings and CLI configuration.
 */
public class SettingsDialog extends JDialog {
    
    private final JFrame parent;
    private final Preferences prefs;
    private final ThemeManager themeManager;
    private final FontDialogManager fontDialogManager;
    private final EnhancedTextEditor textEditor;
    
    // Settings panels
    private ThemePanel themePanel;
    private EditorPanel editorPanel;
    private KalixCliPanel kalixCliPanel;
    
    // Dialog result
    private boolean settingsChanged = false;
    
    /**
     * Creates a new SettingsDialog.
     */
    public SettingsDialog(JFrame parent, ThemeManager themeManager, FontDialogManager fontDialogManager, EnhancedTextEditor textEditor) {
        super(parent, "Settings", true);
        this.parent = parent;
        this.prefs = Preferences.userNodeForPackage(SettingsDialog.class);
        this.themeManager = themeManager;
        this.fontDialogManager = fontDialogManager;
        this.textEditor = textEditor;
        
        initializeDialog();
    }
    
    /**
     * Initializes the dialog layout and components.
     */
    private void initializeDialog() {
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        
        // Create tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // Create and add tabs
        themePanel = new ThemePanel();
        editorPanel = new EditorPanel();
        kalixCliPanel = new KalixCliPanel();
        
        tabbedPane.addTab("Theme", createTabIcon("theme"), themePanel, "Configure visual theme");
        tabbedPane.addTab("Editor", createTabIcon("editor"), editorPanel, "Configure editor settings");
        tabbedPane.addTab("KalixCLI", createTabIcon("cli"), kalixCliPanel, "Configure Kalix CLI settings");
        
        add(tabbedPane, BorderLayout.CENTER);
        
        // Create button panel
        add(createButtonPanel(), BorderLayout.SOUTH);
        
        // Set dialog properties
        setSize(500, 400);
        setLocationRelativeTo(parent);
        setResizable(false);
        
        // Load current settings
        loadSettings();
    }
    
    /**
     * Creates a simple icon for tabs (placeholder implementation).
     */
    private Icon createTabIcon(String type) {
        // Simple colored square icons
        Color color;
        switch (type) {
            case "theme": color = new Color(138, 43, 226); break; // Purple
            case "editor": color = new Color(100, 149, 237); break; // Blue
            case "cli": color = new Color(34, 139, 34); break; // Green
            default: color = Color.GRAY;
        }
        
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(color);
                g.fillRect(x, y, getIconWidth(), getIconHeight());
                g.setColor(Color.DARK_GRAY);
                g.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
            }
            
            @Override
            public int getIconWidth() { return 16; }
            
            @Override
            public int getIconHeight() { return 16; }
        };
    }
    
    /**
     * Creates the button panel with OK, Cancel, Apply buttons.
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        JButton applyButton = new JButton("Apply");
        
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
        
        applyButton.addActionListener(e -> applySettings());
        
        buttonPanel.add(applyButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);
        
        // Set OK as default button
        getRootPane().setDefaultButton(okButton);
        
        return buttonPanel;
    }
    
    /**
     * Loads current settings into the panels.
     */
    private void loadSettings() {
        themePanel.loadSettings();
        editorPanel.loadSettings();
        kalixCliPanel.loadSettings();
    }
    
    /**
     * Applies settings from all panels.
     * 
     * @return true if settings were applied successfully
     */
    private boolean applySettings() {
        try {
            boolean themeChanged = themePanel.applySettings();
            boolean editorChanged = editorPanel.applySettings();
            boolean cliChanged = kalixCliPanel.applySettings();
            
            if (themeChanged || editorChanged || cliChanged) {
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
     * Base class for settings panels.
     */
    private abstract class SettingsPanel extends JPanel {
        public abstract void loadSettings();
        public abstract boolean applySettings();
    }
    
    /**
     * Panel for theme settings.
     */
    private class ThemePanel extends SettingsPanel {
        private JComboBox<String> themeComboBox;
        
        public ThemePanel() {
            initializeThemePanel();
        }
        
        private void initializeThemePanel() {
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(10, 10, 10, 10);
            gbc.anchor = GridBagConstraints.WEST;
            
            // Theme selection
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("Theme:"), gbc);
            
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            themeComboBox = new JComboBox<>(AppConstants.AVAILABLE_THEMES);
            add(themeComboBox, gbc);
            
            // Add some vertical space at the bottom
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weighty = 1.0;
            add(new JPanel(), gbc);
        }
        
        @Override
        public void loadSettings() {
            // Load theme
            String currentTheme = themeManager.getCurrentTheme();
            themeComboBox.setSelectedItem(currentTheme);
        }
        
        @Override
        public boolean applySettings() {
            boolean changed = false;
            
            // Apply theme
            String selectedTheme = (String) themeComboBox.getSelectedItem();
            if (!selectedTheme.equals(themeManager.getCurrentTheme())) {
                themeManager.switchTheme(selectedTheme);
                changed = true;
            }
            
            return changed;
        }
    }
    
    /**
     * Panel for editor settings (font, line wrap).
     */
    private class EditorPanel extends SettingsPanel {
        private JComboBox<String> fontNameComboBox;
        private JComboBox<Integer> fontSizeComboBox;
        private JComboBox<String> editorThemeComboBox;
        private JCheckBox lineWrapCheckBox;
        private JLabel previewLabel;
        
        public EditorPanel() {
            initializeEditorPanel();
        }
        
        private void initializeEditorPanel() {
            setLayout(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;
            
            // Font name selection
            gbc.gridx = 0; gbc.gridy = 0;
            add(new JLabel("Font:"), gbc);
            
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            fontNameComboBox = new JComboBox<>(AppConstants.MONOSPACE_FONTS);
            fontNameComboBox.addActionListener(e -> updatePreview());
            add(fontNameComboBox, gbc);
            
            // Font size selection
            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Font Size:"), gbc);
            
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            fontSizeComboBox = new JComboBox<>(AppConstants.FONT_SIZES);
            fontSizeComboBox.addActionListener(e -> updatePreview());
            add(fontSizeComboBox, gbc);
            
            // Editor theme selection
            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Editor Theme:"), gbc);
            
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            editorThemeComboBox = new JComboBox<>(com.kalix.gui.editor.EditorTheme.getAvailableThemes());
            editorThemeComboBox.addActionListener(e -> updatePreview());
            add(editorThemeComboBox, gbc);
            
            // Line wrap checkbox with aligned layout
            gbc.gridx = 0; gbc.gridy = 3; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
            add(new JLabel("Line Wrap:"), gbc);
            
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            lineWrapCheckBox = new JCheckBox();
            add(lineWrapCheckBox, gbc);
            
            // Preview area
            gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH; 
            gbc.weightx = 1.0; gbc.weighty = 1.0;
            
            JPanel previewPanel = createPreviewPanel();
            add(previewPanel, gbc);
        }
        
        private JPanel createPreviewPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder("Preview"));
            panel.setPreferredSize(new Dimension(400, 120));
            
            previewLabel = new JLabel();
            previewLabel.setText(AppConstants.FONT_PREVIEW_TEXT);
            previewLabel.setVerticalAlignment(SwingConstants.TOP);
            previewLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            JScrollPane scrollPane = new JScrollPane(previewLabel);
            scrollPane.setPreferredSize(new Dimension(380, 100));
            
            panel.add(scrollPane, BorderLayout.CENTER);
            return panel;
        }
        
        private void updatePreview() {
            String fontName = (String) fontNameComboBox.getSelectedItem();
            Integer fontSize = (Integer) fontSizeComboBox.getSelectedItem();
            
            if (fontName != null && fontSize != null) {
                Font font = new Font(fontName, Font.PLAIN, fontSize);
                previewLabel.setFont(font);
            }
        }
        
        @Override
        public void loadSettings() {
            // Load font settings
            String fontName = prefs.get(AppConstants.PREF_FONT_NAME, AppConstants.DEFAULT_FONT_NAME);
            int fontSize = prefs.getInt(AppConstants.PREF_FONT_SIZE, AppConstants.DEFAULT_FONT_SIZE);
            
            fontNameComboBox.setSelectedItem(fontName);
            fontSizeComboBox.setSelectedItem(fontSize);
            
            // Load editor theme setting
            String editorTheme = prefs.get(AppConstants.PREF_EDITOR_THEME, "GitHub Light Colorblind");
            editorThemeComboBox.setSelectedItem(editorTheme);
            
            // Load line wrap setting from preferences
            boolean savedLineWrap = prefs.getBoolean(AppConstants.PREF_LINE_WRAP, true);
            lineWrapCheckBox.setSelected(savedLineWrap);
            textEditor.setLineWrap(savedLineWrap);
            
            updatePreview();
        }
        
        @Override
        public boolean applySettings() {
            boolean changed = false;
            
            // Apply font settings
            String selectedFont = (String) fontNameComboBox.getSelectedItem();
            Integer selectedSize = (Integer) fontSizeComboBox.getSelectedItem();
            
            String currentFont = prefs.get(AppConstants.PREF_FONT_NAME, AppConstants.DEFAULT_FONT_NAME);
            int currentSize = prefs.getInt(AppConstants.PREF_FONT_SIZE, AppConstants.DEFAULT_FONT_SIZE);
            
            if (!selectedFont.equals(currentFont) || !selectedSize.equals(currentSize)) {
                prefs.put(AppConstants.PREF_FONT_NAME, selectedFont);
                prefs.putInt(AppConstants.PREF_FONT_SIZE, selectedSize);
                
                // Apply font changes through FontDialogManager
                fontDialogManager.applyFont(selectedFont, selectedSize);
                changed = true;
            }
            
            // Apply editor theme setting
            String selectedTheme = (String) editorThemeComboBox.getSelectedItem();
            String currentTheme = prefs.get(AppConstants.PREF_EDITOR_THEME, "GitHub Light Colorblind");
            
            if (!selectedTheme.equals(currentTheme)) {
                prefs.put(AppConstants.PREF_EDITOR_THEME, selectedTheme);
                textEditor.setEditorTheme(selectedTheme);
                changed = true;
            }
            
            // Apply line wrap setting
            boolean selectedLineWrap = lineWrapCheckBox.isSelected();
            boolean currentLineWrap = prefs.getBoolean(AppConstants.PREF_LINE_WRAP, true);
            
            if (selectedLineWrap != currentLineWrap) {
                prefs.putBoolean(AppConstants.PREF_LINE_WRAP, selectedLineWrap);
                textEditor.setLineWrap(selectedLineWrap);
                changed = true;
            }
            
            return changed;
        }
    }
    
    /**
     * Panel for KalixCLI settings.
     */
    private class KalixCliPanel extends SettingsPanel {
        private JTextField binaryPathField;
        private JButton browseButton;
        private JButton testButton;
        private JLabel statusLabel;
        private JTextArea infoArea;
        
        // Preferences key for CLI path
        private static final String PREF_CLI_PATH = "kalixcli.binary.path";
        
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
            String savedPath = prefs.get(PREF_CLI_PATH, "");
            binaryPathField.setText(savedPath);
            statusLabel.setText("Status: Not tested");
            statusLabel.setForeground(Color.GRAY);
        }
        
        @Override
        public boolean applySettings() {
            String newPath = binaryPathField.getText().trim();
            String currentPath = prefs.get(PREF_CLI_PATH, "");
            
            if (!newPath.equals(currentPath)) {
                prefs.put(PREF_CLI_PATH, newPath);
                return true;
            }
            
            return false;
        }
        
        /**
         * Gets the configured CLI path.
         */
        public String getConfiguredCliPath() {
            return prefs.get(PREF_CLI_PATH, "");
        }
    }
    
    /**
     * Gets the configured CLI binary path.
     */
    public String getConfiguredCliPath() {
        return kalixCliPanel.getConfiguredCliPath();
    }
}