package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classification and model-folder detection behind the project tree's colour coding:
 * {@link FileCategory} (pure name logic) and {@link FileTreeNode#containsModelFile()}
 * (one cached directory scan, invalidated by the tree when the watcher reports changes).
 */
class FileCategoryTest {

    @Test
    void classifiesByExtensionCaseInsensitively() {
        assertEquals(FileCategory.MODEL, FileCategory.ofName("catchment.ini"));
        assertEquals(FileCategory.MODEL, FileCategory.ofName("CATCHMENT.INI"));
        assertEquals(FileCategory.DATA, FileCategory.ofName("rain.csv"));
        assertEquals(FileCategory.SOURCE_RESULT, FileCategory.ofName("results.res.csv"));
        assertEquals(FileCategory.SOURCE_RESULT, FileCategory.ofName("RESULTS.RES.CSV"));
        assertEquals(FileCategory.DATA, FileCategory.ofName("series.pxt"));
        assertEquals(FileCategory.DATA, FileCategory.ofName("series.PXB"));
        assertEquals(FileCategory.OTHER, FileCategory.ofName("notes.md"));
        assertEquals(FileCategory.OTHER, FileCategory.ofName("model.toml"));
        assertEquals(FileCategory.OTHER, FileCategory.ofName("ini")); // no extension
    }

    @Test
    void detectsDirectModelContainment(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("rain.csv"));
        FileTreeNode node = new FileTreeNode(dir.toFile(), () -> true);
        assertFalse(node.containsModelFile());

        // The answer is cached until invalidated (the tree invalidates on watcher events).
        Files.createFile(dir.resolve("model.ini"));
        assertFalse(node.containsModelFile());
        node.invalidateContainsModelFile();
        assertTrue(node.containsModelFile());
    }

    @Test
    void directoryNamedLikeModelDoesNotCount(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("archive.ini"));
        FileTreeNode node = new FileTreeNode(dir.toFile(), () -> true);
        assertFalse(node.containsModelFile());
    }

    @Test
    void modelsInSubfoldersDoNotMarkTheParent(@TempDir Path dir) throws Exception {
        Path sub = Files.createDirectory(dir.resolve("nested"));
        Files.createFile(sub.resolve("model.ini"));
        FileTreeNode parent = new FileTreeNode(dir.toFile(), () -> true);
        FileTreeNode nested = new FileTreeNode(sub.toFile(), () -> true);
        assertFalse(parent.containsModelFile()); // direct containment only
        assertTrue(nested.containsModelFile());
    }

    @Test
    void plainFilesNeverContainModels(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("model.ini"));
        FileTreeNode node = new FileTreeNode(file.toFile(), () -> true);
        assertFalse(node.containsModelFile());
    }

    @Test
    void ordersDirectoriesThenHiddenThenNaturalName(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("2024_runs"));
        Files.createDirectory(dir.resolve(".git"));
        Files.createDirectory(dir.resolve("archive"));
        Files.createFile(dir.resolve("model2.ini"));
        Files.createFile(dir.resolve("model10.ini"));
        Files.createFile(dir.resolve(".gitignore"));

        java.io.File[] entries = dir.toFile().listFiles();
        java.util.Arrays.sort(entries, FileTreeNode.FILE_ORDER);
        java.util.List<String> names = java.util.Arrays.stream(entries)
            .map(java.io.File::getName).toList();

        // Directories before files; hidden first within each group (deliberately, even
        // above digit-led names, per file-tree-colour §2.7); then natural number-aware order.
        assertEquals(java.util.List.of(
            ".git", "2024_runs", "archive",
            ".gitignore", "model2.ini", "model10.ini"), names);
    }
}
