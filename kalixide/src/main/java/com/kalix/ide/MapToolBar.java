package com.kalix.ide;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.icons.HollowIcon;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The display toggles floating over the top-right corner of a {@link MapView}, drawn as a
 * small rounded group. The buttons change that map alone and show its state, read back
 * whenever it changes.
 */
public final class MapToolBar extends JToolBar {

    private static final int ARC = 8;
    private static final int PADDING = 3;

    private final MapPanel map;
    private final JToggleButton gridlinesButton = new JToggleButton();
    private final JToggleButton labelsButton = new JToggleButton();

    MapToolBar(MapPanel map) {
        this.map = map;
        setFloatable(false);
        setRollover(true);
        setOpaque(false); // paintComponent draws the rounded group; the corners show the map
        setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));

        configure(gridlinesButton, "Toggle Gridlines");
        gridlinesButton.addActionListener(e -> map.setShowGridlines(!map.isShowGridlines()));

        // One button cycles the three label modes (names, aka, hidden): selected while
        // labels are drawn, its icon says which text.
        configure(labelsButton, "Node labels");
        labelsButton.addActionListener(e -> map.setLabelMode(map.getLabelMode().next()));

        add(gridlinesButton);
        add(labelsButton);

        map.addPropertyChangeListener(MapPanel.SHOW_GRIDLINES_PROPERTY, e -> refreshGridlines());
        map.addPropertyChangeListener(MapPanel.LABEL_MODE_PROPERTY, e -> refreshLabels());
        refreshIcons();
    }

    /** Rebuilds the icons in the new theme's colour. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (map != null) { // null while JToolBar's constructor runs
            refreshIcons();
        }
    }

    /** The group's rounded background and outline, in the current theme's toolbar colours. */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(themeColor("ToolBar.background", Color.WHITE));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.setColor(themeColor("ToolBar.borderColor", themeColor("Component.borderColor", Color.LIGHT_GRAY)));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
    }

    private static Color themeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static void configure(JToggleButton button, String accessibleName) {
        button.setFocusPainted(false);
        button.setFocusable(false); // keep keyboard focus on the map
        button.getAccessibleContext().setAccessibleName(accessibleName);
    }

    private void refreshIcons() {
        gridlinesButton.setIcon(icon(FontAwesomeSolid.BORDER_NONE));
        refreshGridlines();
        refreshLabels();
    }

    private void refreshGridlines() {
        boolean gridVisible = map.isShowGridlines();
        gridlinesButton.setSelected(gridVisible);
        gridlinesButton.setToolTipText(gridVisible
            ? "Gridlines visible - click to hide"
            : "Gridlines hidden - click to show");
    }

    /** The label icon depends on the mode, so it is rebuilt here. */
    private void refreshLabels() {
        NodeLabelMode mode = map.getLabelMode();
        labelsButton.setIcon(labelIcon(mode));
        labelsButton.setSelected(mode.drawsLabels());
        labelsButton.setToolTipText(labelModeTooltip(mode));
    }

    /** Names: the tag. Akas: the "A". Hidden: the tag, hollow. */
    private static Icon labelIcon(NodeLabelMode mode) {
        return switch (mode) {
            case NAMES -> icon(FontAwesomeSolid.TAG);
            case AKA -> icon(FontAwesomeSolid.FONT);
            case HIDDEN -> HollowIcon.of(FontAwesomeSolid.TAG, AppConstants.TOOLBAR_ICON_SIZE,
                ThemeUtils.toolBarIconColor());
        };
    }

    private static FontIcon icon(FontAwesomeSolid glyph) {
        FontIcon icon = FontIcon.of(glyph, AppConstants.TOOLBAR_ICON_SIZE);
        icon.setIconColor(ThemeUtils.toolBarIconColor());
        return icon;
    }

    /** The current mode and what the next click does, e.g. "Node labels: akas only - click to hide". */
    private static String labelModeTooltip(NodeLabelMode mode) {
        return switch (mode) {
            case NAMES -> "Node labels: names - click to show akas only";
            case AKA -> "Node labels: akas only - click to hide";
            case HIDDEN -> "Node labels: hidden (shown on hover) - click to show names";
        };
    }
}
