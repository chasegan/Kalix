package com.kalix.ide.docking;

import com.kalix.ide.constants.UIConstants;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * A JPanel that can be docked and undocked from its parent container.
 * 
 * Features:
 * - Transparent docking mode activation via F4 key
 * - Visual highlight with grip when in docking mode
 * - Drag and drop support for repositioning
 * - Creates detached windows when dropped outside valid drop zones
 */
public class DockablePanel extends JPanel implements KeyListener {
    
    private boolean dockingMode = false;
    private DockGrip grip;
    private DockHighlighter highlighter;
    private Point dragStartPoint;
    private boolean isDragging = false;
    private DockingManager dockingManager;
    
    /**
     * Creates a new DockablePanel with the specified layout manager.
     * @param layout The layout manager for this panel
     */
    public DockablePanel(LayoutManager layout) {
        super(layout);
        initializeDocking();
    }
    
    /**
     * Creates a new DockablePanel with default FlowLayout.
     */
    public DockablePanel() {
        super();
        initializeDocking();
    }
    
    /**
     * Initializes docking capabilities for this panel.
     */
    private void initializeDocking() {
        setFocusable(true);
        addKeyListener(this);
        
        grip = new DockGrip();
        highlighter = new DockHighlighter();
        dockingManager = DockingManager.getInstance();
        
        setupMouseListeners();
        
        // Initially invisible
        grip.setVisible(false);
        highlighter.setVisible(false);
    }
    
    /**
     * Sets up mouse listeners for drag and drop operations.
     */
    private void setupMouseListeners() {
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (dockingMode && grip.contains(e.getPoint())) {
                    dragStartPoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dockingMode && dragStartPoint != null) {
                    Point currentPoint = e.getPoint();
                    double distance = dragStartPoint.distance(currentPoint);
                    
                    if (!isDragging && distance > UIConstants.Docking.MIN_DRAG_DISTANCE) {
                        isDragging = true;
                        dockingManager.startDrag(DockablePanel.this);
                    }
                    
                    if (isDragging) {
                        // Convert to screen coordinates and notify docking manager
                        Point screenPoint = new Point(currentPoint);
                        SwingUtilities.convertPointToScreen(screenPoint, DockablePanel.this);
                        dockingManager.updateDrag(screenPoint);
                    }
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (isDragging) {
                    Point screenPoint = new Point(e.getPoint());
                    SwingUtilities.convertPointToScreen(screenPoint, DockablePanel.this);
                    dockingManager.finishDrag(screenPoint);
                    isDragging = false;
                }
                
                dragStartPoint = null;
                setCursor(Cursor.getDefaultCursor());
            }
        };
        
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }
    
    /**
     * Activates or deactivates docking mode.
     * @param enabled true to enable docking mode, false to disable
     */
    public void setDockingMode(boolean enabled) {
        if (this.dockingMode != enabled) {
            this.dockingMode = enabled;
            
            try {
                if (enabled) {
                    requestFocusInWindow();
                    highlighter.setVisible(true);
                    grip.setVisible(true);
                    grip.setLocation(UIConstants.Docking.GRIP_MARGIN, UIConstants.Docking.GRIP_MARGIN);
                } else {
                    highlighter.setVisible(false);
                    grip.setVisible(false);
                    
                    // Reset any drag state
                    if (isDragging) {
                        dockingManager.cancelDrag();
                        isDragging = false;
                        dragStartPoint = null;
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
            } catch (Exception e) {
                // Log error but don't break the UI
                System.err.println("Error setting docking mode: " + e.getMessage());
            }
            
            repaint();
        }
    }
    
    /**
     * Returns whether docking mode is currently active.
     * @return true if docking mode is active
     */
    public boolean isDockingMode() {
        return dockingMode;
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (dockingMode) {
            // Draw highlight border
            highlighter.paintHighlight(g, getSize());
            
            // Draw grip
            if (grip.isVisible()) {
                grip.paintGrip(g);
            }
        }
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == UIConstants.Docking.TRIGGER_KEY) {
            setDockingMode(!dockingMode);
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE && dockingMode) {
            // Escape key cancels docking mode
            setDockingMode(false);
            e.consume();
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) {
        // Not used
    }
    
    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }
    
    /**
     * Called when this panel is being detached to a new window.
     * Subclasses can override to perform cleanup or preparation.
     */
    protected void onDetach() {
        // Default implementation does nothing
    }
    
    /**
     * Called when this panel is being attached to a new parent.
     * Subclasses can override to perform setup or initialization.
     * @param newParent The new parent container
     */
    protected void onAttach(Container newParent) {
        // Default implementation does nothing
    }
}