package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A JPanel that can be made dockable by pressing F6 while hovering.
 * When activated, shows a translucent blue highlight and a draggable grip.
 */
public class DockablePanel extends JPanel {


    // State management
    private boolean isHighlighted = false;
    private boolean isHovered = false;
    private boolean dockingModeActive = false;
    private Timer dockingModeTimer;
    private DockingGrip grip;

    // Listeners
    private MouseListener hoverListener;
    private KeyListener keyListener;

    public DockablePanel() {
        this(new BorderLayout());
    }

    public DockablePanel(LayoutManager layout) {
        super(layout);
        initializeDocking();
    }

    /**
     * Initializes the docking functionality for this panel.
     */
    private void initializeDocking() {
        setFocusable(true);

        // Create grip component
        grip = new DockingGrip(this);
        grip.setVisible(false);

        // Set up mouse listener for hover detection
        hoverListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                isHovered = true;
                updateHighlight();
                // Request focus when mouse enters to ensure key events work
                requestFocusInWindow();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                isHovered = false;
                updateHighlight();
            }
        };
        addMouseListener(hoverListener);

        // Set up key listener for F9 detection - toggle mode on/off
        keyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F9) {
                    toggleDockingMode();
                }
            }
        };
        addKeyListener(keyListener);

        // Set focus policy to accept focus
        setFocusable(true);

        // Add focus listener to handle focus events
        addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                // Panel gained focus - ready for key events
            }
        });
    }

    /**
     * Toggles docking mode on/off with F9.
     */
    private void toggleDockingMode() {
        if (dockingModeActive) {
            deactivateDockingMode();
        } else {
            activateDockingMode();
        }
    }

    /**
     * Activates docking mode, showing the grip if hovered.
     * Mode stays active until manually toggled off.
     */
    private void activateDockingMode() {
        if (dockingModeActive) {
            return; // Already active
        }

        dockingModeActive = true;
        updateHighlight();

        // No timer needed - stays on until manually toggled off
    }

    /**
     * Deactivates docking mode, hiding the grip.
     */
    private void deactivateDockingMode() {
        if (!dockingModeActive) {
            return; // Already inactive
        }

        dockingModeActive = false;
        updateHighlight();

        // Clean up any timer if it exists
        if (dockingModeTimer != null) {
            dockingModeTimer.stop();
            dockingModeTimer = null;
        }

        // Docking mode deactivated
    }

    /**
     * Updates the highlight state based on hover and docking mode state.
     */
    private void updateHighlight() {
        boolean shouldHighlight = isHovered && dockingModeActive;

        if (shouldHighlight != isHighlighted) {
            isHighlighted = shouldHighlight;
            grip.setVisible(isHighlighted);
            repaint();

            if (isHighlighted) {
                positionGrip();
            }
        }
    }

    /**
     * Positions the grip in the top-left corner of the panel.
     */
    private void positionGrip() {
        if (grip.getParent() != this) {
            add(grip);
        }

        grip.setBounds(Dimensions.GRIP_MARGIN, Dimensions.GRIP_MARGIN,
                       Dimensions.GRIP_WIDTH, Dimensions.GRIP_HEIGHT);
        setComponentZOrder(grip, 0); // Bring to front
    }

    @Override
    public void doLayout() {
        super.doLayout();
        // Ensure grip stays positioned correctly after layout
        if (grip != null && grip.isVisible()) {
            grip.setBounds(Dimensions.GRIP_MARGIN, Dimensions.GRIP_MARGIN,
                       Dimensions.GRIP_WIDTH, Dimensions.GRIP_HEIGHT);
            setComponentZOrder(grip, 0);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (isHighlighted) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw highlight border
            g2d.setColor(Colors.HIGHLIGHT);
            g2d.setStroke(new BasicStroke(Dimensions.HIGHLIGHT_BORDER_WIDTH));
            g2d.drawRect(1, 1, getWidth() - 3, getHeight() - 3);

            g2d.dispose();
        }
    }


    /**
     * Returns whether this panel is currently highlighted (F6 + hover).
     */
    public boolean isHighlighted() {
        return isHighlighted;
    }

    /**
     * Forces the panel to request focus so it can receive key events.
     */
    public void requestDockingFocus() {
        requestFocusInWindow();
    }
}