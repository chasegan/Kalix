package com.kalix.ide.docking;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import static com.kalix.ide.docking.DockingConstants.*;

/**
 * A specialized container that serves as a drop target for dockable panels in the Kalix IDE docking system.
 *
 * <p>DockingArea provides a region where {@link DockablePanel} instances can be dropped during
 * drag operations. It offers visual feedback during drag operations and can contain various
 * types of content including single panels, tabbed containers, and split panes.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Visual drop zone highlighting during drag operations</li>
 *   <li>Support for hybrid docking (center, top, bottom, left, right zones)</li>
 *   <li>Empty state with philosophical placeholder text</li>
 *   <li>Automatic registration with {@link DockingManager}</li>
 *   <li>Support for complex nested layouts (tabs and splits)</li>
 * </ul>
 *
 * <h3>Drop Zones:</h3>
 * <p>When a panel is dragged over an occupied docking area, the cursor position
 * determines the drop zone which affects how the new panel is integrated:</p>
 * <ul>
 *   <li><strong>Center:</strong> Creates tabbed interface with existing content</li>
 *   <li><strong>Top/Bottom:</strong> Creates vertical split pane</li>
 *   <li><strong>Left/Right:</strong> Creates horizontal split pane</li>
 * </ul>
 *
 * @see DockablePanel
 * @see DockingManager
 * @see DropZoneDetector
 * @author Kalix Development Team
 * @since 2025-09-27
 */
public class DockingArea extends JPanel {


    private boolean isHighlighted = false;
    private boolean isValidDropTarget = true;
    private String areaName;
    private PlaceholderComponent emptyLabel;

    // Hybrid docking zone support
    private DropZoneDetector.DropZone currentDropZone = DropZoneDetector.DropZone.NONE;
    private Point lastCursorPosition;

    /**
     * Creates a new DockingArea with the default name "Docking Area".
     */
    public DockingArea() {
        this("Docking Area");
    }

    /**
     * Creates a new DockingArea with the specified name.
     *
     * @param name the display name for this docking area
     */
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
            if (!highlighted) {
                currentDropZone = DropZoneDetector.DropZone.NONE;
            }
            repaint();
        }
    }

    /**
     * Updates the current drop zone based on cursor position.
     * This enables zone-specific highlighting for hybrid docking.
     */
    public void updateDropZone(Point screenPoint) {
        if (!isHighlighted || !isValidDropTarget) {
            return;
        }

        try {
            // Convert screen point to component coordinates
            Point componentPoint = new Point(screenPoint);
            SwingUtilities.convertPointFromScreen(componentPoint, this);

            // Only update if cursor is still within this area
            if (contains(componentPoint)) {
                Rectangle bounds = new Rectangle(0, 0, getWidth(), getHeight());
                DropZoneDetector.DropZone newZone = DropZoneDetector.detectZone(componentPoint, bounds);

                if (newZone != currentDropZone) {
                    currentDropZone = newZone;
                    lastCursorPosition = new Point(componentPoint);
                    repaint();
                }
            }
        } catch (Exception e) {
            // If coordinate conversion fails, ignore the update
        }
    }

    /**
     * Returns the current drop zone.
     */
    public DropZoneDetector.DropZone getCurrentDropZone() {
        return currentDropZone;
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
     * Removes all components without triggering the empty state check.
     * Used during programmatic layout setup to avoid placeholder interference.
     */
    public void removeAllQuietly() {
        super.removeAll();
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
        // Highlighting is now done in paintChildren() to appear on top
    }

    /**
     * Draws general highlighting for empty areas (full area highlight).
     */
    private void drawGeneralHighlight(Graphics2D g2d) {
        // Draw highlight background
        g2d.setColor(Colors.DROP_ZONE_HIGHLIGHT);
        g2d.fillRect(0, 0, getWidth(), getHeight());

        // Draw highlight border
        g2d.setColor(Colors.DROP_ZONE_BORDER);
        g2d.setStroke(new BasicStroke(Dimensions.DROP_ZONE_BORDER_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, Layout.DROP_ZONE_DASH_PATTERN, 0));
        g2d.drawRect(2, 2, getWidth() - 4, getHeight() - 4);
    }

    /**
     * Draws zone-specific highlighting for occupied areas.
     */
    private void drawZoneSpecificHighlight(Graphics2D g2d) {
        Rectangle bounds = new Rectangle(0, 0, getWidth(), getHeight());
        Rectangle zoneBounds = DropZoneDetector.getZoneBounds(currentDropZone, bounds);

        if (zoneBounds != null) {
            // Fill the specific zone with highlight
            g2d.setColor(Colors.DROP_ZONE_HIGHLIGHT);
            g2d.fillRect(zoneBounds.x, zoneBounds.y, zoneBounds.width, zoneBounds.height);

            // Draw border around the specific zone
            g2d.setColor(Colors.DROP_ZONE_BORDER);
            g2d.setStroke(new BasicStroke(Dimensions.DROP_ZONE_BORDER_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawRect(zoneBounds.x + 1, zoneBounds.y + 1, zoneBounds.width - 2, zoneBounds.height - 2);

            // Draw zone description
            drawZoneDescription(g2d, zoneBounds);
        }
    }

    /**
     * Draws the zone description text.
     */
    private void drawZoneDescription(Graphics2D g2d, Rectangle zoneBounds) {
        String description = DropZoneDetector.getZoneDescription(currentDropZone);
        if (description.isEmpty()) {
            return;
        }

        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.WHITE);
        g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, Dimensions.PLACEHOLDER_FONT_SIZE));

        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(description);
        int textHeight = fm.getHeight();

        int x = zoneBounds.x + (zoneBounds.width - textWidth) / 2;
        int y = zoneBounds.y + (zoneBounds.height + textHeight) / 2 - fm.getDescent();

        g2d.drawString(description, x, y);
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);

        // Draw highlights on top of children so they're visible
        if (isHighlighted) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (currentDropZone != DropZoneDetector.DropZone.NONE && !isEmpty()) {
                // Show zone-specific highlighting for occupied areas
                drawZoneSpecificHighlight(g2d);
            } else {
                // Show general highlighting for empty areas
                drawGeneralHighlight(g2d);

                // If empty and highlighted, show drop hint
                if (isEmpty()) {
                    drawDropHint(g2d);
                }
            }

            g2d.dispose();
        }
    }

    /**
     * Draws the drop hint text for empty areas.
     */
    private void drawDropHint(Graphics2D g2d) {
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
    }

    @Override
    public void removeNotify() {
        // Unregister from DockingManager when removed from component hierarchy
        DockingManager.getInstance().unregisterDockingArea(this);
        super.removeNotify();
    }
}