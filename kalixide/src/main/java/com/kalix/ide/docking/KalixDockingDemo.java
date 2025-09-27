package com.kalix.ide.docking;

import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;

import javax.swing.*;
import java.awt.*;

/**
 * Demo application showing how the docking system integrates with KalixIDE components.
 * 
 * This demonstrates the docking system working with the real MapPanel and EnhancedTextEditor
 * components from the KalixIDE, showing how existing components can be made dockable
 * with minimal changes to the existing codebase.
 */
public class KalixDockingDemo extends JFrame {
    
    private DockableMapPanel dockableMapPanel;
    private DockableTextEditor dockableTextEditor;
    private JSplitPane splitPane;
    
    public KalixDockingDemo() {
        setTitle("Kalix IDE - Docking Demo");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        
        initializeComponents();
        setupDockingSystem();
    }
    
    private void initializeComponents() {
        setLayout(new BorderLayout());
        
        // Create dockable versions of the real KalixIDE components
        MapPanel mapPanel = new MapPanel();
        EnhancedTextEditor textEditor = new EnhancedTextEditor();
        
        // Set up the text editor with some demo content
        textEditor.setText(getDefaultModelText());
        
        // Wrap the components in dockable panels
        dockableMapPanel = new DockableMapPanel(mapPanel);
        dockableTextEditor = new DockableTextEditor(textEditor);
        
        // Set up the main split pane layout (similar to KalixIDE)
        splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(dockableMapPanel),
            dockableTextEditor
        );
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.4);
        
        add(splitPane, BorderLayout.CENTER);
        
        // Add instructions panel
        add(createInstructionsPanel(), BorderLayout.SOUTH);
    }
    
    private void setupDockingSystem() {
        DockingManager manager = DockingManager.getInstance();
        
        // Register drop zones for the split pane components
        manager.registerDropZone(new SplitPaneDockZone(splitPane, JSplitPane.LEFT));
        manager.registerDropZone(new SplitPaneDockZone(splitPane, JSplitPane.RIGHT));
        
        // Also register the split pane itself as a drop zone for the center
        manager.registerDropZone(new BasicDockZone(splitPane) {
            @Override
            public boolean acceptPanel(DockablePanel panel, Point dropPoint) {
                // Convert the split pane to a tabbed layout for center drops
                return handleCenterDrop(panel, dropPoint);
            }
        });
    }
    
    /**
     * Custom dock zone for split pane sides.
     */
    private static class SplitPaneDockZone extends BasicDockZone {
        private final JSplitPane splitPane;
        private final String side;
        
        public SplitPaneDockZone(JSplitPane splitPane, String side) {
            super(splitPane);
            this.splitPane = splitPane;
            this.side = side;
        }
        
        @Override
        public boolean containsPoint(Point screenPoint) {
            // Convert screen point to split pane coordinates
            Point localPoint = new Point(screenPoint);
            SwingUtilities.convertPointFromScreen(localPoint, splitPane);
            
            // Determine if point is in the correct side
            if (side.equals(JSplitPane.LEFT)) {
                return localPoint.x < splitPane.getDividerLocation();
            } else {
                return localPoint.x > splitPane.getDividerLocation();
            }
        }
        
        @Override
        public boolean acceptPanel(DockablePanel panel, Point dropPoint) {
            // Remove panel from current parent
            Container currentParent = panel.getParent();
            if (currentParent != null) {
                currentParent.remove(panel);
                currentParent.revalidate();
                currentParent.repaint();
            }
            
            // Add panel to the correct side of the split pane
            if (side.equals(JSplitPane.LEFT)) {
                splitPane.setLeftComponent(new JScrollPane(panel));
            } else {
                splitPane.setRightComponent(panel);
            }
            
            panel.setDockingMode(false);
            return true;
        }
    }
    
    /**
     * Handles dropping panels in the center of the split pane.
     */
    private boolean handleCenterDrop(DockablePanel panel, Point dropPoint) {
        // For this demo, we'll create a tabbed pane in the center
        // This is a simplified implementation
        
        // Remove panel from current parent
        Container currentParent = panel.getParent();
        if (currentParent != null) {
            currentParent.remove(panel);
            currentParent.revalidate();
            currentParent.repaint();
        }
        
        // For simplicity, replace the right component with the dropped panel
        splitPane.setRightComponent(panel);
        panel.setDockingMode(false);
        
        return true;
    }
    
    private JPanel createInstructionsPanel() {
        JPanel instructionsPanel = new JPanel(new BorderLayout());
        instructionsPanel.setBackground(Color.LIGHT_GRAY);
        instructionsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
        JLabel instructionsLabel = new JLabel(
            "<html><b>Kalix IDE Docking Demo</b><br/>" +
            "• Click on the map panel or text editor and press <b>F4</b> to enter docking mode<br/>" +
            "• Drag the grip that appears to move panels around<br/>" +
            "• Drop panels outside the window to create detached windows<br/>" +
            "• This demonstrates integration with real KalixIDE components</html>"
        );
        instructionsLabel.setFont(instructionsLabel.getFont().deriveFont(Font.PLAIN, 12f));
        
        instructionsPanel.add(instructionsLabel, BorderLayout.WEST);
        
        // Add status indicator
        JLabel statusLabel = new JLabel("Press F4 on any panel to start");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 10f));
        statusLabel.setForeground(Color.DARK_GRAY);
        instructionsPanel.add(statusLabel, BorderLayout.EAST);
        
        return instructionsPanel;
    }
    
    private String getDefaultModelText() {
        return "[NODES]\n" +
               "Node1, 100, 200, Reservoir\n" +
               "Node2, 300, 200, Junction\n" +
               "Node3, 500, 200, Outfall\n\n" +
               "[LINKS]\n" +
               "Link1, Node1, Node2\n" +
               "Link2, Node2, Node3\n\n" +
               "# This is a sample hydrological model\n" +
               "# demonstrating the docking system\n" +
               "# working with KalixIDE components";
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new KalixDockingDemo().setVisible(true);
        });
    }
}