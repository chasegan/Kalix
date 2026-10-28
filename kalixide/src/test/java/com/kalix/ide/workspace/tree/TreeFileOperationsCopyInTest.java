package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.kalix.ide.workspace.tree.TreeFileOperations.Collision.KEEP_BOTH;
import static com.kalix.ide.workspace.tree.TreeFileOperations.Collision.REPLACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link TreeFileOperations#copyInto}, the per-entry copy behind a drop from the
 * operating system, and how each collision policy resolves a taken name.
 */
class TreeFileOperationsCopyInTest {

    @TempDir
    Path tmp;

    private File write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path.toFile();
    }

    @Test
    void freeNameIsCopiedAsIs() throws IOException {
        File source = write(tmp.resolve("in/flow.csv"), "new");
        File target = Files.createDirectories(tmp.resolve("proj")).toFile();

        TreeFileOperations.copyInto(source, target, KEEP_BOTH);

        assertEquals("new", Files.readString(tmp.resolve("proj/flow.csv")));
        assertTrue(source.exists(), "a drop from outside never touches the original");
    }

    @Test
    void keepBothGivesTheCopyAFreeName() throws IOException {
        File source = write(tmp.resolve("in/flow.csv"), "new");
        write(tmp.resolve("proj/flow.csv"), "old");

        TreeFileOperations.copyInto(source, tmp.resolve("proj").toFile(), KEEP_BOTH);

        assertEquals("old", Files.readString(tmp.resolve("proj/flow.csv")));
        assertEquals("new", Files.readString(tmp.resolve("proj/flow (1).csv")));
    }

    @Test
    void replaceOverwritesAFile() throws IOException {
        File source = write(tmp.resolve("in/flow.csv"), "new");
        write(tmp.resolve("proj/flow.csv"), "old");

        TreeFileOperations.copyInto(source, tmp.resolve("proj").toFile(), REPLACE);

        assertEquals("new", Files.readString(tmp.resolve("proj/flow.csv")));
        assertFalse(Files.exists(tmp.resolve("proj/flow (1).csv")));
    }

    @Test
    void replaceReplacesAFolderInFull() throws IOException {
        write(tmp.resolve("in/data/a.csv"), "a");
        write(tmp.resolve("proj/data/stale.csv"), "stale");

        TreeFileOperations.copyInto(tmp.resolve("in/data").toFile(), tmp.resolve("proj").toFile(), REPLACE);

        assertEquals("a", Files.readString(tmp.resolve("proj/data/a.csv")));
        assertFalse(Files.exists(tmp.resolve("proj/data/stale.csv")), "replaced, not merged");
        assertFalse(Files.exists(tmp.resolve("proj/data (1)")), "the staging copy is swapped in, not left behind");
    }

    @Test
    void replaceLeavesTheExistingEntryWhenTheSourceCannotBeCopied() throws IOException {
        write(tmp.resolve("proj/flow.csv"), "old");
        File vanished = tmp.resolve("in/flow.csv").toFile(); // never created: gone by copy time

        assertThrows(IOException.class,
            () -> TreeFileOperations.copyInto(vanished, tmp.resolve("proj").toFile(), REPLACE));
        assertEquals("old", Files.readString(tmp.resolve("proj/flow.csv")));
        assertFalse(Files.exists(tmp.resolve("proj/flow (1).csv")));
    }

    @Test
    void replaceRefusesTheSameFileReachedThroughASymlink() throws IOException {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "symlinks need privileges on Windows");
        write(tmp.resolve("real/proj/flow.csv"), "only copy");
        Path link = tmp.resolve("link");
        Files.createSymbolicLink(link, tmp.resolve("real"));
        // The tree shows link/proj; Finder hands over the resolved real/proj/flow.csv.
        File source = tmp.resolve("real/proj/flow.csv").toFile();
        File target = link.resolve("proj").toFile();

        assertThrows(IOException.class, () -> TreeFileOperations.copyInto(source, target, REPLACE));
        assertEquals("only copy", Files.readString(tmp.resolve("real/proj/flow.csv")));
    }

    @Test
    void replaceRefusesToDeleteAFolderThatContainsTheSource() throws IOException {
        // proj/data/nested/data dropped into proj, where proj/data exists and contains the source.
        File source = Files.createDirectories(tmp.resolve("proj/data/nested/data")).toFile();
        File target = tmp.resolve("proj").toFile();

        assertThrows(IOException.class, () -> TreeFileOperations.copyInto(source, target, REPLACE));
        assertTrue(source.exists());
    }
}
