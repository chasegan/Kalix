package com.kalix.ide.workspace.tree;

import com.kalix.ide.io.FileCategory;

import com.kalix.ide.utils.NaturalSortUtils;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/**
 * A tree node backed by a {@link File}. Directory children are loaded lazily from disk on
 * first expansion (see {@link #ensureLoaded()}), so opening a large project folder does not
 * eagerly walk the whole tree.
 *
 * <p>Ordering: directories first, then files; within each group hidden (dot-prefixed) entries
 * first, then natural (number-aware, case-insensitive) name order. Hidden entries are omitted
 * unless {@code showHidden} returns true; the supplier is read at load time so the tree's
 * "show hidden files" toggle takes effect on the next (re)load of a directory without
 * reconstructing nodes.
 */
public class FileTreeNode extends DefaultMutableTreeNode {

    /**
     * Directories first, then files; hidden entries first within each group; then natural
     * (number-aware) order by name.
     *
     * <p>The hidden-first rule is deliberate, not the ASCII accident it is in most trees
     * ("." happening to sort before letters): it pins dotfiles to the conventional top slot
     * regardless of the natural sort's digits-before-letters rule (so {@code .git} sits above
     * {@code 2024_runs}). Position follows convention; de-emphasis is the colour tiers' job
     * (per file-tree-colour §2.7).
     */
    static final Comparator<File> FILE_ORDER = (a, b) -> {
        boolean ad = a.isDirectory();
        boolean bd = b.isDirectory();
        if (ad != bd) {
            return ad ? -1 : 1;
        }
        boolean ah = isHidden(a);
        boolean bh = isHidden(b);
        if (ah != bh) {
            return ah ? -1 : 1;
        }
        return NaturalSortUtils.naturalCompare(a.getName(), b.getName());
    };

    private final File file;
    /** Cached once at construction: a node's file identity never changes, and isLeaf()/
     *  getAllowsChildren() are queried by JTree per cell per repaint — avoid a disk stat each time. */
    private final boolean directory;
    /** Live "show hidden files" state, read each time a directory loads; shared with all nodes. */
    private final BooleanSupplier showHidden;
    private boolean loaded;
    /**
     * Whether this directory directly contains a model file — lazily computed and cached
     * ({@code null} = not yet computed), because the renderer asks per cell per repaint.
     * {@link ProjectTree} invalidates it when the watcher reports a change in this directory.
     */
    private Boolean containsModel;

    public FileTreeNode(File file, BooleanSupplier showHidden) {
        super(file);
        this.file = file;
        this.directory = file.isDirectory();
        this.showHidden = showHidden;
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
        boolean includeHidden = showHidden.getAsBoolean();
        for (File child : entries) {
            if (includeHidden || !isHidden(child)) {
                add(new FileTreeNode(child, showHidden));
            }
        }
    }

    static boolean isHidden(File f) {
        return f.getName().startsWith(".");
    }

    /**
     * Whether this directory directly contains a model ({@code *.ini}) file — the "model folder"
     * signal the renderer colours by. Always false for files.
     *
     * <p>The first ask costs one name listing of the directory (bounded by the rows actually
     * rendered, since only visible folders are asked); the result is cached until
     * {@link #invalidateContainsModelFile()}. Deliberately independent of the tree's children
     * (which may not be loaded) and of the hidden-files filter: a folder whose only model is a
     * dot-file still <em>contains</em> a model.
     */
    public boolean containsModelFile() {
        if (!directory) {
            return false;
        }
        if (containsModel == null) {
            containsModel = scanForModelFile();
        }
        return containsModel;
    }

    /** Drops the cached {@link #containsModelFile()} answer; recomputed on next ask. */
    void invalidateContainsModelFile() {
        containsModel = null;
    }

    private boolean scanForModelFile() {
        String[] names = file.list();
        if (names == null) {
            return false;
        }
        for (String name : names) {
            // The isFile stat only runs for *.ini-named entries, so the common case stays
            // a single directory listing; it guards against a directory named "x.ini".
            if (FileCategory.ofName(name) == FileCategory.MODEL && new File(file, name).isFile()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        // Root may have an empty name (e.g. a drive root); fall back to the path.
        String name = file.getName();
        return name.isEmpty() ? file.getAbsolutePath() : name;
    }
}
