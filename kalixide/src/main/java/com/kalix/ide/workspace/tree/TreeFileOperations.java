package com.kalix.ide.workspace.tree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The side-effecting file operations behind the project tree's context menu and keyboard
 * shortcuts: reveal-in-OS, create, rename, delete, and copy-path (full / relative / trailhead).
 *
 * <p>Kept separate from {@link ProjectTree} so the tree component stays focused on view, model,
 * and event wiring. Operations are selection-aware where it makes sense (copy and delete accept
 * a list); the tree's filesystem watcher reflects create/rename/delete back into the model, so
 * none of these methods touch the tree model directly.
 *
 * <p>All methods run on the EDT (invoked from menu/key handlers) and may show dialogs.
 */
class TreeFileOperations {

    private static final Logger logger = LoggerFactory.getLogger(TreeFileOperations.class);

    private final Component parent;
    /** Supplies the active document's file (or null if untitled/none), for relative paths. */
    private final Supplier<File> activeFileSupplier;

    TreeFileOperations(Component parent, Supplier<File> activeFileSupplier) {
        this.parent = parent;
        this.activeFileSupplier = activeFileSupplier;
    }

    // --- Reveal ---

    void reveal(File file) {
        try {
            File target = file.isDirectory() ? file : file.getParentFile();
            com.kalix.ide.utils.FileManagerLauncher.openFileManagerAt(target);
        } catch (Exception ex) {
            logger.warn("Failed to reveal {}: {}", file, ex.getMessage());
        }
    }

    // --- Create ---

    /**
     * Creates a new file or folder next to {@code anchor}: inside it if it is a directory, else
     * in its parent. Prompts for the name; the watcher adds the resulting node.
     */
    void createChild(File anchor, boolean directory) {
        File dir = anchor.isDirectory() ? anchor : anchor.getParentFile();
        if (dir == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(parent,
            directory ? "New folder name:" : "New file name:",
            directory ? "New Folder" : "New File",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        File target = new File(dir, name.trim());
        try {
            boolean created = directory ? target.mkdir() : target.createNewFile();
            if (!created) {
                JOptionPane.showMessageDialog(parent,
                    "Could not create \"" + name + "\" (it may already exist).",
                    "Create Failed", JOptionPane.WARNING_MESSAGE);
            }
            // The watcher will add the node; no manual model change required.
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                "Failed to create \"" + name + "\": " + ex.getMessage(),
                "Create Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // --- Rename ---

    void rename(File file) {
        String name = (String) JOptionPane.showInputDialog(parent,
            "New name:", "Rename",
            JOptionPane.PLAIN_MESSAGE, null, null, file.getName());
        if (name == null || name.isBlank() || name.equals(file.getName())) {
            return;
        }
        File target = new File(file.getParentFile(), name.trim());
        if (!file.renameTo(target)) {
            JOptionPane.showMessageDialog(parent,
                "Could not rename \"" + file.getName() + "\".",
                "Rename Failed", JOptionPane.WARNING_MESSAGE);
        }
        // The watcher reports delete + create; the tree re-syncs automatically.
    }

    // --- Delete ---

    /**
     * Deletes the given files after a single confirmation. Entries whose ancestor is also in the
     * selection are dropped first (deleting the ancestor already removes them), so a folder and a
     * file inside it can be selected together without a spurious "already gone" error.
     */
    void delete(List<File> files) {
        List<File> targets = withoutDescendants(files);
        if (targets.isEmpty()) {
            return;
        }
        if (!confirmDelete(targets)) {
            return;
        }
        for (File file : targets) {
            deleteOne(file);
        }
        // The watcher reports the deletions; the tree re-syncs automatically.
    }

    private boolean confirmDelete(List<File> targets) {
        String message;
        if (targets.size() == 1) {
            File file = targets.get(0);
            message = "Delete \"" + file.getName() + "\"?"
                + (file.isDirectory() ? "\n\nThis folder and its contents will be deleted." : "");
        } else {
            boolean anyDir = targets.stream().anyMatch(File::isDirectory);
            message = "Delete " + targets.size() + " items?"
                + (anyDir ? "\n\nFolders and their contents will be deleted." : "");
        }
        int choice = JOptionPane.showConfirmDialog(parent, message,
            "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private void deleteOne(File file) {
        try {
            if (file.isDirectory()) {
                // Delete depth-first (children before parents). Files.delete throws on
                // failure, so a locked/read-only entry surfaces as an error rather than a
                // silently half-deleted folder.
                try (Stream<Path> walk = Files.walk(file.toPath())) {
                    List<Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                    for (Path path : paths) {
                        Files.delete(path);
                    }
                }
            } else {
                Files.delete(file.toPath());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                "Failed to delete \"" + file.getName() + "\": " + ex.getMessage(),
                "Delete Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Drops files that have an ancestor also present in the list. */
    static List<File> withoutDescendants(List<File> files) {
        List<Path> paths = files.stream()
            .map(f -> f.toPath().toAbsolutePath().normalize())
            .collect(Collectors.toList());
        List<File> result = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path candidate = paths.get(i);
            boolean hasAncestor = false;
            for (int j = 0; j < paths.size(); j++) {
                Path other = paths.get(j);
                if (i != j && !candidate.equals(other) && candidate.startsWith(other)) {
                    hasAncestor = true;
                    break;
                }
            }
            if (!hasAncestor) {
                result.add(files.get(i));
            }
        }
        return result;
    }

    // --- Copy path ---

    /** Copies the absolute paths, one per line. */
    void copyFullPaths(List<File> files) {
        copyToClipboard(files.stream()
            .map(File::getAbsolutePath)
            .collect(Collectors.joining("\n")));
    }

    /** Copies the paths relative to the active file's directory, one per line. */
    void copyRelativePaths(List<File> files) {
        try {
            copyToClipboard(files.stream()
                .map(this::relativePath)
                .collect(Collectors.joining("\n")));
        } catch (IllegalStateException ex) {
            showPathError(ex.getMessage());
        }
    }

    /** Copies the Kalix trailhead ({@code ^/...}) paths, one per line. */
    void copyTrailheadPaths(List<File> files) {
        try {
            copyToClipboard(files.stream()
                .map(f -> toTrailhead(relativePath(f)))
                .collect(Collectors.joining("\n")));
        } catch (IllegalStateException ex) {
            showPathError(ex.getMessage());
        }
    }

    /**
     * Computes the forward-slash path to {@code target} relative to the active file's directory.
     *
     * @throws IllegalStateException if there is no saved active file, or the paths share no
     *                               common root (so no relative path exists)
     */
    private String relativePath(File target) {
        File activeFile = activeFileSupplier.get();
        if (activeFile == null) {
            throw new IllegalStateException(
                "The active file hasn't been saved yet, so a relative path can't be computed.");
        }
        File baseDir = activeFile.getParentFile();
        if (baseDir == null) {
            throw new IllegalStateException("The active file has no parent directory.");
        }
        Path base = baseDir.toPath().toAbsolutePath().normalize();
        Path dest = target.toPath().toAbsolutePath().normalize();
        try {
            return base.relativize(dest).toString().replace(File.separator, "/");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                "Can't compute a relative path between different filesystem roots.");
        }
    }

    /**
     * Converts a relative path to a Kalix trailhead path: strips the leading run of "./" and
     * "../" segments and prepends "^/" (so the result always starts with "^/").
     */
    static String toTrailhead(String relative) {
        String s = relative;
        while (s.startsWith("./") || s.startsWith("../")) {
            s = s.startsWith("../") ? s.substring(3) : s.substring(2);
        }
        return "^/" + s;
    }

    private static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void showPathError(String message) {
        JOptionPane.showMessageDialog(parent, message, "Copy Path", JOptionPane.WARNING_MESSAGE);
    }
}
