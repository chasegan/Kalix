package com.kalix.ide.editor;

import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;
import com.kalix.ide.linter.events.ValidationEventManager;
import com.kalix.ide.linter.ui.HoverTipSupplier;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.text.AbstractDocument;
import java.awt.AWTEvent;
import java.awt.Toolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the editor/linter disposal chain (review #21): every listener a
 * component attaches to something longer-lived (the global Toolkit, the shared
 * text area) must be detached by its dispose(), and closing a document must run
 * the chain. These tests construct Swing components, so they are display-gated
 * like {@code DocumentManagerTest}.
 */
class EditorDisposalTest {

    private static int awtMouseListenerCount() {
        return Toolkit.getDefaultToolkit().getAWTEventListeners(AWTEvent.MOUSE_EVENT_MASK).length;
    }

    @Test
    void editorDisposeRemovesTheGlobalToolkitListener() {
        int before = awtMouseListenerCount();
        EnhancedTextEditor editor = new EnhancedTextEditor();
        assertEquals(before + 1, awtMouseListenerCount(), "editor registers one global listener");

        editor.dispose();
        assertEquals(before, awtMouseListenerCount(), "dispose must remove the global listener");

        editor.dispose(); // idempotent
        assertEquals(before, awtMouseListenerCount());
    }

    @Test
    void closingADocumentDisposesItsEditor() {
        int before = awtMouseListenerCount();
        DocumentManager dm = new DocumentManager();
        KalixDocument document = new KalixDocument();
        dm.addDocument(document);
        assertEquals(before + 1, awtMouseListenerCount());

        dm.closeDocument(document);
        assertEquals(before, awtMouseListenerCount(),
            "closeDocument must dispose the document's editor graph");
    }

    @Test
    void validationEventManagerDetachesItsDocumentListener() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        AbstractDocument document = (AbstractDocument) textArea.getDocument();
        int before = document.getDocumentListeners().length;

        ValidationEventManager manager = new ValidationEventManager(textArea, () -> { });
        assertEquals(before + 1, document.getDocumentListeners().length);

        manager.dispose();
        assertEquals(before, document.getDocumentListeners().length,
            "dispose must detach the document listener (orphans revalidate against a disposed orchestrator)");
    }

    @Test
    void hoverTipSupplierDetachesEverythingItInstalled() {
        RSyntaxTextArea textArea = new RSyntaxTextArea();
        int mouseBefore = textArea.getMouseListeners().length;

        HoverTipSupplier supplier = HoverTipSupplier.install(textArea);
        assertEquals(supplier, textArea.getToolTipSupplier());
        // Two listeners: the dismiss-delay idiom's, and ToolTipManager's own on registration.
        assertEquals(mouseBefore + 2, textArea.getMouseListeners().length);

        supplier.uninstall();
        assertEquals(mouseBefore, textArea.getMouseListeners().length);
        assertEquals(null, textArea.getToolTipSupplier());

        supplier.uninstall(); // idempotent
        assertEquals(mouseBefore, textArea.getMouseListeners().length);
    }
}
