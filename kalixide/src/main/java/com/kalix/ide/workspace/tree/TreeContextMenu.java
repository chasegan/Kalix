package com.kalix.ide.workspace.tree;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Builds the project tree's right-click menu from the current selection.
 *
 * <p>Each item is a declarative {@link Entry}: a label, a predicate deciding whether it applies
 * to the selection, and a handler acting on the selection. Entries are organised into groups;
 * {@link #build} renders only the entries whose predicate passes and emits a separator only
 * <em>between</em> non-empty groups — so items that drop out for a multi-selection never leave a
 * stray or doubled separator behind.
 *
 * <p>Adding a future interaction is a matter of adding one {@link Entry} with its applicability
 * rule; the rendering and separator logic need no changes.
 */
class TreeContextMenu {

    private final ProjectTree tree;
    private final TreeFileOperations fileOps;
    private final Consumer<File> fileOpenConsumer;
    private final Consumer<File> compareWithActiveEditor;

    /** Item groups in display order; separators are drawn between non-empty groups. */
    private final List<List<Entry>> groups;

    TreeContextMenu(ProjectTree tree, TreeFileOperations fileOps, Consumer<File> fileOpenConsumer,
                    Consumer<File> compareWithActiveEditor) {
        this.tree = tree;
        this.fileOps = fileOps;
        this.fileOpenConsumer = fileOpenConsumer;
        this.compareWithActiveEditor = compareWithActiveEditor;
        this.groups = buildEntries();
    }

    /** Builds the popup for the given selection (in row order), or returns null if it is empty. */
    JPopupMenu build(List<FileTreeNode> selection) {
        if (selection.isEmpty()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        boolean firstGroup = true;
        for (List<Entry> group : groups) {
            List<Entry> visible = group.stream()
                .filter(e -> e.visible.test(selection))
                .collect(Collectors.toList());
            if (visible.isEmpty()) {
                continue;
            }
            if (!firstGroup) {
                menu.addSeparator();
            }
            firstGroup = false;
            for (Entry entry : visible) {
                JMenuItem item = new JMenuItem(entry.label.apply(selection));
                item.addActionListener(e -> entry.action.accept(selection));
                menu.add(item);
            }
        }
        return menu;
    }

    // --- Entry definitions ---

    private List<List<Entry>> buildEntries() {
        return List.of(
            List.of(
                item("Open", TreeContextMenu::isSingleFile,
                    sel -> fileOpenConsumer.accept(file(sel))),
                item("Compare with active editor", TreeContextMenu::isSingleFile,
                    sel -> compareWithActiveEditor.accept(file(sel)))
            ),
            List.of(
                item("Reveal in File Manager", TreeContextMenu::isSingle,
                    sel -> fileOps.reveal(file(sel)))
            ),
            List.of(
                item("Expand children", TreeContextMenu::hasDirectory,
                    sel -> directories(sel).forEach(tree::expandSubtree)),
                item("Collapse children", TreeContextMenu::hasDirectory,
                    sel -> directories(sel).forEach(tree::collapseSubtree)),
                item("Collapse tree", TreeContextMenu::any,
                    sel -> tree.collapseAll())
            ),
            List.of(
                item("Copy relative path", TreeContextMenu::any,
                    sel -> fileOps.copyRelativePaths(files(sel))),
                item("Copy full path", TreeContextMenu::any,
                    sel -> fileOps.copyFullPaths(files(sel))),
                item("Copy trailhead path", TreeContextMenu::any,
                    sel -> fileOps.copyTrailheadPaths(files(sel)))
            ),
            List.of(
                item("New File...", TreeContextMenu::isSingle,
                    sel -> fileOps.createChild(file(sel), false)),
                item("New Folder...", TreeContextMenu::isSingle,
                    sel -> fileOps.createChild(file(sel), true))
            ),
            List.of(
                item("Rename...", TreeContextMenu::isSingle,
                    sel -> fileOps.rename(file(sel))),
                item("Delete", TreeContextMenu::any,
                    sel -> fileOps.delete(files(sel)))
            ),
            List.of(
                item("Refresh", TreeContextMenu::any,
                    sel -> sel.forEach(tree::refresh))
            )
        );
    }

    // --- Selection predicates ---

    private static boolean any(List<FileTreeNode> sel) {
        return !sel.isEmpty();
    }

    private static boolean isSingle(List<FileTreeNode> sel) {
        return sel.size() == 1;
    }

    private static boolean isSingleFile(List<FileTreeNode> sel) {
        return sel.size() == 1 && !sel.get(0).isDirectory();
    }

    private static boolean hasDirectory(List<FileTreeNode> sel) {
        return sel.stream().anyMatch(FileTreeNode::isDirectory);
    }

    // --- Selection accessors ---

    private static File file(List<FileTreeNode> sel) {
        return sel.get(0).getFile();
    }

    private static List<File> files(List<FileTreeNode> sel) {
        return sel.stream().map(FileTreeNode::getFile).collect(Collectors.toList());
    }

    private static List<FileTreeNode> directories(List<FileTreeNode> sel) {
        return sel.stream().filter(FileTreeNode::isDirectory).collect(Collectors.toList());
    }

    private static Entry item(String label, Predicate<List<FileTreeNode>> visible,
                              Consumer<List<FileTreeNode>> action) {
        return new Entry(sel -> label, visible, action);
    }

    /** A single menu item: its label (selection-dependent), applicability, and handler. */
    private record Entry(Function<List<FileTreeNode>, String> label,
                         Predicate<List<FileTreeNode>> visible,
                         Consumer<List<FileTreeNode>> action) {
    }
}
