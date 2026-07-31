package com.kalix.ide.filedialog;

import java.nio.file.Path;

/**
 * What a listing view (list or columns) needs from the dialog that hosts it. The dialog is
 * the controller: views render entries and report gestures; the dialog owns navigation
 * state, filtering policy, and accept/cancel semantics.
 */
interface FileViewHost {

    /** The shared background lister (one per dialog). */
    DirectoryLister lister();

    /** Whether hidden (dot-prefixed) entries are currently shown. */
    boolean showHidden();

    /** Whether an entry passes the active extension filter (directories always do). */
    boolean passesFilter(FsEntry entry);

    /** Whether the dialog is choosing a folder (files render disabled and unselectable). */
    boolean directoriesOnly();

    /** Whether several entries may be selected at once (open mode only). */
    boolean allowsMultiSelect();

    /** The view navigated into a directory (breadcrumb + state follow the view). */
    void directoryShown(Path dir);

    /**
     * The selection changed; {@code entry} is null when nothing is selected. Under
     * multi-select {@code entry} is the lead of the selection — the host reads the rest
     * back from the active view.
     */
    void selectionChanged(FsEntry entry);

    /** The user activated an entry (double-click / Enter on a file): accept it. */
    void entryActivated(FsEntry entry);

    /** A listing failed; show the message in the dialog's status area. */
    void listingFailed(String message);

    /** Right-click on an entry: show the rename/delete context menu at the given point. */
    void showEntryContextMenu(FsEntry entry, java.awt.Component invoker, int x, int y);

    /**
     * Right-click on empty space: the subject is the containing folder being viewed
     * (context-menu-style §4) — the current directory, or a specific column's directory.
     */
    void showContainerContextMenu(java.nio.file.Path dir, java.awt.Component invoker, int x, int y);
}
