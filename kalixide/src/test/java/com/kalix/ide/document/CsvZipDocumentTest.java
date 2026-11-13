package com.kalix.ide.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code .csv.zip} document contract (issue #409): the file opens as
 * a DATA document, is read-only regardless of size (its bytes on disk are a
 * zip archive — there is no text buffer to honestly edit and save back), and
 * carries the decoded table as its contextual view.
 */
class CsvZipDocumentTest {

    @TempDir
    Path tempDir;

    private File zipFile(String name, String inner, String content) throws IOException {
        Path file = tempDir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry(inner));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file.toFile();
    }

    @Test
    void csvZipOpensAsAReadOnlyDataDocument() throws IOException {
        File zip = zipFile("flows.csv.zip", "flows.csv", "Date,flow\n2020-01-01,1.5\n");
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(zip));
        KalixDocument doc = KalixDocument.createFor(zip);
        try {
            assertInstanceOf(DataDocument.class, doc);
            assertFalse(doc.isEditable(),
                "zip bytes on disk: no honest text buffer to edit, so always read-only");
            assertNotNull(doc.getContextView(), "the decoded table is the contextual view");
            assertFalse(doc.isModel());
        } finally {
            doc.dispose();
        }
    }

    @Test
    void csvGzOpensAsAReadOnlyDataDocument() throws IOException {
        // The gzip sibling: same contract, same reasons.
        Path file = tempDir.resolve("flows.csv.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write("Date,flow\n2020-01-01,1.5\n".getBytes(StandardCharsets.UTF_8));
        }
        File gz = file.toFile();
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(gz));
        KalixDocument doc = KalixDocument.createFor(gz);
        try {
            assertInstanceOf(DataDocument.class, doc);
            assertFalse(doc.isEditable(), "gzip bytes on disk: always read-only");
            assertNotNull(doc.getContextView(), "the decoded table is the contextual view");
        } finally {
            doc.dispose();
        }
    }

    @Test
    void anUnreadableCsvGzSaysWhyInItsBanner() throws IOException {
        // A truncated download: the tab is read-only with nothing to show, so
        // the banner must carry the reason rather than leave a blank tab.
        File bogus = Files.writeString(tempDir.resolve("cut.csv.gz"), "not gzip").toFile();
        KalixDocument doc = KalixDocument.createFor(bogus);
        try {
            assertFalse(doc.isEditable());
            JComponent primary = doc.getPrimaryView();
            assertNotSame(doc.getEditor(), primary, "the editor is wrapped in a banner");
            List<String> labels = new ArrayList<>();
            collectLabels(primary, labels);
            assertTrue(labels.stream().anyMatch(t -> t.startsWith("Can't read this file")), labels.toString());
        } finally {
            doc.dispose();
        }
    }

    private static void collectLabels(Component c, List<String> out) {
        if (c instanceof JLabel label && label.getText() != null) {
            out.add(label.getText());
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, out);
            }
        }
    }
}
