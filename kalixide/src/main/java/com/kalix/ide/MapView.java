package com.kalix.ide;

import com.kalix.ide.rendering.NodeLabelMode;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.function.Consumer;

/**
 * A model document's contextual view: the map with its own {@link MapToolBar} across the
 * top, as {@code DataVizView} mounts the data view's plot under its toolbar.
 */
public final class MapView extends JPanel {

    private final MapToolBar toolBar;

    public MapView(MapPanel map) {
        super(new BorderLayout());
        toolBar = new MapToolBar(map);
        add(toolBar, BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
    }

    /**
     * Routes the toolbar's toggles through the application, which applies them to every
     * map and saves them. A toggle clicked before this is called throws.
     */
    public void setDisplayActions(Consumer<Boolean> gridlines, Consumer<NodeLabelMode> labelMode) {
        toolBar.setActions(gridlines, labelMode);
    }
}
