package com.kalix.ide.workspace;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * The main work area: <code>[ project tree | document tabs ]</code>, one
 * {@link JSplitPane}. The tab strip runs the full remaining width and is the
 * always-present anchor; the tree (left) can be resized by dragging the divider and
 * collapsed. Each tab carries its own editor|contextual-view split — see
 * {@link DocumentSplitView} and {@code docs/multi-document-architecture.md} (Addendum).
 *
 * <p>Resize behaviour: when the window grows, the tab area absorbs the extra space
 * while the tree keeps its width — the VSCode-like feel. The tree width and collapsed
 * state are reported via {@link LayoutChangeListener} so the host can persist them;
 * the persisted values are passed back in through the constructor and applied once
 * the panel is realised.
 */
public class WorkspacePanel extends JPanel {

    /** Notified whenever the tree width or collapsed state changes, so it can be persisted. */
    public interface LayoutChangeListener {
        void onLayoutChanged(int treeWidth, boolean treeCollapsed);
    }

    private final JSplitPane split; // [ tree | centre ]
    private final int defaultDividerSize;

    private boolean treeCollapsed;
    private int treeWidth; // remembered expanded width of the tree region

    private boolean initialLayoutApplied = false;
    private boolean applyingLayout = false; // suppress divider-listener feedback while we set the divider
    private LayoutChangeListener layoutChangeListener;

    /**
     * @param tree          the left region component (project tree)
     * @param centre        the centre region component (the document tab strip)
     * @param treeWidth     initial expanded width of the tree region
     * @param treeCollapsed whether the tree region starts collapsed
     */
    public WorkspacePanel(JComponent tree, JComponent centre,
                          int treeWidth, boolean treeCollapsed) {
        super(new BorderLayout());
        this.treeWidth = treeWidth;
        this.treeCollapsed = treeCollapsed;

        // Allow the tree to collapse fully (divider all the way to the edge).
        tree.setMinimumSize(new Dimension(0, 0));
        centre.setMinimumSize(new Dimension(0, 0));

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tree, centre);
        split.setResizeWeight(0.0); // tree keeps its width; the rest absorbs resize
        split.setContinuousLayout(true);
        split.setBorder(null);

        this.defaultDividerSize = split.getDividerSize();

        add(split, BorderLayout.CENTER);

        // Apply the persisted layout once the panel actually has a size, then start
        // listening for user-driven divider drags.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!initialLayoutApplied && getWidth() > 0 && getHeight() > 0) {
                    initialLayoutApplied = true;
                    applyLayout();
                    installDividerListener();
                }
            }
        });
    }

    /** Pushes the current width/collapsed state onto the split pane. */
    private void applyLayout() {
        applyingLayout = true;
        try {
            if (treeCollapsed) {
                split.setDividerSize(0);
                split.setDividerLocation(0);
            } else {
                split.setDividerSize(defaultDividerSize);
                split.setDividerLocation(treeWidth);
            }
        } finally {
            applyingLayout = false;
        }
    }

    private void installDividerListener() {
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (applyingLayout || treeCollapsed) {
                return;
            }
            treeWidth = split.getDividerLocation();
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
            int w = split.getDividerLocation();
            if (w > 0) {
                treeWidth = w;
            }
        }
        treeCollapsed = collapsed;
        applyLayout();
        fireLayoutChanged();
    }

    public boolean isTreeCollapsed() {
        return treeCollapsed;
    }

    public void setLayoutChangeListener(LayoutChangeListener listener) {
        this.layoutChangeListener = listener;
    }

    private void fireLayoutChanged() {
        if (layoutChangeListener != null) {
            layoutChangeListener.onLayoutChanged(treeWidth, treeCollapsed);
        }
    }
}
