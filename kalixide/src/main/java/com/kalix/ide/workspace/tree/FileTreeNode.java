package com.kalix.ide.workspace.tree;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A tree node backed by a {@link File}. Directory children are loaded lazily from disk on
 * first expansion (see {@link #ensureLoaded()}), so opening a large project folder does not
 * eagerly walk the whole tree.
 *
 * <p>Ordering: directories first, then files, each alphabetically (case-insensitive).
 * Hidden entries (names starting with ".") are omitted.
 */
public class FileTreeNode extends DefaultMutableTreeNode {

    /** Directories first, then files, each case-insensitively by name. */
    static final Comparator<File> FILE_ORDER = (a, b) -> {
        boolean ad = a.isDirectory();
        boolean bd = b.isDirectory();
        if (ad != bd) {
            return ad ? -1 : 1;
        }
        return a.getName().compareToIgnoreCase(b.getName());
    };

    private final File file;
    /** Cached once at construction: a node's file identity never changes, and isLeaf()/
     *  getAllowsChildren() are queried by JTree per cell per repaint — avoid a disk stat each time. */
    private final boolean directory;
    private boolean loaded;

    public FileTreeNode(File file) {
        super(file);
        this.file = file;
        this.directory = file.isDirectory();
    }

    public File getFile() {
        return file;
    }

    /** @return whether this node is a directory (cached at construction) */
    public boolean isDirectory() {
        return directory;
    }

    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public boolean isLeaf() {
        return !directory;
    }

    @Override
    public boolean getAllowsChildren() {
        return directory;
    }

    /**
     * Loads this directory's children from disk if not already loaded. No-op for files.
     */
    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!directory) {
            return;
        }
        removeAllChildren();
        File[] entries = file.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries, FILE_ORDER);
        for (File child : entries) {
            if (!isHidden(child)) {
                add(new FileTreeNode(child));
            }
        }
    }

    static boolean isHidden(File f) {
        return f.getName().startsWith(".");
    }

    @Override
    public String toString() {
        // Root may have an empty name (e.g. a drive root); fall back to the path.
        String name = file.getName();
        return name.isEmpty() ? file.getAbsolutePath() : name;
    }
}
