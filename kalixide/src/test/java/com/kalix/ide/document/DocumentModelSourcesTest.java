package com.kalix.ide.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DocumentModelSources} — the adapter that lets an auxiliary
 * window enumerate the open models instead of sampling whichever one is active.
 *
 * <p>Uses real {@link KalixDocument}s (they construct headless) so the adapter is
 * exercised against the same object graph it sees in the running IDE.</p>
 */
class DocumentModelSourcesTest {

    @Test
    @DisplayName("An empty workspace offers nothing and has no active model")
    void testEmptyWorkspace() {
        DocumentManager manager = new DocumentManager();
        DocumentModelSources sources = new DocumentModelSources(manager, () -> null);

        assertTrue(sources.available().isEmpty());
        assertNull(sources.active());
    }

    @Test
    @DisplayName("Every open model is offered, in tab order")
    void testAvailableTracksTheDocumentSet() {
        DocumentManager manager = new DocumentManager();
        DocumentModelSources sources = new DocumentModelSources(manager, () -> null);

        KalixDocument first = new KalixDocument();
        KalixDocument second = new KalixDocument();
        manager.addDocument(first);
        manager.addDocument(second);

        assertEquals(2, sources.available().size());
        assertSame(first, sources.available().get(0));
        assertSame(second, sources.available().get(1));

        manager.closeDocument(first);
        assertEquals(1, sources.available().size());
        assertSame(second, sources.available().get(0));
    }

    @Test
    @DisplayName("The active model tracks the main window's selection")
    void testActiveFollowsTheActiveDocument() {
        DocumentManager manager = new DocumentManager();
        DocumentModelSources sources = new DocumentModelSources(manager, () -> null);

        KalixDocument first = new KalixDocument();
        KalixDocument second = new KalixDocument();
        manager.setActiveDocument(first);
        assertSame(first, sources.active());

        manager.setActiveDocument(second);
        assertSame(second, sources.active());
    }

    @Test
    @DisplayName("Opening, activating and closing each notify listeners")
    void testChangeListenerFiresForEveryDocumentSetChange() {
        DocumentManager manager = new DocumentManager();
        DocumentModelSources sources = new DocumentModelSources(manager, () -> null);

        AtomicInteger notifications = new AtomicInteger();
        sources.addChangeListener(notifications::incrementAndGet);
        assertEquals(0, notifications.get());

        // Missing any of these would leave the Optimiser's model list stale — offering
        // a closed model, or omitting one the user just opened.
        KalixDocument first = new KalixDocument();
        manager.addDocument(first);
        int afterOpen = notifications.get();
        assertTrue(afterOpen > 0, "opening a document must notify");

        manager.setActiveDocument(first);
        int afterActivate = notifications.get();
        assertTrue(afterActivate > afterOpen, "activating a document must notify");

        manager.closeDocument(first);
        assertTrue(notifications.get() > afterActivate, "closing a document must notify");
    }

    @Test
    @DisplayName("A document is a ModelSource, and is optimisable only once saved")
    void testDocumentSatisfiesModelSource() {
        KalixDocument document = new KalixDocument();

        ModelSource source = document;
        assertEquals("Untitled", source.getDisplayName());
        assertNull(source.getWorkingDirectory());
        assertFalse(source.isOptimisable(), "an unsaved model has no folder for data paths");

        document.setFile(new File(System.getProperty("java.io.tmpdir"), "catchment.ini"));
        assertEquals("catchment.ini", source.getDisplayName());
        assertNotNull(source.getWorkingDirectory());
        assertTrue(source.isOptimisable());
    }

    @Test
    @DisplayName("A null listener is ignored rather than failing on the next change")
    void testNullListenerIsIgnored() {
        DocumentManager manager = new DocumentManager();
        DocumentModelSources sources = new DocumentModelSources(manager, () -> null);

        sources.addChangeListener(null);

        assertDoesNotThrow(() -> manager.addDocument(new KalixDocument()));
    }
}
