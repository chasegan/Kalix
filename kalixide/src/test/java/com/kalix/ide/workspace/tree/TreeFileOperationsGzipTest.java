package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tree's Gzip and Gunzip as the menu calls them: off the EDT, beside the
 * selected file. The overwrite prompt needs a display, so only the prompt-free paths run here.
 */
class TreeFileOperationsGzipTest {

    private static final String CSV = "Date,q\n2020-01-01,1.0\n";

    @TempDir
    Path tempDir;

    private final TreeFileOperations ops = new TreeFileOperations(null, () -> null, (a, b) -> { });

    private static void await(Path path) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!Files.exists(path)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + path);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void gzipThenGunzipGivesTheFileBack() throws IOException {
        File csv = Files.writeString(tempDir.resolve("flows.csv"), CSV, StandardCharsets.UTF_8).toFile();
        ops.gzipFile(csv);
        Path gz = tempDir.resolve("flows.csv.gz");
        await(gz);

        Files.delete(csv.toPath());
        ops.gunzipFile(gz.toFile());
        await(csv.toPath());
        assertEquals(CSV, Files.readString(csv.toPath()));
    }

    @Test
    void gzipNeverOverwritesItTakesTheNextFreeName() throws IOException {
        File csv = Files.writeString(tempDir.resolve("flows.csv"), CSV).toFile();
        Files.writeString(tempDir.resolve("flows.csv.gz"), "taken");
        ops.gzipFile(csv);
        await(tempDir.resolve("flows (1).csv.gz"));
        assertEquals("taken", Files.readString(tempDir.resolve("flows.csv.gz")));
    }
}
