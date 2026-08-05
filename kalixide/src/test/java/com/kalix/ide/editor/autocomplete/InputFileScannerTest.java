package com.kalix.ide.editor.autocomplete;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@code [data]} path popup must offer exactly what {@code [data]} accepts.
 * Pixie is the interesting case: the {@code .pxt} half is offerable and the
 * {@code .pxb} half is not, because the engine reads the {@code .pxb} alongside
 * its {@code .pxt} and rejects it when named directly — offering it would be
 * offering a mistake.
 */
class InputFileScannerTest {

    /**
     * The scan is deliberately asynchronous (the popup never blocks on the
     * filesystem), so the first call returns the empty initial listing and
     * queues the walk. Poll until it lands.
     */
    private static List<String> scannedPaths(Path dir) throws Exception {
        InputFileScanner scanner = new InputFileScanner(dir::toFile);
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                List<String> paths = scanner.getRelativePathsAndRefresh();
                if (!paths.isEmpty()) {
                    return paths;
                }
                Thread.sleep(20);
            }
            fail("scan did not complete within 2s");
            return List.of(); // unreachable
        } finally {
            scanner.dispose();
        }
    }

    @Test
    void offersPxtAndCsvButNeverPxb(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("climate.csv"), "date,value\n");
        Files.writeString(dir.resolve("gaugings.pxt"), "metadata");
        Files.writeString(dir.resolve("gaugings.pxb"), "binary");

        List<String> paths = scannedPaths(dir);

        assertTrue(paths.contains("climate.csv"), "CSV should be offered: " + paths);
        assertTrue(paths.contains("gaugings.pxt"), ".pxt should be offered: " + paths);
        assertTrue(paths.stream().noneMatch(p -> p.endsWith(".pxb")),
                ".pxb must never be offered: " + paths);
    }

    @Test
    void findsPxtInSubdirectories(@TempDir Path dir) throws Exception {
        Path sub = dir.resolve("data");
        Files.createDirectory(sub);
        Files.writeString(sub.resolve("climate.pxt"), "metadata");

        List<String> paths = scannedPaths(dir);

        // Forward slashes regardless of platform.
        assertTrue(paths.contains("data/climate.pxt"), "expected nested .pxt: " + paths);
    }

    @Test
    void ignoresUnrelatedExtensions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("model.ini"), "[data]\n");
        Files.writeString(dir.resolve("climate.pxt"), "metadata");

        List<String> paths = scannedPaths(dir);

        assertTrue(paths.stream().noneMatch(p -> p.endsWith(".ini")),
                "only data files should be offered: " + paths);
    }
}
