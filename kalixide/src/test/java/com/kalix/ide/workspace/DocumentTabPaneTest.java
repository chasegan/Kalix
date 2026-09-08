package com.kalix.ide.workspace;

import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;
import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.document.TextDocument;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins tab↔document identity through the per-document root map (map-in-tab):
 * a model document's tab content is an editor|context composite, a text
 * document's is its bare editor, and resolution must work for both.
 */
class DocumentTabPaneTest {

    private static DocumentTabPane pane(DocumentManager dm) {
        return pane(dm, new ContextSplitCoordinator(420, false, (w, c) -> { }));
    }

    private static DocumentTabPane pane(DocumentManager dm, ContextSplitCoordinator coordinator) {
        return new DocumentTabPane(dm, doc -> { }, (files, invoker, x, y) -> { }, () -> null, coordinator);
    }

    @Test
    void tabsResolveThroughRootsNotEditors() {
        DocumentManager dm = new DocumentManager();
        DocumentTabPane pane = pane(dm);

        KalixDocument model = new ModelDocument();
        KalixDocument text = new TextDocument();
        dm.setActiveDocument(model);
        dm.setActiveDocument(text);

        assertEquals(2, pane.getTabbedPane().getTabCount());
        assertEquals(0, pane.indexOf(model));
        assertEquals(1, pane.indexOf(text));
        assertSame(model, pane.documentAt(0));
        assertSame(text, pane.documentAt(1));
    }

    @Test
    void closingRemovesTheRootMapping() {
        DocumentManager dm = new DocumentManager();
        DocumentTabPane pane = pane(dm);

        KalixDocument a = new ModelDocument();
        KalixDocument b = new TextDocument();
        dm.setActiveDocument(a);
        dm.setActiveDocument(b);
        dm.closeDocument(b);

        assertEquals(1, pane.getTabbedPane().getTabCount());
        assertEquals(-1, pane.indexOf(b), "closed document resolves to no tab");
        assertEquals(List.of(a), dm.getDocuments());
        assertSame(a, pane.documentAt(0));
    }

    @Test
    void modelTabsGetAnEditorContextCompositeTextTabsTheBareEditor() {
        DocumentManager dm = new DocumentManager();
        DocumentTabPane pane = pane(dm);

        KalixDocument model = new ModelDocument();
        KalixDocument text = new TextDocument();
        dm.setActiveDocument(model);
        dm.setActiveDocument(text);

        Component modelRoot = pane.getTabbedPane().getComponentAt(0);
        Component textRoot = pane.getTabbedPane().getComponentAt(1);
        assertInstanceOf(DocumentSplitView.class, modelRoot,
            "a document with a contextual view mounts an editor|context split");
        assertNotSame(model.getEditor(), modelRoot);
        assertSame(text.getEditor(), textRoot,
            "no contextual view -> the editor itself is the tab content");
    }

    @Test
    void toggleContextViewFlipsPersistsAndRelaysOutTheActiveSplit() {
        DocumentManager dm = new DocumentManager();
        boolean[] persistedCollapsed = new boolean[1];
        ContextSplitCoordinator coordinator = new ContextSplitCoordinator(420, false,
            (w, c) -> persistedCollapsed[0] = c);
        DocumentTabPane pane = pane(dm, coordinator);
        dm.setActiveDocument(new ModelDocument());

        DocumentSplitView root = (DocumentSplitView) pane.getTabbedPane().getComponentAt(0);
        root.setSize(800, 600);
        root.doLayout(); // realise the split so the toggle's immediate re-layout is observable

        pane.toggleContextView();
        assertTrue(pane.isContextViewCollapsed());
        assertTrue(persistedCollapsed[0]);
        assertEquals(0, root.getSplit().getDividerSize(), "active tab collapses immediately");

        pane.toggleContextView();
        assertFalse(pane.isContextViewCollapsed());
        assertFalse(persistedCollapsed[0]);
        assertEquals(800 - 420 - root.getSplit().getDividerSize(), root.getSplit().getDividerLocation(),
            "active tab expands immediately to the shared width");
    }
}
