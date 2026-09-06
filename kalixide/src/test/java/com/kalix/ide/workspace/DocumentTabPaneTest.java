package com.kalix.ide.workspace;

import com.kalix.ide.document.DocumentKind;
import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins tab↔document identity through the per-document root map (map-in-tab
 * stage 1): resolution must not depend on the tab content being the editor
 * component, so stage 2 can wrap it in an editor|context composite.
 */
class DocumentTabPaneTest {

    private static DocumentTabPane pane(DocumentManager dm) {
        return new DocumentTabPane(dm, doc -> { }, (files, invoker, x, y) -> { }, () -> null);
    }

    @Test
    void tabsResolveThroughRootsNotEditors() {
        DocumentManager dm = new DocumentManager();
        DocumentTabPane pane = pane(dm);

        KalixDocument model = new KalixDocument(DocumentKind.MODEL);
        KalixDocument text = new KalixDocument(DocumentKind.TEXT);
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

        KalixDocument a = new KalixDocument(DocumentKind.MODEL);
        KalixDocument b = new KalixDocument(DocumentKind.TEXT);
        dm.setActiveDocument(a);
        dm.setActiveDocument(b);
        dm.closeDocument(b);

        assertEquals(1, pane.getTabbedPane().getTabCount());
        assertEquals(-1, pane.indexOf(b), "closed document resolves to no tab");
        assertEquals(List.of(a), dm.getDocuments());
        assertSame(a, pane.documentAt(0));
    }
}
