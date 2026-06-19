package com.kalix.ide.workspace.tree;

import com.kalix.ide.icons.MenuIcons;
import com.kalix.ide.utils.PlatformUtils;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
    private final TreeHost host;

    /** Item groups in display order; separators are drawn between non-empty groups. */
    private final List<List<Entry>> groups;

    TreeContextMenu(ProjectTree tree, TreeFileOperations fileOps, TreeHost host) {
        this.tree = tree;
        this.fileOps = fileOps;
        this.host = host;
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
                JMenuItem item;
                if (entry.checked != null) {
                    JCheckBoxMenuItem checkItem = new JCheckBoxMenuItem(entry.label.apply(selection));
                    checkItem.setSelected(entry.checked.test(selection));
                    item = checkItem;
                } else {
                    item = new JMenuItem(entry.label.apply(selection));
                }
                if (entry.icon != null) {
                    item.setIcon(entry.icon.get());
                }
                item.addActionListener(e -> entry.action.accept(selection));
                menu.add(item);
            }
        }
        return menu;
    }

    // --- Entry definitions ---

    private List<List<Entry>> buildEntries() {
        // Groups follow the context-menu skeleton (manifestos/context-menu-style.md §1):
        // primary -> context-specific -> external handoff -> clipboard -> create ->
        // modify -> destructive (isolated) -> view/state. Labels are sentence case.
        return List.of(
            List.of(
                item("Open", TreeContextMenu::isSingleFile,
                    sel -> host.openFile(file(sel)))
            ),
            List.of(
                item("Compare with active editor", TreeContextMenu::isSingleFile,
                    sel -> host.compareWithActiveEditor(file(sel))),
                item("Compare files", TreeContextMenu::isTwoFiles,
                    sel -> host.compareFiles(file(sel, 0), file(sel, 1)))
            ),
            List.of(
                item(revealLabel(), TreeContextMenu::isSingle,
                    sel -> fileOps.reveal(file(sel))),
                item("Launch Terminal", TreeContextMenu::isSingle,
                    sel -> fileOps.openTerminal(file(sel)))
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
                item("New file…", TreeContextMenu::isSingle,
                    sel -> fileOps.createChild(file(sel), false)),
                item("New folder…", TreeContextMenu::isSingle,
                    sel -> fileOps.createChild(file(sel), true))
            ),
            List.of(
                item("Rename…", TreeContextMenu::isSingle,
                    sel -> fileOps.rename(file(sel))),
                item("Duplicate…", TreeContextMenu::isSingle,
                    sel -> fileOps.duplicate(file(sel)))
            ),
            List.of(
                item("Delete", TreeContextMenu::any,
                    sel -> fileOps.delete(files(sel)), MenuIcons::delete)
            ),
            List.of(
                item("Expand children", TreeContextMenu::hasDirectory,
                    sel -> directories(sel).forEach(tree::expandSubtree)),
                item("Collapse children", TreeContextMenu::hasDirectory,
                    sel -> directories(sel).forEach(tree::collapseSubtree)),
                item("Collapse tree", TreeContextMenu::any,
                    sel -> tree.collapseAll()),
                checkbox("Show hidden files", TreeContextMenu::any,
                    sel -> host.isShowHiddenFiles(),
                    sel -> host.setShowHiddenFiles(!host.isShowHiddenFiles())),
                item("Refresh", TreeContextMenu::any,
                    sel -> sel.forEach(tree::refresh))
            )
        );
    }

    /**
     * The "reveal this file in the OS file manager" label, in each platform's own idiom
     * (manifesto §2.6): native feel outranks cross-platform verb parallelism here.
     */
    private static String revealLabel() {
        return switch (PlatformUtils.getCurrentPlatform()) {
            case MACOS -> "Reveal in Finder";
            case WINDOWS -> "Show in Explorer";
            case LINUX -> "Show in File Manager";
            default -> "Reveal in File Manager";
        };
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

    private static boolean isTwoFiles(List<FileTreeNode> sel) {
        return sel.size() == 2 && sel.stream().noneMatch(FileTreeNode::isDirectory);
    }

    private static boolean hasDirectory(List<FileTreeNode> sel) {
        return sel.stream().anyMatch(FileTreeNode::isDirectory);
    }

    // --- Selection accessors ---

    private static File file(List<FileTreeNode> sel) {
        return sel.get(0).getFile();
    }

    private static File file(List<FileTreeNode> sel, int index) {
        return sel.get(index).getFile();
    }

    private static List<File> files(List<FileTreeNode> sel) {
        return sel.stream().map(FileTreeNode::getFile).collect(Collectors.toList());
    }

    private static List<FileTreeNode> directories(List<FileTreeNode> sel) {
        return sel.stream().filter(FileTreeNode::isDirectory).collect(Collectors.toList());
    }

    private static Entry item(String label, Predicate<List<FileTreeNode>> visible,
                              Consumer<List<FileTreeNode>> action) {
        return new Entry(sel -> label, visible, null, action, null);
    }

    /** A plain item carrying a sparse landmark icon (manifesto §3); {@code icon} is lazy so it
     * picks up the current theme each time the menu is built. */
    private static Entry item(String label, Predicate<List<FileTreeNode>> visible,
                              Consumer<List<FileTreeNode>> action, Supplier<Icon> icon) {
        return new Entry(sel -> label, visible, null, action, icon);
    }

    /** A checkbox menu item: {@code checked} supplies its tick state when the menu is built. */
    private static Entry checkbox(String label, Predicate<List<FileTreeNode>> visible,
                                  Predicate<List<FileTreeNode>> checked,
                                  Consumer<List<FileTreeNode>> action) {
        return new Entry(sel -> label, visible, checked, action, null);
    }

    /**
     * A single menu item: its label (selection-dependent), applicability, optional checkbox state
     * ({@code checked}, null for a plain item), handler, and optional landmark icon supplier.
     */
    private record Entry(Function<List<FileTreeNode>, String> label,
                         Predicate<List<FileTreeNode>> visible,
                         Predicate<List<FileTreeNode>> checked,
                         Consumer<List<FileTreeNode>> action,
                         Supplier<Icon> icon) {
    }
}
