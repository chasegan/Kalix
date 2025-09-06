package com.kalix.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;

import com.kalix.gui.editor.EnhancedTextEditor;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.ArrayList;

public class KalixGUI extends JFrame {
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;
    private JLabel statusLabel;
    private Preferences prefs;
    private String currentTheme;
    private List<String> recentFiles;
    private JMenu recentFilesMenu;
    private static final int MAX_RECENT_FILES = 5;

    public KalixGUI() {
        // Initialize preferences
        prefs = Preferences.userNodeForPackage(KalixGUI.class);
        currentTheme = prefs.get("theme", "Light");
        
        // Initialize recent files
        recentFiles = new ArrayList<>();
        loadRecentFiles();
        
        setTitle("Kalix Hydrologic Modeling GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        initializeComponents();
        setupLayout();
        setupMenuBar();
        setupDragAndDrop();
        
        setVisible(true);
    }

    private void initializeComponents() {
        mapPanel = new MapPanel();
        textEditor = new EnhancedTextEditor();
        textEditor.setText("# Kalix Model\n# Edit your hydrologic model here...\n");
        
        // Load saved font preferences
        loadFontPreferences();
        
        // Set up dirty file indicator listener
        textEditor.setDirtyStateListener(isDirty -> {
            updateWindowTitle(isDirty);
        });
        
        // Set up file drop handler for text editor
        textEditor.setFileDropHandler(file -> {
            loadModelFile(file);
        });
        
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }
    
    private void updateWindowTitle(boolean isDirty) {
        String title = "Kalix Hydrologic Modeling GUI";
        if (isDirty) {
            title = "*" + title;
        }
        setTitle(title);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create split pane with map panel on left, text editor on right
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(mapPanel),
            textEditor  // Enhanced text editor already includes scroll pane
        );
        splitPane.setDividerLocation(600);
        splitPane.setResizeWeight(0.5);
        
        add(splitPane, BorderLayout.CENTER);
        
        // Add status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.add(createMenuItem("New", e -> newModel()));
        fileMenu.add(createMenuItem("Open", e -> openModel()));
        fileMenu.addSeparator();
        
        // Recent files submenu
        recentFilesMenu = new JMenu("Recent Files");
        updateRecentFilesMenu();
        fileMenu.add(recentFilesMenu);
        
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Save", e -> saveModel()));
        fileMenu.add(createMenuItem("Save As...", e -> saveAsModel()));
        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Exit", e -> exitApplication()));
        
        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        editMenu.add(createMenuItem("Undo", e -> undoAction()));
        editMenu.add(createMenuItem("Redo", e -> redoAction()));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Cut", e -> cutAction()));
        editMenu.add(createMenuItem("Copy", e -> copyAction()));
        editMenu.add(createMenuItem("Paste", e -> pasteAction()));
        
        // Editor menu
        JMenu editorMenu = new JMenu("Editor");
        editorMenu.add(createMenuItem("Font...", e -> showFontDialog()));
        editorMenu.addSeparator();
        
        // Line wrap checkbox
        JCheckBoxMenuItem lineWrapItem = new JCheckBoxMenuItem("Line Wrap");
        lineWrapItem.setSelected(textEditor.isLineWrap());
        lineWrapItem.addActionListener(e -> {
            textEditor.setLineWrap(lineWrapItem.isSelected());
        });
        editorMenu.add(lineWrapItem);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.add(createMenuItem("Zoom In", e -> zoomIn()));
        viewMenu.add(createMenuItem("Zoom Out", e -> zoomOut()));
        viewMenu.add(createMenuItem("Reset Zoom", e -> resetZoom()));
        viewMenu.addSeparator();
        viewMenu.add(createMenuItem("Show Splash Screen", e -> showSplashScreen()));
        viewMenu.addSeparator();
        
        // Theme submenu
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        
        // Define theme options
        String[] themes = {"Light", "Dark", "Dracula", "One Dark", "Carbon"};
        
        for (String theme : themes) {
            JRadioButtonMenuItem themeItem = new JRadioButtonMenuItem(theme, theme.equals(currentTheme));
            themeItem.addActionListener(e -> switchTheme(theme));
            themeGroup.add(themeItem);
            themeMenu.add(themeItem);
        }
        
        viewMenu.add(themeMenu);
        
        // Graph menu
        JMenu graphMenu = new JMenu("Graph");
        graphMenu.add(createMenuItem("FlowViz", e -> flowViz()));
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem("About", e -> showAbout()));
        
        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(editorMenu);
        menuBar.add(viewMenu);
        menuBar.add(graphMenu);
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
    }
    
    private void setupDragAndDrop() {
        // Enable drag and drop for the entire window
        new DropTarget(this, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (isValidFileDrop(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (isValidFileDrop(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                if (isValidFileDrop(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                // Nothing to do
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        
                        Transferable transferable = dtde.getTransferable();
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        
                        if (!files.isEmpty()) {
                            // Process the first valid model file
                            for (File file : files) {
                                if (isKalixModelFile(file)) {
                                    loadModelFile(file);
                                    dtde.dropComplete(true);
                                    return;
                                }
                            }
                            // No valid model files found
                            updateStatus("Dropped files do not contain valid Kalix model files (.ini or .toml)");
                            dtde.dropComplete(false);
                        } else {
                            dtde.dropComplete(false);
                        }
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    updateStatus("Error processing dropped file: " + e.getMessage());
                    dtde.dropComplete(false);
                }
            }
        });
    }
    
    private boolean isValidFileDrop(DropTargetDragEvent dtde) {
        return dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }
    
    private boolean isKalixModelFile(File file) {
        if (!file.isFile()) return false;
        
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".ini") || fileName.endsWith(".toml");
    }

    private JMenuItem createMenuItem(String text, ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }

    // Menu action methods (placeholder implementations)
    private void newModel() {
        textEditor.setText("# New Kalix Model\n");
        mapPanel.clearModel();
        updateStatus("New model created");
    }

    private void openModel() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Open Kalix Model");
        
        // Set file filters for supported model formats
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "Kalix Model Files (*.ini, *.toml)", "ini", "toml"));
        fileChooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "INI Files (*.ini)", "ini"));
        fileChooser.addChoosableFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
            "TOML Files (*.toml)", "toml"));
        
        int result = fileChooser.showOpenDialog(this);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            loadModelFile(selectedFile);
        }
    }
    
    private void loadModelFile(File file) {
        try {
            // Read the file content as plain text
            String content = Files.readString(file.toPath());
            
            // Set the content in the text editor (this also clears dirty state and undo history)
            textEditor.setText(content);
            
            // Get file extension to determine format
            String fileName = file.getName();
            String format = "unknown";
            if (fileName.toLowerCase().endsWith(".ini")) {
                format = "INI";
            } else if (fileName.toLowerCase().endsWith(".toml")) {
                format = "TOML";
            }
            
            // Update status with file info
            updateStatus(String.format("Opened %s model: %s (%s format)", 
                format, file.getName(), format));
                
            // Add to recent files
            addRecentFile(file.getAbsolutePath());
                
            // Clear the map panel when opening a new model
            mapPanel.clearModel();
            
        } catch (IOException e) {
            // Show error dialog if file reading fails
            JOptionPane.showMessageDialog(this,
                "Error opening file: " + e.getMessage(),
                "File Open Error",
                JOptionPane.ERROR_MESSAGE);
            updateStatus("Failed to open file: " + file.getName());
        }
    }

    private void saveModel() {
        updateStatus("Save model - Not yet implemented");
    }

    private void saveAsModel() {
        updateStatus("Save as - Not yet implemented");
    }

    private void exitApplication() {
        System.exit(0);
    }

    private void undoAction() {
        if (textEditor.canUndo()) {
            textEditor.undo();
            updateStatus("Undo");
        } else {
            updateStatus("Nothing to undo");
        }
    }

    private void redoAction() {
        if (textEditor.canRedo()) {
            textEditor.redo();
            updateStatus("Redo");
        } else {
            updateStatus("Nothing to redo");
        }
    }

    private void cutAction() {
        textEditor.cut();
        updateStatus("Cut");
    }

    private void copyAction() {
        textEditor.copy();
        updateStatus("Copy");
    }

    private void pasteAction() {
        textEditor.paste();
        updateStatus("Paste");
    }

    private void zoomIn() {
        mapPanel.zoomIn();
        updateStatus("Zoomed in");
    }

    private void zoomOut() {
        mapPanel.zoomOut();
        updateStatus("Zoomed out");
    }

    private void resetZoom() {
        mapPanel.resetZoom();
        updateStatus("Zoom reset");
    }

    private void flowViz() {
        com.kalix.gui.flowviz.FlowVizWindow.createNewWindow();
        updateStatus("FlowViz window opened");
    }

    private void showSplashScreen() {
        SplashScreen.showSplashScreen();
        updateStatus("Splash screen displayed");
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Kalix Hydrologic Modeling GUI\nVersion 1.0\n\nA Java Swing interface for Kalix hydrologic models.",
            "About Kalix GUI",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void showFontDialog() {
        // Current font from text editor
        Font currentFont = textEditor.getTextPane().getFont();
        
        // Available monospace fonts
        String[] monospaceFonts = {
            "JetBrains Mono",
            "Fira Code", 
            "Consolas",
            "Courier New",
            "Monaco",
            "Menlo",
            "DejaVu Sans Mono",
            "Liberation Mono",
            "Source Code Pro",
            "Ubuntu Mono"
        };
        
        // Font sizes
        Integer[] fontSizes = {8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 36, 48};
        
        // Create dialog
        JDialog fontDialog = new JDialog(this, "Font Settings", true);
        fontDialog.setSize(400, 300);
        fontDialog.setLocationRelativeTo(this);
        fontDialog.setLayout(new BorderLayout());
        
        // Create components
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        
        // Font family selection
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST;
        settingsPanel.add(new JLabel("Font:"), gbc);
        
        JComboBox<String> fontComboBox = new JComboBox<>(monospaceFonts);
        fontComboBox.setSelectedItem(currentFont.getFontName());
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(fontComboBox, gbc);
        
        // Font size selection
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE;
        settingsPanel.add(new JLabel("Size:"), gbc);
        
        JComboBox<Integer> sizeComboBox = new JComboBox<>(fontSizes);
        sizeComboBox.setSelectedItem(currentFont.getSize());
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL;
        settingsPanel.add(sizeComboBox, gbc);
        
        // Preview area
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0;
        JTextArea previewArea = new JTextArea("Sample text:\n[Section]\nname = value\n# Comment");
        previewArea.setFont(currentFont);
        previewArea.setEditable(false);
        previewArea.setBorder(BorderFactory.createTitledBorder("Preview"));
        settingsPanel.add(new JScrollPane(previewArea), gbc);
        
        // Update preview when selections change
        ActionListener updatePreview = e -> {
            String fontName = (String) fontComboBox.getSelectedItem();
            Integer fontSize = (Integer) sizeComboBox.getSelectedItem();
            Font newFont = new Font(fontName, Font.PLAIN, fontSize);
            previewArea.setFont(newFont);
        };
        fontComboBox.addActionListener(updatePreview);
        sizeComboBox.addActionListener(updatePreview);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        
        okButton.addActionListener(e -> {
            String fontName = (String) fontComboBox.getSelectedItem();
            Integer fontSize = (Integer) sizeComboBox.getSelectedItem();
            Font newFont = new Font(fontName, Font.PLAIN, fontSize);
            
            // Apply font to text editor and line numbers
            textEditor.setEditorFont(newFont);
            
            // Save preference
            prefs.put("editor.font.name", fontName);
            prefs.putInt("editor.font.size", fontSize);
            
            fontDialog.dispose();
        });
        
        cancelButton.addActionListener(e -> fontDialog.dispose());
        
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        
        fontDialog.add(settingsPanel, BorderLayout.CENTER);
        fontDialog.add(buttonPanel, BorderLayout.SOUTH);
        
        fontDialog.setVisible(true);
    }
    
    private void loadFontPreferences() {
        String fontName = prefs.get("editor.font.name", "Consolas");
        int fontSize = prefs.getInt("editor.font.size", 12);
        
        Font savedFont = new Font(fontName, Font.PLAIN, fontSize);
        textEditor.setEditorFont(savedFont);
    }

    private void updateStatus(String message) {
        statusLabel.setText(message);
    }
    
    private void switchTheme(String theme) {
        if (!this.currentTheme.equals(theme)) {
            this.currentTheme = theme;
            
            // Save preference
            prefs.put("theme", theme);
            
            // Apply the new theme with animation
            FlatAnimatedLafChange.showSnapshot();
            
            try {
                switch (theme) {
                    case "Light":
                        UIManager.setLookAndFeel(new FlatLightLaf());
                        break;
                    case "Dark":
                        UIManager.setLookAndFeel(new FlatDarkLaf());
                        break;
                    case "Dracula":
                        UIManager.setLookAndFeel(new FlatDraculaIJTheme());
                        break;
                    case "One Dark":
                        UIManager.setLookAndFeel(new FlatOneDarkIJTheme());
                        break;
                    case "Carbon":
                        UIManager.setLookAndFeel(new FlatCarbonIJTheme());
                        break;
                    default:
                        UIManager.setLookAndFeel(new FlatLightLaf());
                        break;
                }
            } catch (UnsupportedLookAndFeelException e) {
                System.err.println("Failed to set look and feel: " + e.getMessage());
            }
            
            // Update all components
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
            SwingUtilities.updateComponentTreeUI(this);
            
            updateStatus("Switched to " + theme + " theme");
        }
    }
    
    // Recent files management
    private void loadRecentFiles() {
        recentFiles.clear();
        for (int i = 0; i < MAX_RECENT_FILES; i++) {
            String filePath = prefs.get("recentFile" + i, null);
            if (filePath != null && !filePath.isEmpty()) {
                recentFiles.add(filePath);
            }
        }
    }
    
    private void saveRecentFiles() {
        // Clear all existing recent file preferences
        for (int i = 0; i < MAX_RECENT_FILES; i++) {
            prefs.remove("recentFile" + i);
        }
        
        // Save current recent files
        for (int i = 0; i < recentFiles.size(); i++) {
            prefs.put("recentFile" + i, recentFiles.get(i));
        }
    }
    
    private void addRecentFile(String filePath) {
        // Remove if already exists
        recentFiles.remove(filePath);
        
        // Add to front
        recentFiles.add(0, filePath);
        
        // Limit size
        while (recentFiles.size() > MAX_RECENT_FILES) {
            recentFiles.remove(recentFiles.size() - 1);
        }
        
        // Save and update menu
        saveRecentFiles();
        updateRecentFilesMenu();
    }
    
    private void updateRecentFilesMenu() {
        recentFilesMenu.removeAll();
        
        if (recentFiles.isEmpty()) {
            JMenuItem emptyItem = new JMenuItem("No recent files");
            emptyItem.setEnabled(false);
            recentFilesMenu.add(emptyItem);
        } else {
            for (int i = 0; i < recentFiles.size(); i++) {
                String filePath = recentFiles.get(i);
                String fileName = new java.io.File(filePath).getName();
                String displayText = String.format("%d. %s", i + 1, fileName);
                
                JMenuItem item = new JMenuItem(displayText);
                item.setToolTipText(filePath);
                
                // Create final reference for lambda
                final String pathToOpen = filePath;
                item.addActionListener(e -> openRecentFile(pathToOpen));
                
                recentFilesMenu.add(item);
            }
            
            recentFilesMenu.addSeparator();
            JMenuItem clearItem = new JMenuItem("Clear Recent Files");
            clearItem.addActionListener(e -> clearRecentFiles());
            recentFilesMenu.add(clearItem);
        }
    }
    
    private void openRecentFile(String filePath) {
        java.io.File file = new java.io.File(filePath);
        if (file.exists()) {
            loadModelFile(file);
        } else {
            // File no longer exists, remove from recent files
            recentFiles.remove(filePath);
            saveRecentFiles();
            updateRecentFilesMenu();
            
            JOptionPane.showMessageDialog(this,
                "File no longer exists:\n" + filePath,
                "File Not Found",
                JOptionPane.WARNING_MESSAGE);
        }
    }
    
    private void clearRecentFiles() {
        recentFiles.clear();
        saveRecentFiles();
        updateRecentFilesMenu();
        updateStatus("Recent files cleared");
    }

    public static void main(String[] args) {
        // Set system properties for better FlatLaf experience
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Kalix GUI");
        System.setProperty("flatlaf.useWindowDecorations", "false");
        System.setProperty("flatlaf.menuBarEmbedded", "false");
        
        // Show splash screen first
        SplashScreen.showSplashScreen();
        
        SwingUtilities.invokeLater(() -> {
            // Initialize FlatLaf
            try {
                // Load saved theme preference
                Preferences prefs = Preferences.userNodeForPackage(KalixGUI.class);
                String theme = prefs.get("theme", "Light");
                
                switch (theme) {
                    case "Light":
                        UIManager.setLookAndFeel(new FlatLightLaf());
                        break;
                    case "Dark":
                        UIManager.setLookAndFeel(new FlatDarkLaf());
                        break;
                    case "Dracula":
                        UIManager.setLookAndFeel(new FlatDraculaIJTheme());
                        break;
                    case "One Dark":
                        UIManager.setLookAndFeel(new FlatOneDarkIJTheme());
                        break;
                    case "Carbon":
                        UIManager.setLookAndFeel(new FlatCarbonIJTheme());
                        break;
                    default:
                        UIManager.setLookAndFeel(new FlatLightLaf());
                        break;
                }
                
                // Configure FlatLaf properties for better appearance
                UIManager.put("TextComponent.arc", 4);
                UIManager.put("Button.arc", 6);
                UIManager.put("Component.focusWidth", 1);
                UIManager.put("ScrollBar.width", 12);
                UIManager.put("TabbedPane.tabHeight", 32);
                UIManager.put("Table.rowHeight", 24);
                
            } catch (UnsupportedLookAndFeelException e) {
                System.err.println("Failed to initialize FlatLaf: " + e.getMessage());
                e.printStackTrace();
            }
            
            new KalixGUI();
        });
    }
}