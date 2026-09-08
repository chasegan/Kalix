package com.kalix.ide.document;

import com.kalix.ide.managers.FileOperationsManager;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one invariant whose failure mode is data loss: a read-only data
 * document has no editor buffer, so every save path must refuse rather than
 * write an empty buffer over the real file. Lives in the document package to
 * reach the gate test seam.
 */
class ReadOnlyDataSaveGuardTest {

    private static FileOperationsManager manager(DocumentManager dm, List<String> status) {
        return new FileOperationsManager(
            null, dm, KalixDocument::createFor,
            status::add, s -> { }, () -> { }, null);
    }

    @Test
    void savePathsNeverTouchAReadOnlyDataFile() throws IOException {
        Path file = Files.createTempFile("kalix-saveguard", ".csv");
        file.toFile().deleteOnExit();
        Files.writeString(file, "Date,flow\n2020-01-01,1.5\n", StandardCharsets.UTF_8);
        String before = Files.readString(file);

        DocumentManager dm = new DocumentManager();
        List<String> status = new ArrayList<>();
        FileOperationsManager fom = manager(dm, status);
        KalixDocument doc = new DataDocument(file.toFile(), true); // gate seam
        try {
            dm.setActiveDocument(doc);
            fom.saveModel();
            fom.saveAllModels();
            assertEquals(before, Files.readString(file),
                "a save must never write a read-only data view's empty buffer over the file");
            assertTrue(status.stream().anyMatch(s -> s.contains("Read-only")),
                "the refusal is reported, not silent");
        } finally {
            doc.dispose();
        }
    }

    @Test
    void aFailedSessionAboveTheGateIsStillGuarded() {
        // The reviewer's blocker scenario: session open fails, editor buffer is
        // empty, and a habitual Save All must refuse for this document.
        DocumentManager dm = new DocumentManager();
        List<String> status = new ArrayList<>();
        FileOperationsManager fom = manager(dm, status);
        KalixDocument doc = new DataDocument(
            new File("/nonexistent/kalix-saveguard-missing.csv"), true);
        try {
            dm.setActiveDocument(doc);
            fom.saveAllModels();
            assertTrue(status.stream().anyMatch(s -> s.contains("Read-only")));
        } finally {
            doc.dispose();
        }
    }
}
