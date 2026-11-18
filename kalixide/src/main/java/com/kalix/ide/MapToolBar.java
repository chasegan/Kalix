package com.kalix.ide;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.JToggleButton;
import javax.swing.JToolBar;

/**
 * The toolbar across the top of a {@link MapView}: display toggles for the one map below it.
 * The buttons change that map alone and show its state, read back whenever it changes.
 */
public final class MapToolBar extends JToolBar {

    private final MapPanel map;
    private final JToggleButton gridlinesButton = new JToggleButton();
    private final JToggleButton labelsButton = new JToggleButton();

    MapToolBar(MapPanel map) {
        this.map = map;
        setFloatable(false);
        setRollover(true);

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
        labelsButton.setIcon(icon(mode == NodeLabelMode.AKA ? FontAwesomeSolid.FONT : FontAwesomeSolid.TAG));
        labelsButton.setSelected(mode.drawsLabels());
        labelsButton.setToolTipText(labelModeTooltip(mode));
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
