package com.kalix.ide.flowviz;

import com.kalix.ide.flowviz.data.LabelResolver;
import com.kalix.ide.flowviz.data.SeriesRef;
import com.kalix.ide.flowviz.rendering.PlotColors;
import com.kalix.ide.flowviz.rendering.ViewPort;
import com.kalix.ide.flowviz.style.LineStyle;
import com.kalix.ide.flowviz.style.SeriesMarker;
import com.kalix.ide.flowviz.style.SeriesStyleResolver;
import com.kalix.ide.flowviz.style.StrokeStyle;
import com.kalix.ide.preferences.PreferenceKeys;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

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
    private static final int DOT_DIAMETER = SeriesMarker.DIAMETER;
    /** Total width of a line sample (line + end dot); for components that mirror the Key. */
    static final int SAMPLE_WIDTH = LINE_SAMPLE_WIDTH + DOT_DIAMETER;
    private static final int MIN_WIDTH = 120;
    private static final int MAX_WIDTH = 400;
    private static final int DEFAULT_OFFSET = 20;
    private static final int COLLAPSED_WIDTH = 30;  // Width when fully collapsed
    private static final int COLLAPSED_HEIGHT = 24; // Height when fully collapsed

    // Colors. The theme supplies three legend roles (background, border, foreground)
    // via the Kalix.plot.* keys; the remaining tints are derived from them once per
    // paint in refreshColors(). The derivations reproduce the historical hard-coded
    // values exactly under the light defaults (bg #ffffff, fg #444444).
    private static final Color ENTRY_HOVER_COLOR = new Color(173, 216, 230, 51); // Light blue 20% opacity
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 38); // 15% opacity
    private Color bgColor;        // legendBackground at 92% opacity (alpha 235)
    private Color headerBgStart;  // legendBackground shifted 10 towards contrast
    private Color headerBgEnd;    // legendBackground shifted 19 towards contrast
    private Color borderColor;    // legendBorder
    private Color headerDivider;  // legendBackground shifted 34 towards contrast
    private Color titleColor;     // legendForeground strengthened by 17
    private Color seriesNameColor; // legendForeground
    private Color buttonColor;    // legendForeground softened by 34
    private Color buttonHoverColor; // 5% scrim in the contrast direction

    // State
    private boolean enabled = true;
    private boolean collapsed = false;
    private int x = -1; // -1 means auto-position
    private int y = -1;
    private DisplayMode displayMode = DisplayMode.FULL_NAME;

    // Callback for enabled state changes (the toolbar's Show Key button follows it)
    private Runnable onEnabledChanged = null;

    // Callback invoked when an entry's line sample is clicked, with the series and
    // the click point. Null when style picking is unavailable (non-palette plots).
    private BiConsumer<SeriesRef, Point> onStyleClicked = null;

    // Series data
    private final List<LegendEntry> entries = new ArrayList<>();

    // Label resolver — projects SeriesRef to display string at render time so renames
    // are reflected automatically. Optional; defaults to ref.toString() if absent.
    private LabelResolver labelResolver;

    // Style resolver — projects SeriesRef to its colour at render time so palette
    // changes are reflected automatically. Optional; defaults to grey if absent.
    private SeriesStyleResolver styleResolver;

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
     * Represents a single series entry in the legend. Identity is the {@link SeriesRef};
     * the display label and colour are both projected at render time (via the
     * {@link LabelResolver} and {@link SeriesStyleResolver}) so the entry tracks
     * renames and palette changes automatically.
     */
    private static class LegendEntry {
        final SeriesRef ref;

        LegendEntry(SeriesRef ref) {
            this.ref = ref;
        }
    }

    public PlotLegendManager() {
        loadPreferences();
    }

    public void setLabelResolver(LabelResolver labelResolver) {
        this.labelResolver = labelResolver;
    }

    /** Sets the resolver consulted for each entry's swatch colour at render time. */
    public void setStyleResolver(SeriesStyleResolver styleResolver) {
        this.styleResolver = styleResolver;
    }

    /**
     * Sets the callback invoked when an entry's line sample is clicked — passed the
     * series and the click point. Setting it to {@code null} disables line-sample
     * clicking entirely (no hand cursor, no action).
     */
    public void setOnStyleClicked(BiConsumer<SeriesRef, Point> onStyleClicked) {
        this.onStyleClicked = onStyleClicked;
    }

    /**
     * Returns the index of the legend entry whose line sample contains the point,
     * or {@code -1}. Always {@code -1} when style clicking is disabled, so it gates
     * both the hand cursor and the click action.
     */
    private int lineSampleEntryAt(int mouseX, int mouseY) {
        if (!enabled || collapsed || onStyleClicked == null) {
            return -1;
        }
        int entryY = y + HEADER_HEIGHT + PADDING;
        for (int i = 0; i < entries.size(); i++) {
            Rectangle sample = new Rectangle(
                x + PADDING - 2, entryY - 2, LINE_SAMPLE_WIDTH + 8, ENTRY_HEIGHT);
            if (sample.contains(mouseX, mouseY)) {
                return i;
            }
            entryY += ENTRY_HEIGHT + ENTRY_SPACING;
        }
        return -1;
    }

    private LineStyle lineStyleFor(SeriesRef ref) {
        return styleResolver != null
            ? styleResolver.styleFor(ref)
            : new LineStyle(Color.GRAY, StrokeStyle.DEFAULT);
    }

    private String labelFor(SeriesRef ref) {
        return labelResolver != null ? labelResolver.labelFor(ref) : String.valueOf(ref);
    }

    private String sourceLabel(SeriesRef ref) {
        return labelResolver != null ? labelResolver.sourceLabel(ref) : "";
    }

    /**
     * Drops everything before and including the first {@code "."} in {@code baseName}.
     * If no dot is present (or it's the last character), returns the input unchanged.
     */
    private static String dropBasePrefix(String baseName) {
        int firstDot = baseName.indexOf('.');
        if (firstDot >= 0 && firstDot < baseName.length() - 1) {
            return baseName.substring(firstDot + 1);
        }
        return baseName;
    }

    /**
     * Adds a series to the legend. The swatch colour is resolved at render time
     * via the {@link SeriesStyleResolver}, so no colour is supplied here.
     */
    public void addSeries(SeriesRef ref) {
        // Remove existing entry with same ref if present
        entries.removeIf(e -> e.ref.equals(ref));
        entries.add(new LegendEntry(ref));
    }

    /**
     * Removes a series from the legend.
     */
    public void removeSeries(SeriesRef ref) {
        entries.removeIf(e -> e.ref.equals(ref));
    }

    /**
     * Clears all series from the legend.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Projects a {@link SeriesRef} to the legend's display string under the current
     * {@link #displayMode}. Builds the label from {@link SeriesRef#baseName()} plus the
     * resolver's {@link LabelResolver#sourceLabel} so the various modes don't have to
     * parse the {@code labelFor} output.
     * <ul>
     *   <li>FULL_NAME: {@code "node.my_node_1.rainfall [Run_1]"}</li>
     *   <li>DROP_PREFIX: {@code "my_node_1.rainfall [Run_1]"}</li>
     *   <li>DROP_PREFIX_AND_RUN: {@code "my_node_1.rainfall"}</li>
     * </ul>
     */
    private String displayLabel(SeriesRef ref) {
        String base = ref.baseName();
        switch (displayMode) {
            case FULL_NAME:
                return labelFor(ref);
            case DROP_PREFIX: {
                String source = sourceLabel(ref);
                String prefixDropped = dropBasePrefix(base);
                return source.isEmpty() ? prefixDropped : prefixDropped + " [" + source + "]";
            }
            case DROP_PREFIX_AND_RUN:
                return dropBasePrefix(base);
            default:
                return labelFor(ref);
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
     * Resolves the theme's legend colours (once per paint) and derives the header,
     * title, and button tints from them. On a light legend the derived values are
     * byte-identical to the legacy hard-coded constants; on a dark legend the same
     * shifts run in the opposite direction.
     */
    private void refreshColors() {
        PlotColors colors = PlotColors.fromUIManager();
        Color background = colors.legendBackground;
        Color foreground = colors.legendForeground;
        boolean dark = PlotColors.isDark(background);

        bgColor = PlotColors.withAlpha(background, 235);
        borderColor = colors.legendBorder;
        headerBgStart = PlotColors.shiftForContrast(background, background, 10);
        headerBgEnd = PlotColors.shiftForContrast(background, background, 19);
        headerDivider = PlotColors.shiftForContrast(background, background, 34);
        seriesNameColor = foreground;
        titleColor = PlotColors.shiftForContrast(foreground, background, 17);
        buttonColor = PlotColors.shiftForContrast(foreground, background, -34);
        buttonHoverColor = dark ? new Color(255, 255, 255, 13) : new Color(0, 0, 0, 13);
    }

    /**
     * Renders the legend on the plot.
     */
    public void render(Graphics2D g2d, ViewPort viewport) {
        if (!enabled || entries.isEmpty()) {
            return;
        }

        refreshColors();

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
            g.setColor(bgColor);
            g.fill(background);

            // Draw border
            g.setColor(borderColor);
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
            x, y, headerBgStart,
            x, y + COLLAPSED_HEIGHT, headerBgEnd
        );
        g.setPaint(gradient);
        g.fill(background);

        // Draw border
        g.setColor(borderColor);
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
            g.setColor(buttonHoverColor);
            g.fill(background);
        }

        // Draw button text
        g.setColor(buttonColor);
        g.drawString(buttonText, buttonX, buttonY);
    }

    private void drawHeader(Graphics2D g, int x, int y, int width) {
        // Header background with gradient
        GradientPaint gradient = new GradientPaint(
            x, y, headerBgStart,
            x, y + HEADER_HEIGHT, headerBgEnd
        );

        // Draw gradient fill - full rectangle for header area
        g.setPaint(gradient);
        g.fillRect(x, y, width, HEADER_HEIGHT);

        // If expanded, draw divider line at bottom of header
        if (!collapsed) {
            g.setColor(headerDivider);
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
            g.setColor(buttonHoverColor);
            g.fillRoundRect(titleX - 2, titleY, titleWidth + 4, HEADER_HEIGHT - 4, 4, 4);
        }

        // Draw title text
        g.setColor(titleColor);
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
            g.setColor(buttonHoverColor);
            g.fillRoundRect(buttonX - 2, buttonY, buttonWidth + 4, HEADER_HEIGHT - 4, 4, 4);
        }

        // Draw button text
        g.setColor(buttonColor);
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

            // Draw the line sample (line + end dot) via the shared routine, so the
            // Key and the style picker render series styling identically.
            int lineY = entryY + ENTRY_HEIGHT / 2;
            paintLineSample(g, lineStyleFor(entry.ref), x + PADDING, lineY);

            // Draw series name (transformed based on display mode)
            g.setColor(seriesNameColor);
            g.setFont(new Font("Dialog", Font.PLAIN, 10));
            int nameX = x + PADDING + LINE_SAMPLE_WIDTH + 6;

            // Project ref → display label (display-mode aware) at render time, then truncate.
            String transformedName = displayLabel(entry.ref);
            String displayName = truncateText(g, transformedName, width - (PADDING * 2 + LINE_SAMPLE_WIDTH + 6));
            g.drawString(displayName, nameX, entryY + 14);

            entryY += ENTRY_HEIGHT + ENTRY_SPACING;
        }
    }

    /**
     * Paints a series line sample — a short stroked line plus an end dot — starting
     * at {@code startX} and centred on {@code lineY}. Shared by the legend Key and
     * the style picker so both render series styling identically.
     */
    static void paintLineSample(Graphics2D g, LineStyle style, int startX, int lineY) {
        g.setColor(style.color());
        g.setStroke(style.stroke().toBasicStroke());
        g.drawLine(startX, lineY, startX + LINE_SAMPLE_WIDTH, lineY);

        SeriesMarker.paint(g, style, startX + LINE_SAMPLE_WIDTH, lineY);
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
            // Use display label (display-mode aware) for width calculation to match displayed text
            String displayedName = displayLabel(entry.ref);
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
                // Check if an entry's line sample was clicked (open the style picker)
                else {
                    int sampleEntry = lineSampleEntryAt(mouseX, mouseY);
                    if (sampleEntry >= 0) {
                        onStyleClicked.accept(entries.get(sampleEntry).ref,
                            new Point(mouseX, mouseY));
                    }
                }
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

        // Hand cursor for clickable elements (title, collapse button, line samples)
        if (titleBounds.contains(mouseX, mouseY) || collapseButtonBounds.contains(mouseX, mouseY)
                || lineSampleEntryAt(mouseX, mouseY) >= 0) {
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
        if (onEnabledChanged != null) {
            onEnabledChanged.run();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        savePreferences();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    public void setOnEnabledChanged(Runnable callback) {
        this.onEnabledChanged = callback;
    }

    /**
     * Resets the legend to its default state: shown, expanded, and auto-positioned —
     * recovering it regardless of how it was hidden, collapsed, or dragged off-screen.
     */
    public void reset() {
        x = -1;
        y = -1;
        collapsed = false;
        enabled = true;
        savePreferences();
        if (onEnabledChanged != null) {
            onEnabledChanged.run();
        }
    }

    /**
     * Loads legend preferences.
     */
    public void loadPreferences() {
        enabled = PreferenceKeys.PLOT_LEGEND_ENABLED.get();
        collapsed = PreferenceKeys.PLOT_LEGEND_COLLAPSED.get();
        x = PreferenceKeys.PLOT_LEGEND_POSITION_X.get();
        y = PreferenceKeys.PLOT_LEGEND_POSITION_Y.get();

        // Load display mode
        String modeString = PreferenceKeys.PLOT_LEGEND_DISPLAY_MODE.get();
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
        PreferenceKeys.PLOT_LEGEND_ENABLED.set(enabled);
        PreferenceKeys.PLOT_LEGEND_COLLAPSED.set(collapsed);
        PreferenceKeys.PLOT_LEGEND_POSITION_X.set(x);
        PreferenceKeys.PLOT_LEGEND_POSITION_Y.set(y);
        PreferenceKeys.PLOT_LEGEND_DISPLAY_MODE.set(displayMode.name());
    }
}
