package com.kalix.ide.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
