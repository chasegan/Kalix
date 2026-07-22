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

    /** The view navigated into a directory (breadcrumb + state follow the view). */
    void directoryShown(Path dir);

    /** The selection changed; {@code entry} is null when nothing is selected. */
    void selectionChanged(FsEntry entry);

    /** The user activated an entry (double-click / Enter on a file): accept it. */
    void entryActivated(FsEntry entry);

    /** A listing failed; show the message in the dialog's status area. */
    void listingFailed(String message);
}
