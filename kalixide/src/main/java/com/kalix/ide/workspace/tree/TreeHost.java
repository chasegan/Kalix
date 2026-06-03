package com.kalix.ide.workspace.tree;

import java.io.File;

/**
 * The capabilities the project tree needs from its host application. The tree package defines
 * this interface and depends only on it; the host (KalixIDE) implements it, keeping editor,
 * document, and diff concerns out of the tree package.
 *
 * <p>To add a tree interaction that needs something from the host, add a method here, implement
 * it on the host, and reference it from {@link TreeContextMenu} (or {@link ProjectTree}). No
 * constructor wiring changes along the way.
 */
public interface TreeHost {

    /** Opens the given file (e.g. as an editor tab). */
    void openFile(File file);

    /**
     * @return the active document's file, or {@code null} if untitled / none. Used to compute
     *         paths relative to the active document.
     */
    File activeFile();

    /** Opens a diff comparing the given file with the active editor's current text. */
    void compareWithActiveEditor(File file);
}
