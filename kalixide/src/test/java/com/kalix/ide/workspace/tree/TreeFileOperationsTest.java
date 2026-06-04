package com.kalix.ide.workspace.tree;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link TreeFileOperations#withoutDescendants}: when a multi-selection contains both a
 * folder and entries inside it, the descendants are dropped so the recursive delete of the
 * ancestor does not then fail on an already-removed child.
 */
class TreeFileOperationsTest {

    private static List<String> names(List<File> files) {
        return files.stream()
                .map(f -> f.toPath().normalize().toString().replace('\\', '/'))
                .toList();
    }

    private static File file(String... parts) {
        return Path.of("", parts).toFile();
    }

    @Test
    void unrelatedEntriesAllKept() {
        List<File> in = List.of(file("a", "x"), file("b", "y"));
        assertEquals(List.of("a/x", "b/y"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void childDroppedWhenParentPresent() {
        List<File> in = List.of(file("x"), file("x", "y"));
        assertEquals(List.of("x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void deepDescendantDroppedWhenAncestorPresent() {
        List<File> in = List.of(file("x"), file("x", "y", "z"));
        assertEquals(List.of("x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void orderIndependentParentBeforeOrAfterChild() {
        List<File> in = List.of(file("x", "y"), file("x"));
        assertEquals(List.of("x"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void siblingsBothKept() {
        List<File> in = List.of(file("x", "a"), file("x", "b"));
        assertEquals(List.of("x/a", "x/b"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void inputOrderPreservedAmongSurvivors() {
        List<File> in = List.of(file("x"), file("x", "y"), file("z"));
        assertEquals(List.of("x", "z"), names(TreeFileOperations.withoutDescendants(in)));
    }

    @Test
    void siblingPrefixNameNotTreatedAsDescendant() {
        // "x-data" must not be considered a descendant of "x" (element-wise comparison).
        List<File> in = List.of(file("x"), file("x-data"));
        assertEquals(List.of("x", "x-data"), names(TreeFileOperations.withoutDescendants(in)));
    }
}
