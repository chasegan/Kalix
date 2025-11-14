package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the plot legend display with draggable and collapsible functionality.
 * Provides a beautiful, semi-transparent legend showing series information.
 */
public class PlotLegendManager {

    /**
     * Display modes for series names in the legend.
     * FULL_NAME: Full name (default) - "node.my_node_1.rainfall [Run_1]"
     * DROP_PREFIX: Drop prefix up to first "." - "my_node_1.rainfall [Run_1]"
     * DROP_PREFIX_AND_RUN: Drop prefix and run label - "my_node_1.rainfall"
     */
    public enum DisplayMode {
        FULL_NAME,          // Full name with everything
        DROP_PREFIX,        // Drop prefix up to and including first dot
        DROP_PREFIX_AND_RUN // Drop prefix and run label
    }

    // Visual constants
    private static final int HEADER_HEIGHT = 24;
    private static final int ENTRY_HEIGHT = 22;
    private static final int ENTRY_SPACING = 2;
    private static final int PADDING = 8;
    private static final int CORNER_RADIUS = 8;
    private static final int LINE_SAMPLE_WIDTH = 20;
    private static final int LINE_SAMPLE_THICKNESS = 2;
    private static final int DOT_DIAMETER = 4;
    private static final int MIN_WIDTH = 120;
    private static final int MAX_WIDTH = 400;
    private static final int DEFAULT_OFFSET = 20;
    private static final int COLLAPSED_WIDTH = 30;  // Width when fully collapsed
    private static final int COLLAPSED_HEIGHT = 24; // Height when fully collapsed

    // Colors
    private static final Color BG_COLOR = new Color(255, 255, 255, 235); // 92% opacity
    private static final Color HEADER_BG_START = new Color(245, 245, 245);
    private static final Color HEADER_BG_END = new Color(236, 236, 236);
    private static final Color BORDER_COLOR = new Color(204, 204, 204);
    private static final Color HEADER_DIVIDER = new Color(221, 221, 221);
    private static final Color TITLE_COLOR = new Color(51, 51, 51);
    private static final Color SERIES_NAME_COLOR = new Color(68, 68, 68);
    private static final Color BUTTON_COLOR = new Color(102, 102, 102);
    private static final Color ENTRY_HOVER_COLOR = new Color(173, 216, 230, 51); // Light blue 20% opacity
    private static final Color BUTTON_HOVER_COLOR = new Color(0, 0, 0, 13); // 5% opacity
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 38); // 15% opacity

    // State
    private boolean enabled = true;
    private boolean collapsed = false;
    private int x = -1; // -1 means auto-position
    private int y = -1;
    private DisplayMode displayMode = DisplayMode.FULL_NAME;

    // Callback for collapsed state changes
    private Runnable onCollapsedChanged = null;

    // Series data
    private final List<LegendEntry> entries = new ArrayList<>();

    // Interaction state
    private boolean isDragging = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;
    private int pressX = 0; // Track initial press position
    private int pressY = 0;
    private boolean hasMoved = false; // Track if mouse moved during drag
    private int hoveredEntryIndex = -1;
    private boolean collapseButtonHovered = false;
    private boolean titleHovered = false;

    // Click vs drag detection
    private static final int DRAG_THRESHOLD = 5; // Pixels of movement to count as drag

    // Cached bounds
    private final Rectangle bounds = new Rectangle();
    private final Rectangle collapseButtonBounds = new Rectangle();
    private final Rectangle titleBounds = new Rectangle();

    /**
     * Represents a single series entry in the legend.
     */
    private static class LegendEntry {
        final String name;
        final Color color;

        LegendEntry(String name, Color color) {
            this.name = name;
            this.color = color;
        }
    }

    public PlotLegendManager() {
        loadPreferences();
    }

    /**
     * Adds a series to the legend.
     */
    public void addSeries(String name, Color color) {
        // Remove existing entry with same name if present
        entries.removeIf(e -> e.name.equals(name));
        entries.add(new LegendEntry(name, color));
    }

    /**
     * Removes a series from the legend.
     */
    public void removeSeries(String name) {
        entries.removeIf(e -> e.name.equals(name));
    }

    /**
     * Clears all series from the legend.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Transforms a series name based on the current display mode.
     * FULL_NAME: Full name - "node.my_node_1.rainfall [Run_1]"
     * DROP_PREFIX: Drop prefix up to first "." - "my_node_1.rainfall [Run_1]"
     * DROP_PREFIX_AND_RUN: Drop prefix and run label - "my_node_1.rainfall"
     */
    private String transformName(String fullName) {
        switch (displayMode) {
            case FULL_NAME:
                // Full name, no transformation
                return fullName;

            case DROP_PREFIX:
                // Drop prefix up to and including first "."
                int firstDot = fullName.indexOf('.');
                if (firstDot >= 0 && firstDot < fullName.length() - 1) {
                    return fullName.substring(firstDot + 1);
                }
                return fullName;

            case DROP_PREFIX_AND_RUN:
                // Drop prefix AND run label
                String nameWithoutPrefix = fullName;

                // First, drop prefix up to and including first "."
                int dot = nameWithoutPrefix.indexOf('.');
                if (dot >= 0 && dot < nameWithoutPrefix.length() - 1) {
                    nameWithoutPrefix = nameWithoutPrefix.substring(dot + 1);
                }

                // Then, drop run label if it exists (e.g., " [Run_1]")
                int bracketStart = nameWithoutPrefix.lastIndexOf(" [");
                if (bracketStart > 0 && nameWithoutPrefix.endsWith("]")) {
                    nameWithoutPrefix = nameWithoutPrefix.substring(0, bracketStart);
                }

                return nameWithoutPrefix;

            default:
                return fullName;
        }
    }

    /**
     * Cycles to the next display mode.
     */
    private void cycleDisplayMode() {
        switch (displayMode) {
            case FULL_NAME:
                displayMode = DisplayMode.DROP_PREFIX;
                break;
            case DROP_PREFIX:
                displayMode = DisplayMode.DROP_PREFIX_AND_RUN;
                break;
            case DROP_PREFIX_AND_RUN:
                displayMode = DisplayMode.FULL_NAME;
                break;
        }
        savePreferences();
    }

    /**
     * Renders the legend on the plot.
     */
    public void render(Graphics2D g2d, ViewPort viewport) {
        if (!enabled || entries.isEmpty()) {
            return;
        }

        // Calculate dimensions
        int width = calculateWidth(g2d);
        int height = calculateHeight();

        // Auto-position if not set
        if (x < 0 || y < 0) {
            x = viewport.getPlotX() + viewport.getPlotWidth() - width - DEFAULT_OFFSET;
            y = viewport.getPlotY() + DEFAULT_OFFSET;
        }

        // Update bounds for interaction
        bounds.setBounds(x, y, width, height);

        // Save graphics state
        Graphics2D g = (Graphics2D) g2d.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (collapsed) {
            // Draw minimal collapsed state (just [+] button)
            drawCollapsedButton(g, x, y);
        } else {
            // Draw full legend
            // Draw shadow
            drawShadow(g, x, y, width, height);

            // Draw main background
            RoundRectangle2D background = new RoundRectangle2D.Double(x, y, width, height, CORNER_RADIUS, CORNER_RADIUS);
            g.setColor(BG_COLOR);
            g.fill(background);

            // Draw border
            g.setColor(BORDER_COLOR);
            g.setStroke(new BasicStroke(1.0f));
            g.draw(background);

            // Draw header
            drawHeader(g, x, y, width);

            // Draw entries
            drawEntries(g, x, y + HEADER_HEIGHT, width);
        }

        g.dispose();
    }

    private void drawShadow(Graphics2D g, int x, int y, int width, int height) {
        // Simple drop shadow offset by 2px down
        RoundRectangle2D shadow = new RoundRectangle2D.Double(
            x + 2, y + 2, width, height, CORNER_RADIUS, CORNER_RADIUS
        );
        g.setColor(SHADOW_COLOR);
        g.fill(shadow);
    }

    private void drawCollapsedButton(Graphics2D g, int x, int y) {
        // Draw shadow
        RoundRectangle2D shadow = new RoundRectangle2D.Double(
            x + 2, y + 2, COLLAPSED_WIDTH, COLLAPSED_HEIGHT, CORNER_RADIUS, CORNER_RADIUS
        );
        g.setColor(SHADOW_COLOR);
        g.fill(shadow);

        // Draw background
        RoundRectangle2D background = new RoundRectangle2D.Double(
            x, y, COLLAPSED_WIDTH, COLLAPSED_HEIGHT, CORNER_RADIUS, CORNER_RADIUS
        );
        GradientPaint gradient = new GradientPaint(
            x, y, HEADER_BG_START,
            x, y + COLLAPSED_HEIGHT, HEADER_BG_END
        );
        g.setPaint(gradient);
        g.fill(background);

        // Draw border
        g.setColor(BORDER_COLOR);
        g.setStroke(new BasicStroke(1.0f));
        g.draw(background);

        // Draw [+] button text centered
        String buttonText = "[+]";
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();
        int buttonWidth = fm.stringWidth(buttonText);
        int buttonX = x + (COLLAPSED_WIDTH - buttonWidth) / 2;
        int buttonY = y + COLLAPSED_HEIGHT / 2 + fm.getAscent() / 2 - 1;

        // Update collapse button bounds
        collapseButtonBounds.setBounds(x, y, COLLAPSED_WIDTH, COLLAPSED_HEIGHT);

        // Draw hover background
        if (collapseButtonHovered) {
            g.setColor(BUTTON_HOVER_COLOR);
            g.fill(background);
        }

        // Draw button text
        g.setColor(BUTTON_COLOR);
        g.drawString(buttonText, buttonX, buttonY);
    }

    private void drawHeader(Graphics2D g, int x, int y, int width) {
        // Header background with gradient
        GradientPaint gradient = new GradientPaint(
            x, y, HEADER_BG_START,
            x, y + HEADER_HEIGHT, HEADER_BG_END
        );

        // Draw gradient fill - full rectangle for header area
        g.setPaint(gradient);
        g.fillRect(x, y, width, HEADER_HEIGHT);

        // If expanded, draw divider line at bottom of header
        if (!collapsed) {
            g.setColor(HEADER_DIVIDER);
            g.drawLine(x, y + HEADER_HEIGHT, x + width, y + HEADER_HEIGHT);
        }

        // Draw title with clickable area
        g.setFont(new Font("Dialog", Font.BOLD, 11));
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth("Key");
        int titleX = x + 6;
        int titleY = y + 2;

        // Update title bounds for click detection
        titleBounds.setBounds(titleX - 2, titleY, titleWidth + 4, HEADER_HEIGHT - 4);

        // Draw title hover background
        if (titleHovered) {
            g.setColor(BUTTON_HOVER_COLOR);
            g.fillRoundRect(titleX - 2, titleY, titleWidth + 4, HEADER_HEIGHT - 4, 4, 4);
        }

        // Draw title text
        g.setColor(TITLE_COLOR);
        g.drawString("Key", titleX, y + 16);

        // Draw collapse button
        String buttonText = collapsed ? "[+]" : "[-]";
        FontMetrics fm = g.getFontMetrics(new Font("Monospaced", Font.PLAIN, 10));
        int buttonWidth = fm.stringWidth(buttonText);
        int buttonX = x + width - buttonWidth - 6;
        int buttonY = y + 2;

        collapseButtonBounds.setBounds(buttonX - 2, buttonY, buttonWidth + 4, HEADER_HEIGHT - 4);

        // Draw button hover background
        if (collapseButtonHovered) {
            g.setColor(BUTTON_HOVER_COLOR);
            g.fillRoundRect(buttonX - 2, buttonY, buttonWidth + 4, HEADER_HEIGHT - 4, 4, 4);
        }

        // Draw button text
        g.setColor(BUTTON_COLOR);
        g.setFont(new Font("Monospaced", Font.PLAIN, 10));
        g.drawString(buttonText, buttonX, y + 16);
    }

    private void drawEntries(Graphics2D g, int x, int y, int width) {
        int entryY = y + PADDING;

        for (int i = 0; i < entries.size(); i++) {
            LegendEntry entry = entries.get(i);

            // Draw hover background
            if (i == hoveredEntryIndex) {
                g.setColor(ENTRY_HOVER_COLOR);
                g.fillRect(x + 2, entryY - 2, width - 4, ENTRY_HEIGHT);
            }

            // Draw line sample
            int lineY = entryY + ENTRY_HEIGHT / 2;
            g.setColor(entry.color);
            g.setStroke(new BasicStroke(LINE_SAMPLE_THICKNESS, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + PADDING, lineY, x + PADDING + LINE_SAMPLE_WIDTH, lineY);

            // Draw dot at end of line
            int dotX = x + PADDING + LINE_SAMPLE_WIDTH - DOT_DIAMETER / 2;
            int dotY = lineY - DOT_DIAMETER / 2;
            g.fillOval(dotX, dotY, DOT_DIAMETER, DOT_DIAMETER);

            // Draw series name (transformed based on display mode)
            g.setColor(SERIES_NAME_COLOR);
            g.setFont(new Font("Dialog", Font.PLAIN, 10));
            int nameX = x + PADDING + LINE_SAMPLE_WIDTH + 6;

            // Transform name based on display mode, then truncate if too long
            String transformedName = transformName(entry.name);
            String displayName = truncateText(g, transformedName, width - (PADDING * 2 + LINE_SAMPLE_WIDTH + 6));
            g.drawString(displayName, nameX, entryY + 14);

            entryY += ENTRY_HEIGHT + ENTRY_SPACING;
        }
    }

    private String truncateText(Graphics2D g, String text, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);

        for (int i = text.length() - 1; i > 0; i--) {
            String truncated = text.substring(0, i);
            if (fm.stringWidth(truncated) + ellipsisWidth <= maxWidth) {
                return truncated + ellipsis;
            }
        }

        return ellipsis;
    }

    private int calculateWidth(Graphics2D g) {
        if (collapsed) {
            return COLLAPSED_WIDTH;
        }

        if (entries.isEmpty()) {
            return MIN_WIDTH;
        }

        int maxWidth = MIN_WIDTH;
        FontMetrics nameFm = g.getFontMetrics(new Font("Dialog", Font.PLAIN, 10));

        for (LegendEntry entry : entries) {
            // Use transformed name for width calculation to match displayed text
            String displayedName = transformName(entry.name);
            int nameWidth = nameFm.stringWidth(displayedName);
            int totalWidth = PADDING + LINE_SAMPLE_WIDTH + 6 + nameWidth + PADDING;
            maxWidth = Math.max(maxWidth, totalWidth);
        }

        return Math.min(maxWidth, MAX_WIDTH);
    }

    private int calculateHeight() {
        if (collapsed) {
            return COLLAPSED_HEIGHT;
        }
        return HEADER_HEIGHT + (ENTRY_HEIGHT + ENTRY_SPACING) * entries.size() + PADDING;
    }

    /**
     * Handles mouse click events.
     * @return true if the event was consumed by the legend
     */
    public boolean handleMouseClick(int mouseX, int mouseY) {
        // This method is kept for compatibility but logic moved to handleMouseRelease
        return false;
    }

    /**
     * Handles mouse press events - start of potential drag or click.
     * @return true if event consumed
     */
    public boolean handleMousePress(int mouseX, int mouseY) {
        if (!enabled || !bounds.contains(mouseX, mouseY)) {
            return false;
        }

        // Start potential drag from anywhere on the legend
        isDragging = true;
        dragOffsetX = mouseX - x;
        dragOffsetY = mouseY - y;
        pressX = mouseX;
        pressY = mouseY;
        hasMoved = false;
        return true;
    }

    /**
     * Handles mouse drag events.
     * @return true if the legend is being dragged
     */
    public boolean handleMouseDrag(int mouseX, int mouseY) {
        if (!isDragging) {
            return false;
        }

        // Check if mouse has moved beyond threshold
        int deltaX = Math.abs(mouseX - pressX);
        int deltaY = Math.abs(mouseY - pressY);
        if (deltaX > DRAG_THRESHOLD || deltaY > DRAG_THRESHOLD) {
            hasMoved = true;
        }

        // Update position
        x = mouseX - dragOffsetX;
        y = mouseY - dragOffsetY;
        return true;
    }

    /**
     * Handles mouse release events.
     * Distinguishes between click (no movement) and drag (movement).
     */
    public void handleMouseRelease() {
        if (isDragging) {
            isDragging = false;

            if (hasMoved) {
                // It was a drag - save new position
                savePreferences();
            } else {
                // It was a click - trigger appropriate action based on where clicked
                int mouseX = pressX;
                int mouseY = pressY;

                // Check if title was clicked (cycle display mode) - only when expanded
                if (!collapsed && titleBounds.contains(mouseX, mouseY)) {
                    cycleDisplayMode();
                }
                // Check if collapse button was clicked (toggle collapse)
                else if (collapseButtonBounds.contains(mouseX, mouseY)) {
                    setCollapsed(!collapsed);
                    savePreferences();
                }
                // Else: click on legend body does nothing (just consumed the event)
            }

            hasMoved = false;
        }
    }

    /**
     * Handles mouse move events for hover effects.
     * @return true if hover state changed
     */
    public boolean handleMouseMove(int mouseX, int mouseY) {
        if (!enabled) {
            return false;
        }

        boolean changed = false;

        // Check title hover (only when expanded)
        boolean wasTitleHovered = titleHovered;
        titleHovered = !collapsed && titleBounds.contains(mouseX, mouseY);
        if (wasTitleHovered != titleHovered) {
            changed = true;
        }

        // Check collapse button hover
        boolean wasButtonHovered = collapseButtonHovered;
        collapseButtonHovered = collapseButtonBounds.contains(mouseX, mouseY);
        if (wasButtonHovered != collapseButtonHovered) {
            changed = true;
        }

        // Check entry hover (only if expanded)
        int prevHoveredIndex = hoveredEntryIndex;
        hoveredEntryIndex = -1;

        if (!collapsed && bounds.contains(mouseX, mouseY)) {
            int entryY = y + HEADER_HEIGHT + PADDING;
            for (int i = 0; i < entries.size(); i++) {
                Rectangle entryBounds = new Rectangle(
                    x + 2, entryY - 2, bounds.width - 4, ENTRY_HEIGHT
                );
                if (entryBounds.contains(mouseX, mouseY)) {
                    hoveredEntryIndex = i;
                    break;
                }
                entryY += ENTRY_HEIGHT + ENTRY_SPACING;
            }
        }

        if (prevHoveredIndex != hoveredEntryIndex) {
            changed = true;
        }

        return changed;
    }

    /**
     * Gets the cursor type for the given mouse position.
     */
    public Cursor getCursor(int mouseX, int mouseY) {
        if (!enabled) {
            return null;
        }

        if (isDragging) {
            return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        }

        // Hand cursor for clickable elements (title and collapse button)
        if (titleBounds.contains(mouseX, mouseY) || collapseButtonBounds.contains(mouseX, mouseY)) {
            return Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        }

        // Move cursor for draggable area (rest of legend)
        if (bounds.contains(mouseX, mouseY)) {
            return Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        }

        return null;
    }

    /**
     * Checks if the legend contains the given point.
     */
    public boolean contains(int mouseX, int mouseY) {
        return enabled && bounds.contains(mouseX, mouseY);
    }

    // Getters and setters

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        savePreferences();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        savePreferences();
        if (onCollapsedChanged != null) {
            onCollapsedChanged.run();
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setOnCollapsedChanged(Runnable callback) {
        this.onCollapsedChanged = callback;
    }

    /**
     * Loads legend preferences.
     */
    public void loadPreferences() {
        enabled = PreferenceManager.getFileBoolean(PreferenceKeys.PLOT_LEGEND_ENABLED, true);
        collapsed = PreferenceManager.getFileBoolean(PreferenceKeys.PLOT_LEGEND_COLLAPSED, false);
        x = PreferenceManager.getFileInt(PreferenceKeys.PLOT_LEGEND_POSITION_X, -1);
        y = PreferenceManager.getFileInt(PreferenceKeys.PLOT_LEGEND_POSITION_Y, -1);

        // Load display mode
        String modeString = PreferenceManager.getFileString(PreferenceKeys.PLOT_LEGEND_DISPLAY_MODE, "FULL_NAME");
        try {
            displayMode = DisplayMode.valueOf(modeString);
        } catch (IllegalArgumentException e) {
            displayMode = DisplayMode.FULL_NAME; // Fallback to default
        }
    }

    /**
     * Saves legend preferences.
     */
    public void savePreferences() {
        PreferenceManager.setFileBoolean(PreferenceKeys.PLOT_LEGEND_ENABLED, enabled);
        PreferenceManager.setFileBoolean(PreferenceKeys.PLOT_LEGEND_COLLAPSED, collapsed);
        PreferenceManager.setFileInt(PreferenceKeys.PLOT_LEGEND_POSITION_X, x);
        PreferenceManager.setFileInt(PreferenceKeys.PLOT_LEGEND_POSITION_Y, y);
        PreferenceManager.setFileString(PreferenceKeys.PLOT_LEGEND_DISPLAY_MODE, displayMode.name());
    }
}
