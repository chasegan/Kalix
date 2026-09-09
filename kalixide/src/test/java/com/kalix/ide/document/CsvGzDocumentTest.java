package com.kalix.ide.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the {@code .csv.gz} document contract (issue #374): the file opens as a
 * DATA document, is read-only regardless of size (its bytes on disk are gzip —
 * there is no text buffer to honestly edit and save back), and carries the
 * decoded table as its contextual view.
 */
class CsvGzDocumentTest {

    @TempDir
    Path tempDir;

    private File gzFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file.toFile();
    }

    @Test
    void csvGzOpensAsAReadOnlyDataDocument() throws IOException {
        File gz = gzFile("flows.csv.gz", "Date,flow\n2020-01-01,1.5\n");
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(gz));
        KalixDocument doc = KalixDocument.createFor(gz);
        try {
            assertInstanceOf(DataDocument.class, doc);
            assertFalse(doc.isEditable(),
                "gzip bytes on disk: no honest text buffer to edit, so always read-only");
            assertNotNull(doc.getContextView(), "the decoded table is the contextual view");
            assertFalse(doc.isModel());
        } finally {
            doc.dispose();
        }
    }

}
