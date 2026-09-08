package com.kalix.ide.workspace;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the split view's shared-divider behaviour headlessly: the divider is
 * landed during the view's own first layout — before the split's children get
 * bounds, so MapPanel's deferred zoom-to-fit sees the real width — measured from
 * the right edge; collapse hides the divider handle entirely; only the visible
 * view writes drags back; and hidden views catch up with the shared state when
 * revealed.
 */
class DocumentSplitViewTest {

    private static DocumentSplitView view(ContextSplitCoordinator coordinator) {
        return new DocumentSplitView(new JPanel(), new JPanel(), coordinator);
    }

    @Test
    void firstLayoutLandsTheSharedDividerMeasuredFromTheRightEdge() {
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(300, false, (w, c) -> { });
        DocumentSplitView view = view(coordinator);

        view.setSize(800, 600);
        view.doLayout();

        JSplitPane split = view.getSplit();
        assertEquals(800 - 300 - split.getDividerSize(), split.getDividerLocation());
    }

    @Test
    void collapsedStateAppliesAtFirstLayoutWithNoDividerHandle() {
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(300, true, (w, c) -> { });
        DocumentSplitView view = view(coordinator);

        view.setSize(800, 600);
        view.doLayout();

        JSplitPane split = view.getSplit();
        assertEquals(0, split.getDividerSize(), "collapsed: no draggable divider");
        assertEquals(800, split.getDividerLocation(), "divider parked at the right edge");
    }

    @Test
    void draggingTheDividerWritesTheWidthBackToTheCoordinator() {
        int[] persistedWidth = new int[1];
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(300, false,
            (w, c) -> persistedWidth[0] = w);
        DocumentSplitView view = view(coordinator);
        view.setSize(800, 600);
        view.doLayout(); // first layout installs the divider listener

        JSplitPane split = view.getSplit();
        split.setDividerLocation(500); // a user drag fires the same property change

        assertEquals(800 - 500 - split.getDividerSize(), coordinator.getWidth());
        assertEquals(coordinator.getWidth(), persistedWidth[0]);
    }

    @Test
    void hiddenViewsDoNotWriteStaleWidthsBack() {
        int[] persistedWidth = new int[1];
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(300, false,
            (w, c) -> persistedWidth[0] = w);
        DocumentSplitView view = view(coordinator);
        view.setSize(800, 600);
        view.doLayout();

        // A background tab: the tab pane hides non-selected content, but a window
        // resize still lays the hidden split out and moves its divider.
        view.setVisible(false);
        view.getSplit().setDividerLocation(600);

        assertEquals(300, coordinator.getWidth(),
            "a hidden split's stale divider must not clobber the shared width");
        assertEquals(0, persistedWidth[0], "nothing persisted");
    }

    @Test
    void revealingAViewReAppliesTheSharedState() throws Exception {
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(300, false, (w, c) -> { });
        DocumentSplitView view = view(coordinator);
        view.setSize(800, 600);
        view.doLayout();
        view.setVisible(false);

        coordinator.setWidth(250); // dragged in another tab while this one was hidden
        view.setVisible(true);     // tab switched back
        SwingUtilities.invokeAndWait(() -> { }); // COMPONENT_SHOWN dispatches via the event queue

        JSplitPane split = view.getSplit();
        assertEquals(800 - 250 - split.getDividerSize(), split.getDividerLocation(),
            "revealed view catches up with the shared width");
    }
}
