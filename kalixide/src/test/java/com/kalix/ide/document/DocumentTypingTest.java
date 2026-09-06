package com.kalix.ide.document;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the typed-document seam (map-in-tab stage 1): kind derivation from the
 * file, the null-content contract for non-model kinds, optimisability, and the
 * model-only registry filtering that keeps text tabs out of the Optimiser.
 */
class DocumentTypingTest {

    @Test
    void kindDerivesFromFile() {
        assertEquals(DocumentKind.MODEL, DocumentKind.forFile(null), "untitled = model");
        assertEquals(DocumentKind.MODEL, DocumentKind.forFile(new File("/x/model.ini")));
        assertEquals(DocumentKind.MODEL, DocumentKind.forFile(new File("/x/MODEL.INI")), "case-insensitive");
        assertEquals(DocumentKind.TEXT, DocumentKind.forFile(new File("/x/notes.txt")));
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(new File("/x/data.csv")),
            "csv is DATA since the data-file viewer (DataDocumentTest pins the bundle)");
    }

    @Test
    void modelDocumentsKeepTheFullBundle() {
        KalixDocument doc = new ModelDocument();
        assertTrue(doc.isModel());
        assertNotNull(doc.getModel());
        assertNotNull(doc.getMapPanel());
        assertNotNull(doc.getContextView(), "a model's contextual view is its map");
        assertSame(DocumentKind.MODEL, new ModelDocument().getKind(), "ModelDocument is the MODEL kind");
    }

    @Test
    void textDocumentsHaveEditorOnlyAndNullContext() {
        KalixDocument doc = new TextDocument();
        assertFalse(doc.isModel());
        assertNull(doc.getModel());
        assertNull(doc.getMapPanel());
        assertNull(doc.getContextView(), "no contextual view: the region collapses");
        doc.setText("just some text");
        doc.parseModelFromText(true); // must be a safe no-op, called by every open path
        assertEquals("just some text", doc.getText());
    }

    @Test
    void onlyModelDocumentsAreOptimisable() {
        KalixDocument text = new TextDocument();
        text.setFile(new File("/x/notes.txt"));
        assertFalse(text.isOptimisable(), "a text document is never an optimisation target");

        KalixDocument model = new ModelDocument();
        model.setFile(new File("/x/model.ini"));
        assertTrue(model.isOptimisable());
        assertFalse(new ModelDocument().isOptimisable(), "unsaved: no working dir");
    }

    @Test
    void workspaceViewListsAndActivatesModelsOnly() {
        DocumentManager dm = new DocumentManager();
        DocumentWorkspaceView view = new DocumentWorkspaceView(dm, () -> null);

        KalixDocument model = new ModelDocument();
        KalixDocument text = new TextDocument();
        dm.addDocument(model);
        dm.addDocument(text);

        List<? extends OpenModel> models = view.openModels();
        assertEquals(1, models.size());
        assertSame(model, models.get(0));

        dm.setActiveDocument(text);
        assertNull(view.activeModel(), "an active text tab is not an active model");
        dm.setActiveDocument(model);
        assertSame(model, view.activeModel());
    }
}
