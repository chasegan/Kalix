package com.kalix.ide.workspace;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JSplitPane;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the split view's shared-divider behaviour headlessly: the divider is
 * landed during the view's own first layout — before the split's children get
 * bounds, so MapPanel's deferred zoom-to-fit sees the real width — measured from
 * the right edge, and collapse hides the divider handle entirely.
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

        assertEquals(800 - 500 - split.getDividerSize(), coordinator.width());
        assertEquals(coordinator.width(), persistedWidth[0]);
    }
}
