package com.kalix.ide;

import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import java.awt.Dimension;

/**
 * A model document's contextual view: the map, filling the view, with its {@link MapToolBar}
 * floating over the top-right corner. A layered pane, so the map's repaints never paint
 * over the toolbar.
 */
public final class MapView extends JLayeredPane {

    /** Gap between the toolbar and the map's top and right edges. */
    static final int TOOLBAR_INSET = 10;

    private final MapPanel map;
    private final MapToolBar toolBar;

    public MapView(MapPanel map) {
        this.map = map;
        this.toolBar = new MapToolBar(map);
        // The wheel over the toolbar still zooms or pans the map beneath it.
        toolBar.addMouseWheelListener(e -> map.dispatchEvent(SwingUtilities.convertMouseEvent(toolBar, e, map)));
        add(map, DEFAULT_LAYER);
        add(toolBar, PALETTE_LAYER);
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        map.setBounds(0, 0, width, getHeight());
        Dimension size = toolBar.getPreferredSize();
        toolBar.setBounds(Math.max(0, width - size.width - TOOLBAR_INSET), TOOLBAR_INSET, size.width, size.height);
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : map.getPreferredSize();
    }
}
