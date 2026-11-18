package com.kalix.ide;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * A model document's contextual view: the map with its own {@link MapToolBar} across the
 * top, as {@code DataVizView} mounts the data view's plot under its toolbar.
 */
public final class MapView extends JPanel {

    public MapView(MapPanel map) {
        super(new BorderLayout());
        add(new MapToolBar(map), BorderLayout.NORTH);
        add(map, BorderLayout.CENTER);
    }
}
