package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link TreeFileOperations#withoutDescendants}: when a multi-selection contains both a
 * folder and entries inside it, the descendants are dropped so the recursive delete of the
 * ancestor does not then fail on an already-removed child.
 */
class TreeFileOperationsTest {

    private static List<String> names(List<File> files) {
        return files.stream().map(File::getPath).toList();
    }

    @Test
    void unrelatedEntriesAllKept() {
        List<File> in = List.of(new File("/a/x"), new File("/b/y"));
        assertEquals(List.of("/a/x", "/b/y"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void childDroppedWhenParentPresent() {
        List<File> in = List.of(new File("/x"), new File("/x/y"));
        assertEquals(List.of("/x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void deepDescendantDroppedWhenAncestorPresent() {
        List<File> in = List.of(new File("/x"), new File("/x/y/z"));
        assertEquals(List.of("/x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void orderIndependentParentBeforeOrAfterChild() {
        List<File> in = List.of(new File("/x/y"), new File("/x"));
        assertEquals(List.of("/x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void siblingsBothKept() {
        List<File> in = List.of(new File("/x/a"), new File("/x/b"));
        assertEquals(List.of("/x/a", "/x/b"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void inputOrderPreservedAmongSurvivors() {
        List<File> in = List.of(new File("/x"), new File("/x/y"), new File("/z"));
        assertEquals(List.of("/x", "/z"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void siblingPrefixNameNotTreatedAsDescendant() {
        // "/x-data" must not be considered a descendant of "/x" (element-wise comparison).
        List<File> in = List.of(new File("/x"), new File("/x-data"));
        assertEquals(List.of("/x", "/x-data"), names(TreeFileOperations.withoutDescendants(in)));
    }
}
