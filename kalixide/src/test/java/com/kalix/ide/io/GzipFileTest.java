package com.kalix.ide.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the file tree's Gzip and Gunzip rely on: byte-identical round trips,
 * output the data view reads, names that stay {@code .csv.gz}, and no partial output or lost
 * file on failure.
 */
class GzipFileTest {

    @TempDir
    Path tempDir;

    private static final String CSV = "Date,q\n2020-01-01,1.0\n2020-01-02,2.0\n";

    private File csv(String name) throws IOException {
        return Files.writeString(tempDir.resolve(name), CSV, StandardCharsets.UTF_8).toFile();
    }

    @Test
    void roundTripIsByteIdenticalAndReadableAsCompressedCsv() throws IOException {
        File src = csv("flows.csv");
        File gz = GzipFile.compressedTarget(src);
        assertEquals("flows.csv.gz", gz.getName());
        GzipFile.compress(src, gz);
        assertEquals(CSV, new String(CompressedCsvFormat.decompressBounded(gz, 1024), StandardCharsets.UTF_8),
            "the data view reads what Gzip writes");

        Files.delete(src.toPath());
        File back = GzipFile.decompressedTarget(gz);
        assertEquals(src, back);
        GzipFile.decompress(gz, back);
        assertArrayEquals(CSV.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(back.toPath()));
    }

    @Test
    void outputAppearsOnlyUnderItsFinalName() throws IOException {
        File src = csv("flows.csv");
        GzipFile.compress(src, GzipFile.compressedTarget(src));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("flows.csv", "flows.csv.gz"),
                files.map(p -> p.getFileName().toString()).sorted().toList(),
                "the staging sibling is renamed, not left behind");
        }
    }

    @Test
    void compressIsReproducible() throws IOException {
        File src = csv("flows.csv");
        File a = tempDir.resolve("a.csv.gz").toFile();
        File b = tempDir.resolve("b.csv.gz").toFile();
        GzipFile.compress(src, a);
        GzipFile.compress(src, b);
        assertArrayEquals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
    }

    @Test
    void aTakenNameGainsACounterBeforeTheDoubleExtension() throws IOException {
        File src = csv("flows.csv");
        Files.writeString(tempDir.resolve("flows.csv.gz"), "taken");
        Files.writeString(tempDir.resolve("flows (1).csv.gz"), "taken");
        assertEquals("flows (2).csv.gz", GzipFile.compressedTarget(src).getName());
    }

    @Test
    void compressRefusesAnExistingTargetAndLeavesItAlone() throws IOException {
        File src = csv("flows.csv");
        Path existing = Files.writeString(tempDir.resolve("flows.csv.gz"), "keep me");
        assertThrows(IOException.class, () -> GzipFile.compress(src, existing.toFile()));
        assertEquals("keep me", Files.readString(existing));
    }

    @Test
    void failedCompressLeavesNoPartialFile() {
        File missing = tempDir.resolve("missing.csv").toFile();
        File target = tempDir.resolve("missing.csv.gz").toFile();
        assertThrows(IOException.class, () -> GzipFile.compress(missing, target));
        assertFalse(target.exists());
    }

    @Test
    void failedDecompressKeepsTheFileItWouldReplace() throws IOException {
        Path bogus = Files.writeString(tempDir.resolve("flows.csv.gz"), "not gzip");
        Path existing = Files.writeString(tempDir.resolve("flows.csv"), "keep me");
        assertThrows(IOException.class, () -> GzipFile.decompress(bogus.toFile(), existing.toFile()));
        assertEquals("keep me", Files.readString(existing));
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.count(), "no temporary file left behind");
        }
    }

    @Test
    void theCsvExtensionKeepsItsCaseSoGunzipGivesTheNameBack() throws IOException {
        File src = csv("FLOWS.CSV");
        File gz = GzipFile.compressedTarget(src);
        assertEquals("FLOWS.CSV.gz", gz.getName());
        assertEquals("FLOWS.CSV", GzipFile.decompressedTarget(gz).getName());
    }

    @Test
    void decompressRefusesAFolderAndLeavesItAlone() throws IOException {
        File src = csv("flows.csv");
        File gz = GzipFile.compressedTarget(src);
        GzipFile.compress(src, gz);
        Path folder = Files.createDirectory(tempDir.resolve("flows"));
        Files.writeString(folder.resolve("inside.txt"), "keep me");
        File target = tempDir.resolve("flows.gz").toFile();
        Files.copy(gz.toPath(), target.toPath());

        IOException e = assertThrows(IOException.class, () -> GzipFile.decompress(target, folder.toFile()));
        assertTrue(FileErrors.describe(e).contains("folder"), FileErrors.describe(e));
        assertEquals("keep me", Files.readString(folder.resolve("inside.txt")));
    }

    @Test
    void aStagingFileLeftByACrashIsReplacedNotSteppedAround() throws IOException {
        File src = csv("flows.csv");
        File gz = tempDir.resolve("flows.csv.gz").toFile();
        Files.writeString(tempDir.resolve(".flows.csv.gz.part"), "left by a crash");
        GzipFile.compress(src, gz);
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("flows.csv", "flows.csv.gz"),
                files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void aFolderAtTheStagingNameIsReportedNotDeleted() throws IOException {
        File src = csv("flows.csv");
        Path folder = Files.createDirectory(tempDir.resolve(".flows.csv.gz.part"));
        Files.writeString(folder.resolve("inside.txt"), "keep me");
        IOException e = assertThrows(IOException.class,
            () -> GzipFile.compress(src, tempDir.resolve("flows.csv.gz").toFile()));
        assertTrue(FileErrors.describe(e).contains(".flows.csv.gz.part"), FileErrors.describe(e));
        assertEquals("keep me", Files.readString(folder.resolve("inside.txt")));

        // Even an empty one: the cleanup after a failure must not take it.
        Files.delete(folder.resolve("inside.txt"));
        assertThrows(IOException.class, () -> GzipFile.compress(src, tempDir.resolve("flows.csv.gz").toFile()));
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void decompressReplacesAnExistingFile() throws IOException {
        File src = csv("flows.csv");
        File gz = GzipFile.compressedTarget(src);
        GzipFile.compress(src, gz);
        Files.writeString(src.toPath(), "old");
        GzipFile.decompress(gz, src);
        assertEquals(CSV, Files.readString(src.toPath()));
    }

    @Test
    void gzipNamesNeedSomethingBeforeTheExtension() {
        assertTrue(GzipFile.isGzip(new File("flows.csv.gz")));
        assertTrue(GzipFile.isGzip(new File("BUNDLE.TAR.GZ")));
        assertFalse(GzipFile.isGzip(new File(".gz")));
        assertFalse(GzipFile.isGzip(new File("flows.csv")));
    }
}
