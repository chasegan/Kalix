package com.kalix.ide.filedialog;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Rename and delete for entries inside the file dialogs, mirroring the project tree's
 * semantics ({@code TreeFileOperations}): the same rename collision guard (POSIX rename(2)
 * silently replaces an existing target, so an explicit existence check protects siblings)
 * and the same depth-first delete (children before parents, so a locked entry surfaces as
 * an error rather than a silently half-deleted folder).
 *
 * <p>Runs synchronously on the EDT: these are single-entry operations invoked from a modal
 * dialog whose views refresh explicitly on completion (the dialog has no filesystem
 * watcher). If bulk operations ever land here, adopt the tree's off-EDT pattern with a
 * completion callback instead.
 */
final class EntryOperations {

    private EntryOperations() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Prompts for a new name and renames the entry.
     *
     * @return true if the filesystem changed (so the caller should refresh)
     */
    static boolean rename(Component parent, FsEntry entry) {
        String name = (String) JOptionPane.showInputDialog(parent,
            "New name:", "Rename",
            JOptionPane.PLAIN_MESSAGE, null, null, entry.name());
        if (name == null || name.isBlank() || name.trim().equals(entry.name())) {
            return false;
        }
        Path target = entry.path().resolveSibling(name.trim());
        if (Files.exists(target)) {
            JOptionPane.showMessageDialog(parent,
                "A file or folder named \"" + name.trim() + "\" already exists.",
                "Rename Failed", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            Files.move(entry.path(), target);
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                "Could not rename \"" + entry.name() + "\": " + ex.getMessage(),
                "Rename Failed", JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    /**
     * Confirms and deletes the entry (recursively for folders).
     *
     * @return true if a deletion was attempted (so the caller should refresh)
     */
    static boolean delete(Component parent, FsEntry entry) {
        String message = "Delete \"" + entry.name() + "\"?"
            + (entry.directory() ? "\n\nThis folder and its contents will be deleted." : "");
        int choice = JOptionPane.showConfirmDialog(parent, message,
            "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return false;
        }
        try {
            if (entry.directory()) {
                try (Stream<Path> walk = Files.walk(entry.path())) {
                    List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                    for (Path path : paths) {
                        Files.delete(path);
                    }
                }
            } else {
                Files.delete(entry.path());
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(parent,
                "Failed to delete \"" + entry.name() + "\": " + ex.getMessage(),
                "Delete Failed", JOptionPane.ERROR_MESSAGE);
        }
        return true; // something may have changed even on partial failure; refresh regardless
    }
}
