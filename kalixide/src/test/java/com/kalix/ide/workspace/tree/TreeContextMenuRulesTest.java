package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the context menu offers Zip, Unzip, Gzip and Gunzip. */
class TreeContextMenuRulesTest {

    @TempDir
    File dir;

    /** Nodes for files of these names; a name ending in "/" makes a folder. */
    private List<FileTreeNode> selection(String... names) throws IOException {
        List<FileTreeNode> nodes = new ArrayList<>();
        for (String name : names) {
            File file = new File(dir, name);
            if (name.endsWith("/")) {
                file.mkdir();
            } else {
                file.createNewFile();
            }
            nodes.add(new FileTreeNode(file, () -> true));
        }
        return nodes;
    }

    @Test
    void zipIsOfferedUnlessTheSelectionIsOneCompressedFile() throws IOException {
        assertTrue(TreeContextMenu.canZip(selection("flows.csv")));
        assertTrue(TreeContextMenu.canZip(selection("folder/")));
        assertFalse(TreeContextMenu.canZip(selection("bundle.zip")));
        assertFalse(TreeContextMenu.canZip(selection("a.csv.zip")));
        assertFalse(TreeContextMenu.canZip(selection("a.csv.gz")));
        assertTrue(TreeContextMenu.canZip(selection("a.csv.gz", "b.csv")), "several items zip together");
        assertTrue(TreeContextMenu.canZip(selection("a.csv.zip", "b.csv")), "the same rule as for gzip");
        assertTrue(TreeContextMenu.canZip(selection("bundle.zip", "notes.txt")));
        assertTrue(TreeContextMenu.canZip(selection("archive.zip/")), "a folder is not a zip, whatever its name");
        assertFalse(TreeContextMenu.canZip(List.of()));
    }

    @Test
    void gzipIsOfferedForOnePlainCsv() throws IOException {
        assertTrue(TreeContextMenu.isSinglePlainCsv(selection("flows.csv")));
        assertTrue(TreeContextMenu.isSinglePlainCsv(selection("FLOWS.CSV")));
        assertFalse(TreeContextMenu.isSinglePlainCsv(selection("results.res.csv")), "a Source result");
        assertFalse(TreeContextMenu.isSinglePlainCsv(selection("flows.csv.gz")));
        assertFalse(TreeContextMenu.isSinglePlainCsv(selection("notes.txt")));
        assertFalse(TreeContextMenu.isSinglePlainCsv(selection("a.csv", "b.csv")));
        assertFalse(TreeContextMenu.isSinglePlainCsv(selection("folder.csv/")));
    }

    @Test
    void gunzipIsOfferedForOneGzFile() throws IOException {
        assertTrue(TreeContextMenu.isSingleGzip(selection("flows.csv.gz")));
        assertTrue(TreeContextMenu.isSingleGzip(selection("bundle.tar.gz")), "any .gz, not only data");
        assertFalse(TreeContextMenu.isSingleGzip(selection("flows.csv")));
        assertFalse(TreeContextMenu.isSingleGzip(selection("a.gz", "b.gz")));
        assertFalse(TreeContextMenu.isSingleGzip(selection("folder.gz/")));
    }

    @Test
    void unzipIsOfferedForOneZip() throws IOException {
        assertTrue(TreeContextMenu.isSingleZip(selection("bundle.zip")));
        assertTrue(TreeContextMenu.isSingleZip(selection("flows.csv.zip")), "a zipped CSV can be extracted too");
        assertFalse(TreeContextMenu.isSingleZip(selection("flows.csv.gz")));
        assertFalse(TreeContextMenu.isSingleZip(selection("a.zip", "b.zip")));
    }
}
