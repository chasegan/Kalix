package com.kalix.ide.io;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the file tree's Zip and Unzip rely on: a folder is archived under its own name
 * with '/' separators and its empty subfolders, files from one folder at the top level and
 * from several under their folders, links followed, nothing left behind on failure; and
 * extraction gives it all back, overwrites what is already there, and refuses an entry that
 * escapes the target.
 */
class ZipArchiveTest {

    @TempDir
    Path tempDir;

    private Path write(String relative, String content) throws IOException {
        Path file = tempDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void folderAndFileRoundTrip() throws IOException {
        FileTime stamp = FileTime.from(Instant.parse("2024-05-06T07:08:09Z"));
        Files.setLastModifiedTime(write("data/climate/rain.csv", "rain"), stamp);
        write("data/notes.txt", "notes");
        Files.createDirectories(tempDir.resolve("data/empty"));
        write("model.ini", "[node.a]");
        File zip = tempDir.resolve("files.zip").toFile();

        ZipArchive.create(zip, List.of(tempDir.resolve("data").toFile(), tempDir.resolve("model.ini").toFile()));

        assertEquals(List.of("data/climate/rain.csv", "data/notes.txt", "model.ini"),
            ZipArchive.fileEntryNames(zip).stream().sorted().toList());

        Path out = tempDir.resolve("out");
        ZipArchive.extractAll(zip, out.toFile());
        assertEquals("rain", Files.readString(out.resolve("data/climate/rain.csv")));
        assertEquals("notes", Files.readString(out.resolve("data/notes.txt")));
        assertEquals("[node.a]", Files.readString(out.resolve("model.ini")));
        assertTrue(Files.isDirectory(out.resolve("data/empty")), "empty subfolder survives");
        assertEquals(stamp.toMillis() / 1000, Files.getLastModifiedTime(out.resolve("data/climate/rain.csv")).toMillis() / 1000,
            "modification time kept to the second");
    }

    @Test
    void archiveInsideTheFolderBeingZippedIsSkipped() throws IOException {
        write("data/a.txt", "a");
        File zip = tempDir.resolve("data/data.zip").toFile();

        ZipArchive.create(zip, List.of(tempDir.resolve("data").toFile()));

        assertEquals(List.of("data/a.txt"), ZipArchive.fileEntryNames(zip));
    }

    @Test
    void targetsFromDifferentFoldersKeepTheirPathsFromTheSharedFolder() throws IOException {
        write("a/x.csv", "a");
        write("b/x.csv", "b");
        write("c/deep/folder/y.txt", "y");
        File zip = tempDir.resolve("files.zip").toFile();

        ZipArchive.create(zip, List.of(tempDir.resolve("a/x.csv").toFile(), tempDir.resolve("b/x.csv").toFile(),
            tempDir.resolve("c/deep/folder").toFile()));

        assertEquals(List.of("a/x.csv", "b/x.csv", "c/deep/folder/y.txt"),
            ZipArchive.fileEntryNames(zip).stream().sorted().toList());
    }

    private void symlink(String link, String target) throws IOException {
        try {
            Files.createSymbolicLink(tempDir.resolve(link), tempDir.resolve(target));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links unavailable here: " + e);
        }
    }

    @Test
    void symbolicLinksAreFollowed() throws IOException {
        write("data/real/f.txt", "f");
        symlink("data/link", "data/real");
        File zip = tempDir.resolve("data.zip").toFile();

        ZipArchive.create(zip, List.of(tempDir.resolve("data").toFile()));

        assertEquals(List.of("data/link/f.txt", "data/real/f.txt"),
            ZipArchive.fileEntryNames(zip).stream().sorted().toList());
    }

    @Test
    void danglingLinkFailsWithTheReason() throws IOException {
        write("data/f.txt", "f");
        symlink("data/broken", "data/missing");
        File zip = tempDir.resolve("data.zip").toFile();

        IOException ex = assertThrows(IOException.class,
            () -> ZipArchive.create(zip, List.of(tempDir.resolve("data").toFile())));

        assertTrue(ex.getMessage().contains("target does not exist"), ex.getMessage());
        assertFalse(zip.exists());
    }

    @Test
    void failedCreateLeavesNoArchive() {
        File zip = tempDir.resolve("gone.zip").toFile();

        assertThrows(IOException.class,
            () -> ZipArchive.create(zip, List.of(tempDir.resolve("does-not-exist.txt").toFile())));

        assertFalse(zip.exists());
    }

    @Test
    void existingFileAtTheArchivePathIsRefusedAndKept() throws IOException {
        Path existing = write("keep.zip", "not touched");
        write("a.txt", "a");

        assertThrows(IOException.class,
            () -> ZipArchive.create(existing.toFile(), List.of(tempDir.resolve("a.txt").toFile())));

        assertEquals("not touched", Files.readString(existing));
    }

    @Test
    void extractOverwritesAnExistingFile() throws IOException {
        write("src/a.txt", "new");
        File zip = tempDir.resolve("a.zip").toFile();
        ZipArchive.create(zip, List.of(tempDir.resolve("src/a.txt").toFile()));
        Path out = tempDir.resolve("out");
        write("out/a.txt", "old");

        ZipArchive.extractAll(zip, out.toFile());

        assertEquals("new", Files.readString(out.resolve("a.txt")));
    }

    @Test
    void entryEscapingTheTargetIsRefused() throws IOException {
        Path zip = tempDir.resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);

        IOException ex = assertThrows(IOException.class, () -> ZipArchive.extractAll(zip.toFile(), out.toFile()));

        assertTrue(ex.getMessage().contains("../escaped.txt"), ex.getMessage());
        assertFalse(Files.exists(tempDir.resolve("escaped.txt")));
    }
}
