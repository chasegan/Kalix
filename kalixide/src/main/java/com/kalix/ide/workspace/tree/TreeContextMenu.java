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
 * <p>
 * Each item is a declarative {@link Entry}: a label, a predicate deciding
 * whether it applies
 * to the selection, and a handler acting on the selection. Entries are
 * organised into groups;
 * {@link #build} renders only the entries whose predicate passes and emits a
 * separator only
 * <em>between</em> non-empty groups — so items that drop out for a
 * multi-selection never leave a
 * stray or doubled separator behind.
 *
 * <p>
 * Adding a future interaction is a matter of adding one {@link Entry} with its
 * applicability
 * rule; the rendering and separator logic need no changes.
 */
public class TreeContextMenu {

    private final ProjectTree tree;
    private final TreeFileOperations fileOps;
    private final TreeHost host;

    TreeContextMenu(ProjectTree tree, TreeFileOperations fileOps, TreeHost host) {
        this.tree = tree;
        this.fileOps = fileOps;
        this.host = host;
    }

    /**
     * Private enum tracks build context of context menu.
     */
    enum BuildContext {
        FileTree(),
        EditorTab(),
        Root()
    }

    /**
     * Builds the popup for the given selection in row order, or returns null if it is empty.
     * Backwards compatible single-parameter version (called from file tree).
     */
    JPopupMenu build(List<FileTreeNode> selection) {
        return build(selection, BuildContext.FileTree);
    }

    /**
     * Builds the popup for the given selection in row order, or returns null if it is empty.
     * Used to build from editor tab right click context menu.
     */
    JPopupMenu buildFromEditorTab(List<FileTreeNode> selection) {
        return build(selection, BuildContext.EditorTab);
    }

    /**
     * Builds the popup from root.
     */
    JPopupMenu buildFromRoot(FileTreeNode root) {
        var l = new java.util.ArrayList<FileTreeNode>();
        l.add(root);
        return build(l, BuildContext.Root);
    }

    /**
     * Builds the popup for the given selection (in row order), or returns null if
     * it is empty.
     */
    JPopupMenu build(List<FileTreeNode> selection, BuildContext buildContext) {
        // Item groups in display order; separators are drawn between non-empty groups.
        List<List<Entry>> groups = buildEntries(buildContext);
        if (selection.isEmpty()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        boolean firstGroup = true;
        for (List<Entry> group : groups) {
            List<Entry> visible = group.stream()
                    .filter(e -> e.visible.test(selection))
                    .toList();
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

    private List<List<Entry>> buildEntries(BuildContext context) {
        // Groups follow the context-menu skeleton (manifestos/context-menu-style.md
        // §1):
        // primary -> context-specific -> external handoff -> clipboard -> create ->
        // modify -> destructive (isolated) -> view/state. Labels are sentence case.
        return List.of(
            // Primary
            List.of(
                item(
                    "Open",
                    (sel -> isSingleNonZipFile(sel) && context == BuildContext.FileTree),
                    sel -> host.openFile(file(sel))
                )),
            // Context-specific
            List.of(
                item(
                    "Compare with active editor",
                    sel -> (isSingleNonZipFile(sel) && context == BuildContext.FileTree),
                    sel -> host.compareWithActiveEditor(file(sel))
                ),
                item(
                    "Compare files", TreeContextMenu::isTwoFiles,
                    sel -> host.compareFiles(file(sel, 0), file(sel, 1))
                ),
                item(
                    "Unzip", TreeContextMenu::isSingleZip,
                    sel -> fileOps.unzipFile(file(sel))
                )
            ),
            // External handoff
            List.of(
                // Reveal in platform file manager
                item(
                    revealLabel(), TreeContextMenu::isSingle,
                    sel -> fileOps.reveal(file(sel))
                ),
                item(
                    "Launch Terminal", TreeContextMenu::isSingle,
                    sel -> fileOps.openTerminal(file(sel))
                )
            ),
            // Clipboard
            List.of(
                item(
                    "Copy relative path", TreeContextMenu::any,
                    sel -> fileOps.copyRelativePaths(files(sel))
                ),
                item(
                    "Copy full path", TreeContextMenu::any,
                    sel -> fileOps.copyFullPaths(files(sel))
                ),
                item(
                    "Copy trailhead path", TreeContextMenu::any,
                    sel -> fileOps.copyTrailheadPaths(files(sel))
                )
            ),
            // Create
            List.of(
                item(
                    "New file…", (sel -> isSingle(sel) && context == BuildContext.FileTree),
                    sel -> fileOps.createChild(file(sel), false)
                ),
                item(
                    "New folder…", (sel -> isSingle(sel) & context == BuildContext.FileTree),
                    sel -> fileOps.createChild(file(sel), true)
                )
            ),
            // Modify — identity-changing verbs never apply to the root the user is
            // standing in (context-menu-style §4), which empty-space clicks select.
            List.of(
                item(
                    "Rename…", sel -> isSingle(sel) && noneIsRoot(sel),
                    sel -> fileOps.rename(file(sel))
                ),
                item(
                    "Duplicate…", sel -> isSingle(sel) && noneIsRoot(sel),
                    sel -> fileOps.duplicate(file(sel))
                ),
                // Derives a new archive from the selection, like Duplicate derives a copy —
                // and keeps it from surfacing as a folder's first (= primary-looking) item.
                item(
                    "Zip",
                    (sel -> (isNotSingleZip(sel) && context == BuildContext.FileTree)),
                    sel -> fileOps.zipFiles(files(sel), tree.getRootFile())
                )
            ),
            // Destructive (isolated) — never the root (context-menu-style §4).
            List.of(
                item(
                    "Delete", sel -> any(sel) && noneIsRoot(sel),
                    sel -> fileOps.delete(files(sel)), MenuIcons::delete
                )),
            // View
            List.of(
                item(
                    "Expand children", (sel -> hasDirectory(sel) && context == BuildContext.FileTree),
                    sel -> directories(sel).forEach(tree::expandSubtree)
                ),
                item(
                    "Collapse children", (sel -> hasDirectory(sel) && context == BuildContext.FileTree),
                    sel -> directories(sel).forEach(tree::collapseSubtree)
                ),
                item(
                    "Collapse tree",
                    (sel -> any(sel) && (context == BuildContext.FileTree || context == BuildContext.Root)),
                    sel -> tree.collapseAll()
                ),
                checkbox(
                    "Show hidden files",
                    (sel -> any(sel) &&
                        (context == BuildContext.FileTree || context == BuildContext.Root)),
                    sel -> host.isShowHiddenFiles(),
                    sel -> host.setShowHiddenFiles(!host.isShowHiddenFiles())
                ),
                item(
                    "Refresh",
                    (sel -> any(sel) && (context == BuildContext.FileTree || context == BuildContext.Root)),
                    sel -> sel.forEach(tree::refresh)
                )
            )
        );
    }

    /**
     * The "reveal this file in the OS file manager" label, in each platform's own
     * idiom
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

    private static boolean isSingleNonZipFile(List<FileTreeNode> sel) {
        return sel.size() == 1 && !sel.getFirst().isDirectory() && isNotZip(sel);
    }

    private static boolean isTwoFiles(List<FileTreeNode> sel) {
        return sel.size() == 2 && sel.stream().noneMatch(FileTreeNode::isDirectory) && isNotZip(sel);
    }

    private static boolean hasDirectory(List<FileTreeNode> sel) {
        return sel.stream().anyMatch(FileTreeNode::isDirectory);
    }

    private static boolean isSingleZip(List<FileTreeNode> sel) {
        return sel.size() == 1 && TreeFileOperations.isZip(file(sel));
    }

    /** True when the selection contains no root node — the empty-space subject. */
    private boolean noneIsRoot(List<FileTreeNode> sel) {
        return sel.stream().noneMatch(tree::isRoot);
    }

    private static boolean isNotSingleZip(List<FileTreeNode> sel) {
        return !sel.isEmpty() && any(sel) && isNotZip(sel);
    }

    /**
     * Return true if none of {@code sel} are zip files
     */
    private static boolean isNotZip(List<FileTreeNode> sel) {
        return sel.stream().noneMatch((x) -> TreeFileOperations.isZip(x.getFile()));
    }

    // --- Selection accessors ---

    private static File file(List<FileTreeNode> sel) {
        return sel.getFirst().getFile();
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

    /**
     * A plain item carrying a sparse landmark icon (manifesto §3); {@code icon} is
     * lazy so it
     * picks up the current theme each time the menu is built.
     */
    private static Entry item(String label, Predicate<List<FileTreeNode>> visible,
            Consumer<List<FileTreeNode>> action, Supplier<Icon> icon) {
        return new Entry(sel -> label, visible, null, action, icon);
    }

    /**
     * A checkbox menu item: {@code checked} supplies its tick state when the menu
     * is built.
     */
    private static Entry checkbox(String label, Predicate<List<FileTreeNode>> visible,
            Predicate<List<FileTreeNode>> checked,
            Consumer<List<FileTreeNode>> action) {
        return new Entry(sel -> label, visible, checked, action, null);
    }

    /**
     * A single menu item: its label (selection-dependent), applicability, optional
     * checkbox state
     * ({@code checked}, null for a plain item), handler, and optional landmark icon
     * supplier.
     */
    private record Entry(Function<List<FileTreeNode>, String> label,
            Predicate<List<FileTreeNode>> visible,
            Predicate<List<FileTreeNode>> checked,
            Consumer<List<FileTreeNode>> action,
            Supplier<Icon> icon) {
    }
}
