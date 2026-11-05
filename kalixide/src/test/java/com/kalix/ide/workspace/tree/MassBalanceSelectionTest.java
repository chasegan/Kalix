package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which project tree selections offer the mass balance items. */
class MassBalanceSelectionTest {

    @TempDir
    Path dir;

    private FileTreeNode file(String name) throws IOException {
        return new FileTreeNode(Files.createFile(dir.resolve(name)).toFile(), () -> true);
    }

    private FileTreeNode folder(String name) throws IOException {
        return new FileTreeNode(Files.createDirectory(dir.resolve(name)).toFile(), () -> true);
    }

    private static List<FileTreeNode> sel(FileTreeNode... nodes) {
        return Arrays.asList(nodes);
    }

    @Test
    void oneModelOffersTheReport() throws IOException {
        assertTrue(TreeContextMenu.isSingleModel(sel(file("model.ini"))));
        assertTrue(TreeContextMenu.isSingleModel(sel(file("UPPER.INI"))));
    }

    @Test
    void anythingElseDoesNotOfferTheReport() throws IOException {
        assertFalse(TreeContextMenu.isSingleModel(sel(file("mbal.txt"))));
        assertFalse(TreeContextMenu.isSingleModel(sel(file("flows.csv"))));
        assertFalse(TreeContextMenu.isSingleModel(sel(folder("models.ini"))));
        assertFalse(TreeContextMenu.isSingleModel(sel(file("a.ini"), file("b.ini"))));
    }

    @Test
    void modelAndTextFileOfferValidationInEitherOrder() throws IOException {
        FileTreeNode model = file("model.ini");
        FileTreeNode report = file("mbal.txt");

        assertTrue(TreeContextMenu.isModelAndText(sel(model, report)));
        assertTrue(TreeContextMenu.isModelAndText(sel(report, model)));
        assertTrue(TreeContextMenu.isModelAndText(sel(model, file("report"))));
    }

    @Test
    void otherPairsDoNotOfferValidation() throws IOException {
        FileTreeNode model = file("model.ini");

        assertFalse(TreeContextMenu.isModelAndText(sel(model, file("other.ini"))));
        assertFalse(TreeContextMenu.isModelAndText(sel(model, file("flows.csv"))));
        assertFalse(TreeContextMenu.isModelAndText(sel(model, file("archive.zip"))));
        assertFalse(TreeContextMenu.isModelAndText(sel(model, folder("reports"))));
        assertFalse(TreeContextMenu.isModelAndText(sel(file("a.txt"), file("b.txt"))));
        assertFalse(TreeContextMenu.isModelAndText(sel(model)));
        assertFalse(TreeContextMenu.isModelAndText(sel(model, file("mbal.txt"), file("notes.txt"))));
    }
}
