package com.kalix.ide;

import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.rendering.NodeLabelMode;
import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import java.util.function.Consumer;

/**
 * The toolbar across the top of a {@link MapView}: display toggles for the one map below it.
 * The buttons show that map's state, read back whenever it changes, so every map's toolbar
 * stays in step when a setting is applied to all of them.
 */
public final class MapToolBar extends JToolBar {

    private final MapPanel map;
    private final JToggleButton gridlinesButton = new JToggleButton();
    private final JToggleButton labelsButton = new JToggleButton();
    private Consumer<Boolean> gridlinesAction;
    private Consumer<NodeLabelMode> labelModeAction;

    MapToolBar(MapPanel map) {
        this.map = map;
        setFloatable(false);
        setRollover(true);

        configure(gridlinesButton, "Toggle Gridlines");
        gridlinesButton.addActionListener(e -> {
            requireWired();
            gridlinesAction.accept(!map.isShowGridlines());
            syncSelection(); // the click toggled the button itself; the map is the truth
        });

        // One button cycles the three label modes (names, aka, hidden): selected while
        // labels are drawn, its icon says which text.
        configure(labelsButton, "Node labels");
        labelsButton.addActionListener(e -> {
            requireWired();
            labelModeAction.accept(map.getLabelMode().next());
            syncSelection();
        });

        add(gridlinesButton);
        add(labelsButton);

        map.addPropertyChangeListener(MapPanel.SHOW_GRIDLINES_PROPERTY, e -> refreshGridlines());
        map.addPropertyChangeListener(MapPanel.LABEL_MODE_PROPERTY, e -> refreshLabels());
        refreshIcons();
    }

    /** Routes the toggles through {@code gridlines} and {@code labelMode}; a click before this throws. */
    void setActions(Consumer<Boolean> gridlines, Consumer<NodeLabelMode> labelMode) {
        this.gridlinesAction = gridlines;
        this.labelModeAction = labelMode;
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

    private void requireWired() {
        if (gridlinesAction == null || labelModeAction == null) {
            throw new IllegalStateException("Map toolbar used before its display actions were set");
        }
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

    private void syncSelection() {
        gridlinesButton.setSelected(map.isShowGridlines());
        labelsButton.setSelected(map.getLabelMode().drawsLabels());
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
