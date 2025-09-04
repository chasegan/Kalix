package com.kalix.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme;
import com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.prefs.Preferences;

public class KalixGUI extends JFrame {
    private MapPanel mapPanel;
    private JTextArea textEditor;
    private JLabel statusLabel;
    private Preferences prefs;
    private String currentTheme;

    public KalixGUI() {
        // Initialize preferences
        prefs = Preferences.userNodeForPackage(KalixGUI.class);
        currentTheme = prefs.get("theme", "Light");
        
        setTitle("Kalix Hydrologic Modeling GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        initializeComponents();
        setupLayout();
        setupMenuBar();
        
        setVisible(true);
    }

    private void initializeComponents() {
        mapPanel = new MapPanel();
        textEditor = new JTextArea();
        textEditor.setFont(new Font("Consolas", Font.PLAIN, 12));
        textEditor.setText("# Kalix Model\n# Edit your hydrologic model here...\n");
        
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }

    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create split pane with map panel on left, text editor on right
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(mapPanel),
            new JScrollPane(textEditor)
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
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.add(createMenuItem("Zoom In", e -> zoomIn()));
        viewMenu.add(createMenuItem("Zoom Out", e -> zoomOut()));
        viewMenu.add(createMenuItem("Reset Zoom", e -> resetZoom()));
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
        menuBar.add(viewMenu);
        menuBar.add(graphMenu);
        menuBar.add(helpMenu);
        
        setJMenuBar(menuBar);
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
        updateStatus("Open model - Not yet implemented");
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
        updateStatus("Undo - Not yet implemented");
    }

    private void redoAction() {
        updateStatus("Redo - Not yet implemented");
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

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "Kalix Hydrologic Modeling GUI\nVersion 1.0\n\nA Java Swing interface for Kalix hydrologic models.",
            "About Kalix GUI",
            JOptionPane.INFORMATION_MESSAGE);
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

    public static void main(String[] args) {
        // Set system properties for better FlatLaf experience
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Kalix GUI");
        System.setProperty("flatlaf.useWindowDecorations", "false");
        System.setProperty("flatlaf.menuBarEmbedded", "false");
        
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