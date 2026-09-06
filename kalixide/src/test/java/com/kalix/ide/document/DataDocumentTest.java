package com.kalix.ide.document;

import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.dataview.DataViewSession;
import com.kalix.ide.dataview.VirtualTextArea;

import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        DataDocument doc = new DataDocument(csvFile());
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
        DataDocument doc = new DataDocument(csvFile(), true); // gate seam
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
    void aFailedSessionAboveTheGateStaysReadOnly() {
        // The blocker scenario: session open fails (missing file / dropped share).
        // The tab must NOT degrade to an editable empty buffer over a real file.
        DataDocument doc = new DataDocument(
            new File("/nonexistent/kalix-test-missing.csv"), true);
        try {
            assertFalse(doc.isEditable(), "no session + empty buffer must never be saveable");
            assertSame(doc.getEditor(), doc.getPrimaryView(),
                "virtual text unavailable: the (unsaveable) editor is the fallback");
            assertNull(doc.getContextView());
        } finally {
            doc.dispose();
        }
    }

    @Test
    void savingRebuildsTheDataSessionFromTheNewBytes() throws IOException {
        File file = csvFile(); // header + 2 data rows
        DataDocument doc = new DataDocument(file);
        try {
            await("initial index", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != null && s.isIndexingComplete();
            });
            DataViewSession old = doc.getDataViewSession();
            assertEquals(3, old.rowCount());

            // Simulate the save's rewrite shifting every byte offset.
            Files.writeString(file.toPath(), "Date,flow\n2020-01-01,1.5\n", StandardCharsets.UTF_8);
            doc.refreshDataViewFromDisk();

            await("session rebuilt", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != old && s != null && s.isIndexingComplete();
            });
            assertEquals(2, doc.getDataViewSession().rowCount(),
                "the fresh session indexes the new bytes");
        } finally {
            doc.dispose();
        }
    }

    @Test
    void aboveGateRebuildSwapsTheVirtualTextArea() throws IOException {
        File file = csvFile(); // 3 physical lines
        DataDocument doc = new DataDocument(file, true); // read-only virtual pair
        try {
            await("initial index", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != null && s.isIndexingComplete();
            });
            DataViewSession old = doc.getDataViewSession();
            var area = (com.kalix.ide.dataview.VirtualTextArea) doc.getPrimaryFocusComponent();
            int lineHeight = area.getFontMetrics(area.getFont()).getHeight();

            // A rewrite (smaller file) forces the rebuild path, not a resume.
            Files.writeString(file.toPath(), "a\nb\nc\nd\ne\n", StandardCharsets.UTF_8);
            doc.refreshDataViewFromDisk();

            await("session rebuilt", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != old && s != null && s.isIndexingComplete();
            });
            await("virtual text view tracks the fresh session",
                () -> area.getPreferredSize().height == 5L * lineHeight);
        } finally {
            doc.dispose();
        }
    }

    @Test
    void aBurstOfRefreshRequestsConvergesOnTheFinalBytes() throws IOException {
        File file = csvFile();
        DataDocument doc = new DataDocument(file);
        try {
            await("initial index", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != null && s.isIndexingComplete();
            });
            // Two rewrites with a hail of refresh requests around them: the drain
            // loop must coalesce the burst WITHOUT losing the trailing request,
            // so the views always converge on the final bytes.
            Files.writeString(file.toPath(), "h1,h2\n1,2\n", StandardCharsets.UTF_8);
            for (int i = 0; i < 4; i++) {
                doc.refreshDataViewFromDisk();
            }
            Files.writeString(file.toPath(), "h1,h2\n1,2\n3,4\n5,6\n", StandardCharsets.UTF_8);
            for (int i = 0; i < 4; i++) {
                doc.refreshDataViewFromDisk();
            }
            await("converges on the final bytes", () -> {
                DataViewSession s = doc.getDataViewSession();
                return s != null && s.isIndexingComplete() && s.rowCount() == 4;
            });
        } finally {
            doc.dispose();
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void dataDocumentsRequireABackingFile() {
        assertThrows(IllegalArgumentException.class,
            () -> new DataDocument(null));
    }

    @Test
    void theGateIgnoresSmallAndAbsentFiles() throws IOException {
        assertFalse(DataDocument.exceedsEditableGate(null));
        assertFalse(DataDocument.exceedsEditableGate(csvFile()), "a few bytes is under any sane gate");
    }
}
