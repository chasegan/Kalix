package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TreeTransferHandler#actionable}: which dragged entries a drop into a folder
 * acts on, for drags from the tree and from the operating system.
 */
class TreeTransferHandlerTest {

    private static final File ROOT = Path.of("proj").toAbsolutePath().toFile();

    private static File file(String... parts) {
        return Path.of(ROOT.getPath(), parts).toFile();
    }

    private static List<String> names(List<File> files) {
        return files.stream().map(File::getName).toList();
    }

    @Test
    void folderCannotBeDroppedIntoItself() {
        assertTrue(TreeTransferHandler.actionable(List.of(file("data")), file("data"), false).isEmpty());
    }

    @Test
    void folderCannotBeDroppedIntoADescendant_evenAlongsideValidEntries() {
        List<File> dragged = List.of(file("other.csv"), file("data"));
        assertTrue(TreeTransferHandler.actionable(dragged, file("data", "sub"), false).isEmpty());
    }

    @Test
    void entryAlreadyInTargetIsSkipped_othersKept() {
        List<File> dragged = List.of(file("data", "a.csv"), file("b.csv"));
        assertEquals(List.of("b.csv"), names(TreeTransferHandler.actionable(dragged, file("data"), false)));
    }

    @Test
    void dropThatSkipsEverythingDoesNothing() {
        List<File> dragged = List.of(file("data", "a.csv"));
        assertTrue(TreeTransferHandler.actionable(dragged, file("data"), false).isEmpty());
    }

    @Test
    void inTreeCopyDuplicatesInPlace() {
        List<File> dragged = List.of(file("data", "a.csv"));
        assertEquals(List.of("a.csv"), names(TreeTransferHandler.actionable(dragged, file("data"), true)));
    }

    @Test
    void aFilesystemRootIsSkipped() {
        // A volume dragged from "This PC" has no name; copying it "under its name" would land
        // on the target folder itself.
        File otherVolume = ROOT.toPath().getRoot().toFile();
        List<File> dragged = List.of(otherVolume, file("a.csv"));
        assertEquals(List.of("a.csv"), names(TreeTransferHandler.actionable(dragged, file("data"), false)));
    }

    @Test
    void dragOrderIsKept() {
        List<File> dragged = List.of(file("z.csv"), file("a.csv"), file("m"));
        assertEquals(List.of("z.csv", "a.csv", "m"),
            names(TreeTransferHandler.actionable(dragged, file("data"), false)));
    }
}
