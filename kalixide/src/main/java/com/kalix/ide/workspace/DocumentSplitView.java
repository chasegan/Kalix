package com.kalix.ide.workspace;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * One tab's content for a document that has a contextual view: an
 * <code>[ editor | contextual view ]</code> split, mirroring the resize behaviour the
 * region had as a shared panel — the editor absorbs window resizes, the contextual
 * view keeps its width, and the region collapses to nothing (divider hidden).
 *
 * <p>Every such document has its own instance mounted in its tab, but there is one
 * shared notion of the region's width and collapsed state
 * ({@link ContextSplitCoordinator}). The visible instance writes divider drags back
 * to the coordinator; hidden instances re-apply the shared state when next shown, so
 * all tabs agree without any broadcast.
 *
 * <p>First-layout ordering matters here: the shared divider is landed in
 * {@link #doLayout()} <em>before</em> the split lays out its children, so the
 * contextual view's first non-zero bounds are already at the shared width.
 * {@code MapPanel} completes a deferred zoom-to-fit synchronously in
 * {@code setBounds}; applying the divider only after a default first layout would
 * complete that fit at a wrong, transient width — and flash the region open when it
 * is restored collapsed.
 */
public class DocumentSplitView extends JPanel {

    private final JSplitPane split;
    private final ContextSplitCoordinator coordinator;
    private final int defaultDividerSize;

    private boolean initialLayoutApplied = false;
    private boolean applyingLayout = false; // suppress divider-listener feedback while we set the divider

    public DocumentSplitView(Component editor, Component contextView, ContextSplitCoordinator coordinator) {
        super(new BorderLayout());
        this.coordinator = coordinator;

        // Allow the region to collapse fully (divider all the way to the edge).
        editor.setMinimumSize(new Dimension(0, 0));
        contextView.setMinimumSize(new Dimension(0, 0));

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editor, contextView);
        split.setResizeWeight(1.0); // editor absorbs resize; the contextual view keeps its width
        split.setContinuousLayout(true);
        split.setBorder(null);
        this.defaultDividerSize = split.getDividerSize();

        add(split, BorderLayout.CENTER);

        // The tab pane hides and shows tab contents on switch; a hidden view may have
        // missed coordinator changes (a drag in another tab, a collapse toggle), so
        // catch up with the shared state whenever this view is revealed.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                if (initialLayoutApplied) {
                    applySharedLayout();
                }
            }
        });
    }

    @Override
    public void doLayout() {
        if (!initialLayoutApplied && getWidth() > 0) {
            initialLayoutApplied = true;
            applySharedLayout();
            installDividerListener();
        }
        super.doLayout();
    }

    /**
     * Pushes the coordinator's width/collapsed state onto the split pane. Collapse is
     * "divider at the right edge, divider handle hidden" — the editor takes the whole
     * width and nothing of the contextual view remains visible.
     */
    void applySharedLayout() {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        applyingLayout = true;
        try {
            if (coordinator.isCollapsed()) {
                split.setDividerSize(0);
                split.setDividerLocation(width);
            } else {
                split.setDividerSize(defaultDividerSize);
                split.setDividerLocation(Math.max(0, width - coordinator.width() - defaultDividerSize));
            }
        } finally {
            applyingLayout = false;
        }
    }

    private void installDividerListener() {
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (applyingLayout || coordinator.isCollapsed()) {
                return;
            }
            // Width measured from the right edge, matching resizeWeight 1.0 (a window
            // resize moves the divider but leaves this width unchanged — a no-op write).
            coordinator.setWidth(Math.max(0, getWidth() - split.getDividerLocation() - split.getDividerSize()));
        });
    }

    /** The underlying split pane — package-private, for tests. */
    JSplitPane getSplit() {
        return split;
    }
}
