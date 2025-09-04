package com.kalix.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class KalixGUI extends JFrame {
    private MapPanel mapPanel;
    private JTextArea textEditor;
    private JLabel statusLabel;

    public KalixGUI() {
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new KalixGUI();
        });
    }
}