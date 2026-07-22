package com.kalix.ide.filedialog;

import com.kalix.ide.utils.NaturalSortUtils;

import java.nio.file.Path;
import java.util.Comparator;

/**
 * One directory entry as shown in the file dialogs, with everything the UI needs captured
 * at enumeration time. This is the heart of the dialogs' performance story: all fields are
 * harvested from the single batched directory enumeration (network filesystems return
 * attributes with the listing), so rendering and sorting never trigger the per-file stat
 * calls that make {@code JFileChooser} crawl on network drives.
 *
 * @param path         the entry's path
 * @param name         the file name (cached; {@code Path.getFileName} allocates)
 * @param directory    whether the entry is a directory
 * @param size         file size in bytes (0 for directories)
 * @param lastModified epoch millis of last modification
 */
public record FsEntry(Path path, String name, boolean directory, long size, long lastModified) {

    /**
     * Directories first, then files; hidden entries first within each group; then natural
     * (number-aware) name order — the same deliberate ordering as the project tree
     * (file-tree-colour §2.7, {@code FileTreeNode.FILE_ORDER}).
     */
    public static final Comparator<FsEntry> ENTRY_ORDER = (a, b) -> {
        if (a.directory != b.directory) {
            return a.directory ? -1 : 1;
        }
        boolean ah = a.hidden();
        boolean bh = b.hidden();
        if (ah != bh) {
            return ah ? -1 : 1;
        }
        return NaturalSortUtils.naturalCompare(a.name, b.name);
    };

    /** Hidden by the dot-prefix convention (consistent with the project tree; zero I/O). */
    public boolean hidden() {
        return name.startsWith(".");
    }
}
