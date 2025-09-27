package com.kalix.ide.docking;

import javax.swing.*;
import java.awt.*;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A container that can accept dockable panels as drop targets.
 * Provides visual feedback during drag operations and manages docked panels.
 */
public class DockingArea extends JPanel {


    private boolean isHighlighted = false;
    private boolean isValidDropTarget = true;
    private String areaName;
    private PlaceholderComponent emptyLabel;

    public DockingArea() {
        this("Docking Area");
    }

    public DockingArea(String name) {
        this.areaName = name;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(Layout.AREA_BORDER_SIZE, Layout.AREA_BORDER_SIZE,
                                                 Layout.AREA_BORDER_SIZE, Layout.AREA_BORDER_SIZE));

        // Create the empty label with Nietzsche quote
        createEmptyLabel();
        showEmptyState();

        // Register with DockingManager
        DockingManager.getInstance().registerDockingArea(this);
    }

    /**
     * Creates the subtle empty state label.
     */
    private void createEmptyLabel() {
        emptyLabel = PlaceholderComponent.createNietzscheQuote();
    }

    /**
     * Shows the empty state with the philosophical quote.
     */
    private void showEmptyState() {
        super.removeAll(); // Use super to avoid recursion
        add(emptyLabel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    /**
     * Adds a dockable panel to this area.
     */
    public void addDockablePanel(DockablePanel panel) {
        // Remove all components without triggering empty state check
        super.removeAll();
        add(panel, BorderLayout.CENTER);
        revalidate();
        repaint();

        // Ensure the panel can receive focus for F9 key events
        panel.requestDockingFocus();
    }

    /**
     * Removes a dockable panel from this area.
     */
    public void removeDockablePanel(DockablePanel panel) {
        remove(panel);
        checkEmptyState();
    }

    @Override
    public void remove(Component comp) {
        super.remove(comp);
        // Check empty state whenever any component is removed
        checkEmptyState();
    }

    @Override
    public void removeAll() {
        super.removeAll();
        // Check empty state after removing all components
        checkEmptyState();
    }

    /**
     * Checks if the area is empty and shows appropriate state.
     */
    private void checkEmptyState() {
        if (isEmpty()) {
            showEmptyState();
        } else {
            revalidate();
            repaint();
        }
    }

    /**
     * Returns true if this area is empty (contains no real content, only placeholders).
     */
    public boolean isEmpty() {
        for (Component comp : getComponents()) {
            if (!(comp instanceof PlaceholderComponent)) {
                return false; // Found a non-placeholder component
            }
        }
        return true; // Only placeholders (or no components at all)
    }

    /**
     * Sets whether this area should be highlighted during drag operations.
     */
    public void setHighlighted(boolean highlighted) {
        if (this.isHighlighted != highlighted) {
            this.isHighlighted = highlighted;
            repaint();
        }
    }

    /**
     * Returns whether this area is currently highlighted.
     */
    public boolean isHighlighted() {
        return isHighlighted;
    }

    /**
     * Sets whether this area can accept drop operations.
     */
    public void setValidDropTarget(boolean validDropTarget) {
        this.isValidDropTarget = validDropTarget;
    }

    /**
     * Returns whether this area can accept drop operations.
     */
    public boolean isValidDropTarget() {
        return isValidDropTarget;
    }

    /**
     * Checks if the given screen point is within this area's bounds.
     */
    public boolean containsScreenPoint(Point screenPoint) {
        if (!isValidDropTarget || !isVisible()) {
            return false;
        }

        try {
            // Ensure component is properly initialized and showing
            if (!isDisplayable() || !isShowing()) {
                return false;
            }

            // Convert screen point to component coordinates
            Point componentPoint = new Point(screenPoint);
            SwingUtilities.convertPointFromScreen(componentPoint, this);

            return contains(componentPoint);
        } catch (Exception e) {
            // If coordinate conversion fails, return false to avoid hanging
            System.err.println("Warning: Failed to convert screen coordinates for drop target check: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns the name of this docking area.
     */
    public String getAreaName() {
        return areaName;
    }

    /**
     * Sets the name of this docking area.
     */
    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (isHighlighted) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw highlight background
            g2d.setColor(Colors.DROP_ZONE_HIGHLIGHT);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            // Draw highlight border
            g2d.setColor(Colors.DROP_ZONE_BORDER);
            g2d.setStroke(new BasicStroke(Dimensions.DROP_ZONE_BORDER_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, Layout.DROP_ZONE_DASH_PATTERN, 0));
            g2d.drawRect(2, 2, getWidth() - 4, getHeight() - 4);

            g2d.dispose();
        }
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);

        // If empty and highlighted, show drop hint
        if (isHighlighted && getComponentCount() == 0) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            String message = Text.DROP_HINT;
            FontMetrics fm = g2d.getFontMetrics();
            int messageWidth = fm.stringWidth(message);
            int messageHeight = fm.getHeight();

            int x = (getWidth() - messageWidth) / 2;
            int y = (getHeight() + messageHeight) / 2 - fm.getDescent();

            g2d.setColor(Color.WHITE);
            g2d.setFont(g2d.getFont().deriveFont(Font.BOLD));
            g2d.drawString(message, x, y);

            g2d.dispose();
        }
    }

    @Override
    public void removeNotify() {
        // Unregister from DockingManager when removed from component hierarchy
        DockingManager.getInstance().unregisterDockingArea(this);
        super.removeNotify();
    }
}