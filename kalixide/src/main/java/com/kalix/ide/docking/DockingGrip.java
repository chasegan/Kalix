package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A specialized JComponent that serves as the draggable grip for DockablePanel.
 * Handles mouse interactions for initiating drag operations and provides visual feedback.
 */
public class DockingGrip extends JComponent {
    private boolean isDragging = false;
    private Point dragStart = null;
    private final DockablePanel parentPanel;

    /**
     * Creates a new DockingGrip for the specified parent panel.
     *
     * @param parentPanel The DockablePanel that owns this grip
     */
    public DockingGrip(DockablePanel parentPanel) {
        this.parentPanel = parentPanel;
        setupGrip();
    }

    /**
     * Initializes the grip's appearance and behavior.
     */
    private void setupGrip() {
        setPreferredSize(new Dimension(Dimensions.GRIP_WIDTH, Dimensions.GRIP_HEIGHT));
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        setupMouseHandlers();
    }

    /**
     * Sets up mouse event handling for drag operations.
     */
    private void setupMouseHandlers() {
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isDragging = true;
                    dragStart = e.getPoint();
                    // Convert to screen coordinates for dragging
                    SwingUtilities.convertPointToScreen(dragStart, DockingGrip.this);

                    // Start drag operation
                    startDragOperation(dragStart);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (isDragging) {
                    // Set flag first to prevent multiple calls
                    isDragging = false;
                    dragStart = null;

                    // Add a small delay to ensure mouse events have settled
                    Timer delayTimer = new Timer(Timing.MOUSE_SETTLE_DELAY, evt -> {
                        // End drag operation
                        endDragOperation(e);
                        // Keep docking mode active - user can toggle off with F9
                    });
                    delayTimer.setRepeats(false);
                    delayTimer.start();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDragging && dragStart != null) {
                    // Handle drag movement
                    Point currentPoint = e.getPoint();
                    SwingUtilities.convertPointToScreen(currentPoint, DockingGrip.this);
                    updateDragOperation(currentPoint);
                }
            }

            // Mouse event handlers for future use if needed
        };

        addMouseListener(dragHandler);
        addMouseMotionListener(dragHandler);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw grip background with rounded corners
        RoundRectangle2D.Float gripShape = new RoundRectangle2D.Float(
            0, 0, getWidth(), getHeight(),
            Dimensions.GRIP_CORNER_RADIUS, Dimensions.GRIP_CORNER_RADIUS);
        g2d.setColor(Colors.GRIP);
        g2d.fill(gripShape);

        // Draw dot pattern with inset shadow effect
        drawDotPattern(g2d);

        g2d.dispose();
    }

    /**
     * Draws the dot pattern on the grip with inset shadow effects.
     */
    private void drawDotPattern(Graphics2D g2d) {
        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;

        // Create a 3x2 grid of dots
        int[][] dotPositions = {
            {centerX - Dimensions.DOT_SPACING, centerY - Dimensions.DOT_SPACING/2},
            {centerX, centerY - Dimensions.DOT_SPACING/2},
            {centerX + Dimensions.DOT_SPACING, centerY - Dimensions.DOT_SPACING/2},
            {centerX - Dimensions.DOT_SPACING, centerY + Dimensions.DOT_SPACING/2},
            {centerX, centerY + Dimensions.DOT_SPACING/2},
            {centerX + Dimensions.DOT_SPACING, centerY + Dimensions.DOT_SPACING/2}
        };

        for (int[] pos : dotPositions) {
            drawInsetDot(g2d, pos[0], pos[1]);
        }
    }

    /**
     * Draws a single dot with inset shadow effect (looks like a hole).
     */
    private void drawInsetDot(Graphics2D g2d, int x, int y) {
        // Draw dark shadow on top-left (creates inset effect)
        g2d.setColor(Colors.DOT_SHADOW);
        g2d.fillOval(x - Dimensions.DOT_SIZE/2 - 1, y - Dimensions.DOT_SIZE/2 - 1,
                     Dimensions.DOT_SIZE, Dimensions.DOT_SIZE);

        // Draw light highlight on bottom-right
        g2d.setColor(Colors.DOT_HIGHLIGHT);
        g2d.fillOval(x - Dimensions.DOT_SIZE/2 + 1, y - Dimensions.DOT_SIZE/2 + 1,
                     Dimensions.DOT_SIZE, Dimensions.DOT_SIZE);

        // Draw main dot
        g2d.setColor(Colors.GRIP_DOTS);
        g2d.fillOval(x - Dimensions.DOT_SIZE/2, y - Dimensions.DOT_SIZE/2,
                     Dimensions.DOT_SIZE, Dimensions.DOT_SIZE);
    }

    /**
     * Initiates the drag operation for undocking the panel.
     */
    private void startDragOperation(Point screenLocation) {
        DockingManager.getInstance().startDragOperation(parentPanel, screenLocation);
    }

    /**
     * Updates the drag operation with new position.
     */
    private void updateDragOperation(Point screenLocation) {
        DockingManager.getInstance().updateDragOperation(screenLocation);
    }

    /**
     * Ends the drag operation and handles drop.
     */
    private void endDragOperation(MouseEvent e) {
        Point screenLocation = e.getPoint();
        SwingUtilities.convertPointToScreen(screenLocation, this);
        DockingManager.getInstance().endDragOperation(screenLocation);
    }

    /**
     * Returns whether this grip is currently being dragged.
     */
    public boolean isDragging() {
        return isDragging;
    }
}