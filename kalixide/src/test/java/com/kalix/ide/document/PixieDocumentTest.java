package com.kalix.ide.document;

import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.io.NamedSeries;
import com.kalix.ide.io.PixieWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Pixie document contract: .pxt (and .pxb) open as the pixie bundle,
 * the manifest stays editable while a binary-backed tab is read-only, and the
 * decoded table is the contextual view.
 */
class PixieDocumentTest {

    @TempDir
    Path tempDir;

    private File writePair(String name) throws IOException {
        String base = tempDir.resolve(name).toString();
        LocalDateTime[] times = {LocalDateTime.of(2020, 1, 1, 0, 0)};
        new PixieWriter().writeToFile(base,
            List.of(new NamedSeries("flow", new TimeSeriesData(times, new double[] {1.0}))), true);
        return new File(base + ".pxt");
    }

    @Test
    void pxtOpensAsThePixieBundle() throws IOException {
        File pxt = writePair("results");
        assertEquals(DocumentKind.DATA, DocumentKind.forFile(pxt));
        KalixDocument doc = KalixDocument.createFor(pxt);
        try {
            assertInstanceOf(PixieDocument.class, doc);
            assertNotNull(doc.getContextView(), "the decoded table is the contextual view");
            assertTrue(doc.isEditable(), "the manifest is the modeller's to edit");
            assertFalse(doc.isModel());
        } finally {
            doc.dispose();
        }
    }

    @Test
    void pxbBackedDocumentIsReadOnly() throws IOException {
        File pxt = writePair("binaryside");
        File pxb = new File(pxt.getAbsolutePath().replace(".pxt", ".pxb"));
        KalixDocument doc = KalixDocument.createFor(pxb);
        try {
            assertInstanceOf(PixieDocument.class, doc);
            assertFalse(doc.isEditable(),
                "an empty buffer over a real binary must never be saveable");
        } finally {
            doc.dispose();
        }
    }
}
