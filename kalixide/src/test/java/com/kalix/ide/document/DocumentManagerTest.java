package com.kalix.ide.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Verifies the active-document spine: open/close events, active-document tracking,
 * neighbour selection on close, and find-by-file. These are the behaviours the tab
 * strip, contextual view and host all rely on.
 *
 * <p>{@link KalixDocument} constructs its editor and map (Swing components), so these
 * tests require a graphics environment and are skipped when headless (e.g. CI without a
 * display).
 */
class DocumentManagerTest {

    @BeforeEach
    void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
    }

    private static KalixDocument doc() {
        return new KalixDocument();
    }

    @Test
    void addDocumentFiresOpenedAndPreservesOrder() {
        DocumentManager dm = new DocumentManager();
        List<KalixDocument> opened = new ArrayList<>();
        dm.addDocumentOpenedListener(opened::add);

        KalixDocument a = doc();
        KalixDocument b = doc();
        dm.addDocument(a);
        dm.addDocument(b);
        dm.addDocument(a); // duplicate is ignored

        assertEquals(List.of(a, b), opened);
        assertEquals(List.of(a, b), dm.getDocuments());
    }

    @Test
    void setActiveDocumentFiresOnceAndTracks() {
        DocumentManager dm = new DocumentManager();
        List<KalixDocument> activated = new ArrayList<>();
        dm.addActiveDocumentChangeListener(activated::add);

        KalixDocument a = doc();
        dm.setActiveDocument(a);
        dm.setActiveDocument(a); // no-op, already active

        assertSame(a, dm.getActiveDocument());
        assertEquals(List.of(a), activated);
    }

    @Test
    void findByFileMatchesBackingFile() {
        DocumentManager dm = new DocumentManager();
        KalixDocument a = doc();
        File file = new File("model.ini").getAbsoluteFile();
        a.setFile(file);
        dm.addDocument(a);

        assertSame(a, dm.findByFile(file));
        assertNull(dm.findByFile(new File("other.ini").getAbsoluteFile()));
        assertNull(dm.findByFile(null));
    }

    @Test
    void closingActiveDocumentActivatesNeighbour() {
        DocumentManager dm = new DocumentManager();
        KalixDocument a = doc();
        KalixDocument b = doc();
        KalixDocument c = doc();
        dm.addDocument(a);
        dm.addDocument(b);
        dm.addDocument(c);
        dm.setActiveDocument(b);

        List<KalixDocument> closed = new ArrayList<>();
        dm.addDocumentClosedListener(closed::add);

        dm.closeDocument(b);

        assertEquals(List.of(b), closed);
        assertEquals(List.of(a, c), dm.getDocuments());
        // The neighbour at the same index (c) becomes active.
        assertSame(c, dm.getActiveDocument());
    }

    @Test
    void closingInactiveDocumentLeavesActiveUnchanged() {
        DocumentManager dm = new DocumentManager();
        KalixDocument a = doc();
        KalixDocument b = doc();
        dm.addDocument(a);
        dm.addDocument(b);
        dm.setActiveDocument(a);

        dm.closeDocument(b);

        assertSame(a, dm.getActiveDocument());
        assertEquals(List.of(a), dm.getDocuments());
    }

    @Test
    void closingLastDocumentClearsActive() {
        DocumentManager dm = new DocumentManager();
        KalixDocument a = doc();
        dm.setActiveDocument(a);

        List<KalixDocument> activated = new ArrayList<>();
        dm.addActiveDocumentChangeListener(activated::add);

        dm.closeDocument(a);

        assertTrue(dm.getDocuments().isEmpty());
        assertNull(dm.getActiveDocument());
        // Active-change fired once with the new (null) active document.
        assertEquals(1, activated.size());
        assertNull(activated.get(0));
    }
}
