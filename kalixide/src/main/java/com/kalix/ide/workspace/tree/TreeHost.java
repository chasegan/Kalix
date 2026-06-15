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

    /** Opens a diff comparing two files against each other ({@code left} vs {@code right}). */
    void compareFiles(File left, File right);

    /**
     * Shows or hides hidden (dot-prefixed) entries across the application, persisting the choice.
     * Routed through the host so the tree's context-menu toggle and the View-menu toggle share one
     * source of truth.
     */
    void setShowHiddenFiles(boolean show);

    /** @return whether hidden (dot-prefixed) entries are currently shown. */
    boolean isShowHiddenFiles();
}
