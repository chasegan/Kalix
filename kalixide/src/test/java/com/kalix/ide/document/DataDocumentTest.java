package com.kalix.ide.document;

import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.dataview.VirtualTextArea;

import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the DATA document kind: what a .csv tab bundles, and the editable-text
 * gate contract — below it the real editor is primary; above it the tab is a
 * read-only pair of virtual views and must refuse to look editable.
 */
class DataDocumentTest {

    private static File csvFile() throws IOException {
        Path file = Files.createTempFile("kalix-datadoc-test", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "Date,flow\n2020-01-01,1.5\n2020-01-02,2.5\n", StandardCharsets.UTF_8);
        return file.toFile();
    }

    @Test
    void csvFilesGetTheDataKind() {
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(new File("flows.csv")));
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(new File("results.res.csv")));
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(new File("UPPER.CSV")));
        assertEquals(DocumentKind.MODEL, DocumentKind.forFile(new File("model.ini")));
        assertEquals(DocumentKind.MODEL, DocumentKind.forFile(null));
        assertEquals(DocumentKind.TEXT, DocumentKind.forFile(new File("notes.txt")));
    }

    @Test
    void belowTheGateTheEditorIsPrimaryWithATableBesideIt() throws IOException {
        KalixDocument doc = new KalixDocument(DocumentKind.DATA, csvFile());
        try {
            assertTrue(doc.isEditable(), "small data files stay editable text");
            assertSame(doc.getEditor(), doc.getPrimaryView());
            assertInstanceOf(DataViewPanel.class, doc.getContextView(),
                "the contextual view is the virtual table");
            assertNotNull(doc.getDataViewSession());
            assertFalse(doc.isModel());
            assertFalse(doc.isOptimisable());
        } finally {
            doc.dispose();
        }
    }

    @Test
    void aboveTheGateTheTabIsAReadOnlyVirtualPair() throws IOException {
        KalixDocument doc = new KalixDocument(DocumentKind.DATA, csvFile(), true); // gate seam
        try {
            assertFalse(doc.isEditable(),
                "save paths must refuse: there is no editor buffer to write");
            assertInstanceOf(JScrollPane.class, doc.getPrimaryView(),
                "primary content is the virtual text view, not the editor");
            assertInstanceOf(VirtualTextArea.class, doc.getPrimaryFocusComponent());
            assertInstanceOf(DataViewPanel.class, doc.getContextView());
        } finally {
            doc.dispose();
        }
    }

    @Test
    void dataDocumentsRequireABackingFile() {
        assertThrows(IllegalArgumentException.class,
            () -> new KalixDocument(DocumentKind.DATA, null));
    }

    @Test
    void theGateIgnoresSmallAndAbsentFiles() throws IOException {
        assertFalse(KalixDocument.exceedsEditableGate(null));
        assertFalse(KalixDocument.exceedsEditableGate(csvFile()), "a few bytes is under any sane gate");
    }
}
