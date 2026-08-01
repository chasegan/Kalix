package com.kalix.ide.document;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DocumentWorkspaceView} — the adapter that lets an auxiliary
 * window enumerate the open models instead of sampling whichever one is active.
 *
 * <p>Uses real {@link KalixDocument}s so the adapter is exercised against the same object
 * graph it sees in the running IDE.</p>
 */
class DocumentWorkspaceViewTest {

    @Test
    @DisplayName("An empty workspace offers nothing and has no active model")
    void testEmptyWorkspace() {
        DocumentManager manager = new DocumentManager();
        DocumentWorkspaceView sources = new DocumentWorkspaceView(manager, () -> null);

        assertTrue(sources.openModels().isEmpty());
        assertNull(sources.activeModel());
    }

    @Test
    @DisplayName("Every open model is offered, in tab order")
    void testAvailableTracksTheDocumentSet() {
        DocumentManager manager = new DocumentManager();
        DocumentWorkspaceView sources = new DocumentWorkspaceView(manager, () -> null);

        KalixDocument first = new KalixDocument();
        KalixDocument second = new KalixDocument();
        manager.addDocument(first);
        manager.addDocument(second);

        assertEquals(2, sources.openModels().size());
        assertSame(first, sources.openModels().get(0));
        assertSame(second, sources.openModels().get(1));

        manager.closeDocument(first);
        assertEquals(1, sources.openModels().size());
        assertSame(second, sources.openModels().get(0));
    }

    @Test
    @DisplayName("The active model tracks the main window's selection")
    void testActiveFollowsTheActiveDocument() {
        DocumentManager manager = new DocumentManager();
        DocumentWorkspaceView sources = new DocumentWorkspaceView(manager, () -> null);

        KalixDocument first = new KalixDocument();
        KalixDocument second = new KalixDocument();
        manager.setActiveDocument(first);
        assertSame(first, sources.activeModel());

        manager.setActiveDocument(second);
        assertSame(second, sources.activeModel());
    }

    @Test
    @DisplayName("Opening, activating and closing each notify listeners")
    void testChangeListenerFiresForEveryDocumentSetChange() {
        DocumentManager manager = new DocumentManager();
        DocumentWorkspaceView sources = new DocumentWorkspaceView(manager, () -> null);

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
    @DisplayName("A document is an OpenModel, and is optimisable only once saved")
    void testDocumentSatisfiesOpenModel() {
        KalixDocument document = new KalixDocument();

        OpenModel source = document;
        assertEquals("Untitled", source.getDisplayName());
        assertNull(source.getWorkingDirectory());
        assertFalse(source.isOptimisable(), "an unsaved model has no folder for data paths");

        document.setFile(new File(System.getProperty("java.io.tmpdir"), "catchment.ini"));
        assertEquals("catchment.ini", source.getDisplayName());
        assertNotNull(source.getWorkingDirectory());
        assertTrue(source.isOptimisable());
    }

    @Test
    @DisplayName("Resolving an open model returns that exact document")
    void testResolveMatchesOnIdentity() {
        DocumentManager manager = new DocumentManager();
        KalixDocument first = new KalixDocument();
        KalixDocument second = new KalixDocument();
        manager.addDocument(first);
        manager.addDocument(second);

        assertSame(first, manager.resolve(first));
        assertSame(second, manager.resolve(second));
    }

    @Test
    @DisplayName("A closed and reopened file resolves to the new document")
    void testResolveFallsBackToTheFile() {
        DocumentManager manager = new DocumentManager();
        File file = new File(System.getProperty("java.io.tmpdir"), "catchment.ini");

        KalixDocument original = new KalixDocument();
        original.setFile(file);
        manager.addDocument(original);

        // The user closes the tab and opens the same file again: a *new* document object
        // backed by the same file. An optimisation still holding the old handle must copy
        // its result back into the reopened tab, not report "not open".
        manager.closeDocument(original);
        KalixDocument reopened = new KalixDocument();
        reopened.setFile(file);
        manager.addDocument(reopened);

        assertSame(reopened, manager.resolve(original));
    }

    @Test
    @DisplayName("A model whose file is gone from the workspace resolves to nothing")
    void testResolveReturnsNullWhenNotOpen() {
        DocumentManager manager = new DocumentManager();
        KalixDocument document = new KalixDocument();
        document.setFile(new File(System.getProperty("java.io.tmpdir"), "gone.ini"));
        manager.addDocument(document);
        manager.closeDocument(document);

        assertNull(manager.resolve(document));
        assertNull(manager.resolve(null));
    }

    @Test
    @DisplayName("An unsaved model never resolves by file")
    void testResolveDoesNotMatchUnsavedModelsByFile() {
        DocumentManager manager = new DocumentManager();
        KalixDocument open = new KalixDocument();
        manager.addDocument(open);

        // Two unsaved documents both have a null file; falling back on that would make
        // any closed untitled document resolve to an unrelated open one.
        KalixDocument otherUnsaved = new KalixDocument();
        assertNull(manager.resolve(otherUnsaved));
    }

    @Test
    @DisplayName("A null listener is ignored rather than failing on the next change")
    void testNullListenerIsIgnored() {
        DocumentManager manager = new DocumentManager();
        DocumentWorkspaceView sources = new DocumentWorkspaceView(manager, () -> null);

        sources.addChangeListener(null);

        assertDoesNotThrow(() -> manager.addDocument(new KalixDocument()));
    }
}
