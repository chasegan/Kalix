package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;

/**
 * Simple test application to demonstrate the docking package functionality.
 * 
 * Creates a window with several dockable panels to test the drag and drop
 * behavior, highlighting, grip functionality, and window detachment.
 */
public class DockingTestApp extends JFrame {
    
    public DockingTestApp() {
        setTitle("Docking Package Test");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setLocationRelativeTo(null);
        
        initializeComponents();
    }
    
    private void initializeComponents() {
        setLayout(new BorderLayout());
        
        // Create main content area with BorderLayout
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // Create test dockable panels
        DockablePanel leftPanel = createTestPanel("Left Panel", Color.LIGHT_GRAY, "This is the left panel.\n\nPress F4 to enter docking mode,\nthen drag the grip to move this panel.");
        DockablePanel rightPanel = createTestPanel("Right Panel", Color.CYAN, "This is the right panel.\n\nIt can also be docked and moved\naround the application.");
        DockablePanel topPanel = createTestPanel("Top Panel", Color.YELLOW, "Top panel for testing.\n\nTry dragging panels between\ndifferent regions!");
        DockablePanel bottomPanel = createTestPanel("Bottom Panel", Color.PINK, "Bottom panel.\n\nDrag panels outside the window\nto create detached windows.");
        DockablePanel centerPanel = createTestPanel("Center Panel", Color.WHITE, "Center panel with main content.\n\nThis demonstrates how dockable\npanels can be used for any type\nof content in your application.");
        
        // Add panels to main layout
        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(rightPanel, BorderLayout.EAST);
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        
        add(mainPanel, BorderLayout.CENTER);
        
        // Create instructions panel
        add(createInstructionsPanel(), BorderLayout.SOUTH);
        
        // Register drop zones for each region
        DockingManager manager = DockingManager.getInstance();
        manager.registerDropZone(new BasicDockZone(leftPanel));
        manager.registerDropZone(new BasicDockZone(rightPanel));
        manager.registerDropZone(new BasicDockZone(topPanel));
        manager.registerDropZone(new BasicDockZone(bottomPanel));
        manager.registerDropZone(new BasicDockZone(centerPanel));
    }
    
    private DockablePanel createTestPanel(String title, Color backgroundColor, String content) {
        DockablePanel panel = new DockablePanel(new BorderLayout());
        panel.setBackground(backgroundColor);
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.setPreferredSize(new Dimension(150, 150));
        
        // Add content to the panel
        JTextArea textArea = new JTextArea(content);
        textArea.setEditable(false);
        textArea.setBackground(backgroundColor);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setMargin(new Insets(10, 10, 10, 10));
        
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createInstructionsPanel() {
        JPanel instructionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        instructionsPanel.setBackground(Color.LIGHT_GRAY);
        
        JLabel instructionsLabel = new JLabel("<html><b>Instructions:</b> Click on any panel and press F4 to enter docking mode. Then drag the grip to move panels around or detach them to new windows.</html>");
        instructionsLabel.setFont(instructionsLabel.getFont().deriveFont(Font.PLAIN, 11f));
        
        instructionsPanel.add(instructionsLabel);
        return instructionsPanel;
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new DockingTestApp().setVisible(true);
        });
    }
}