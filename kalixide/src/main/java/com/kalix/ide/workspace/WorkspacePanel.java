package com.kalix.ide.workspace;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * The main three-region work area: <code>[ project tree | editor | contextual view ]</code>,
 * built from two nested {@link JSplitPane}s. The editor occupies the centre and is the
 * always-present anchor; the tree (left) and contextual view (right, the map) can each be
 * resized by dragging their divider and collapsed independently.
 *
 * <p>This replaces the old docking system with a purpose-built layout
 * (see {@code docs/multi-document-architecture.md}, Phase 2).
 *
 * <p>Resize behaviour: when the window grows, the editor absorbs the extra space while the
 * tree and map keep their widths — the VSCode-like feel. Region widths and collapsed states
 * are reported via {@link LayoutChangeListener} so the host can persist them; the persisted
 * values are passed back in through the constructor and applied once the panel is realised.
 */
public class WorkspacePanel extends JPanel {

    /** Notified whenever a region width or collapsed state changes, so it can be persisted. */
    public interface LayoutChangeListener {
        void onLayoutChanged(int treeWidth, int mapWidth, boolean treeCollapsed, boolean mapCollapsed);
    }

    private final JSplitPane outerSplit; // [ tree | innerSplit ]
    private final JSplitPane innerSplit; // [ editor | map ]
    private final int defaultDividerSize;

    private boolean treeCollapsed;
    private boolean mapCollapsed;
    private int treeWidth; // remembered expanded width of the tree region
    private int mapWidth;  // remembered expanded width of the map region

    private boolean initialLayoutApplied = false;
    private boolean applyingLayout = false; // suppress divider-listener feedback while we set dividers
    private LayoutChangeListener layoutChangeListener;

    /**
     * @param tree          the left region component (project tree)
     * @param editor        the centre region component (active document's editor)
     * @param map           the right region component (contextual view / map)
     * @param treeWidth     initial expanded width of the tree region
     * @param mapWidth      initial expanded width of the map region
     * @param treeCollapsed whether the tree region starts collapsed
     * @param mapCollapsed  whether the map region starts collapsed
     */
    public WorkspacePanel(JComponent tree, JComponent editor, JComponent map,
                          int treeWidth, int mapWidth,
                          boolean treeCollapsed, boolean mapCollapsed) {
        super(new BorderLayout());
        this.treeWidth = treeWidth;
        this.mapWidth = mapWidth;
        this.treeCollapsed = treeCollapsed;
        this.mapCollapsed = mapCollapsed;

        // Allow the side regions to collapse fully (divider all the way to the edge).
        tree.setMinimumSize(new Dimension(0, 0));
        editor.setMinimumSize(new Dimension(0, 0));
        map.setMinimumSize(new Dimension(0, 0));

        innerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, map);
        innerSplit.setResizeWeight(1.0); // editor absorbs resize; map keeps its width
        innerSplit.setContinuousLayout(true);
        innerSplit.setBorder(null);

        outerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tree, innerSplit);
        outerSplit.setResizeWeight(0.0); // tree keeps its width; the rest absorbs resize
        outerSplit.setContinuousLayout(true);
        outerSplit.setBorder(null);

        this.defaultDividerSize = outerSplit.getDividerSize();

        add(outerSplit, BorderLayout.CENTER);

        // Apply the persisted layout once the panel actually has a size, then start
        // listening for user-driven divider drags.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!initialLayoutApplied && getWidth() > 0 && getHeight() > 0) {
                    initialLayoutApplied = true;
                    applyLayout();
                    installDividerListeners();
                }
            }
        });
    }

    /**
     * Pushes the current width/collapsed state onto the two split panes.
     */
    private void applyLayout() {
        applyingLayout = true;
        try {
            // Outer (tree) divider.
            if (treeCollapsed) {
                outerSplit.setDividerSize(0);
                outerSplit.setDividerLocation(0);
            } else {
                outerSplit.setDividerSize(defaultDividerSize);
                outerSplit.setDividerLocation(treeWidth);
            }
            outerSplit.validate(); // force innerSplit to take up its new bounds

            // Inner (map) divider, measured from the right edge.
            int innerW = innerSplit.getWidth();
            if (innerW <= 0) {
                innerW = getWidth();
            }
            if (mapCollapsed) {
                innerSplit.setDividerSize(0);
                innerSplit.setDividerLocation(innerW);
            } else {
                innerSplit.setDividerSize(defaultDividerSize);
                innerSplit.setDividerLocation(Math.max(0, innerW - mapWidth - defaultDividerSize));
            }
        } finally {
            applyingLayout = false;
        }
    }

    private void installDividerListeners() {
        outerSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (applyingLayout || treeCollapsed) {
                return;
            }
            treeWidth = outerSplit.getDividerLocation();
            fireLayoutChanged();
        });
        innerSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (applyingLayout || mapCollapsed) {
                return;
            }
            int innerW = innerSplit.getWidth();
            mapWidth = Math.max(0, innerW - innerSplit.getDividerLocation() - innerSplit.getDividerSize());
            fireLayoutChanged();
        });
    }

    // --- Collapse / expand ---

    public void setTreeCollapsed(boolean collapsed) {
        if (collapsed == treeCollapsed) {
            return;
        }
        if (collapsed) {
            // Remember the current width so we can restore it on expand.
            int w = outerSplit.getDividerLocation();
            if (w > 0) {
                treeWidth = w;
            }
        }
        treeCollapsed = collapsed;
        applyLayout();
        fireLayoutChanged();
    }

    public void setMapCollapsed(boolean collapsed) {
        if (collapsed == mapCollapsed) {
            return;
        }
        if (collapsed) {
            int w = innerSplit.getWidth() - innerSplit.getDividerLocation() - innerSplit.getDividerSize();
            if (w > 0) {
                mapWidth = w;
            }
        }
        mapCollapsed = collapsed;
        applyLayout();
        fireLayoutChanged();
    }

    public void toggleTree() {
        setTreeCollapsed(!treeCollapsed);
    }

    public void toggleMap() {
        setMapCollapsed(!mapCollapsed);
    }

    public boolean isTreeCollapsed() {
        return treeCollapsed;
    }

    public boolean isMapCollapsed() {
        return mapCollapsed;
    }

    public void setLayoutChangeListener(LayoutChangeListener listener) {
        this.layoutChangeListener = listener;
    }

    private void fireLayoutChanged() {
        if (layoutChangeListener != null) {
            layoutChangeListener.onLayoutChanged(treeWidth, mapWidth, treeCollapsed, mapCollapsed);
        }
    }
}
