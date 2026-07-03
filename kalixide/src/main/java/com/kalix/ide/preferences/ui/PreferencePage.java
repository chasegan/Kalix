package com.kalix.ide.preferences.ui;

import javax.swing.JComponent;

/**
 * A single page of the preferences dialog.
 *
 * <p>The dialog builds both its navigation tree and its card panel from an ordered
 * list of pages, so a page's tree position and its content can never drift apart:
 * selecting the page's tree node shows the page's component, mapped by identity.
 *
 * <p>Preferences in Kalix apply immediately — a page writes each setting as the
 * user changes it (and notifies interested parties via the narrow callbacks it was
 * constructed with). The only deferred work is free-text fields, which commit on
 * Enter, on focus-lost, and — via {@link #commitPendingEdits()} — on every
 * dialog-close path.
 */
public interface PreferencePage {

    /** Stable identifier for this page, used as its card-layout key. */
    String id();

    /**
     * The page's position in the navigation tree as a {@code /}-separated path,
     * e.g. {@code "Editor/Themes"}. The last segment is the page's leaf label;
     * preceding segments are category nodes, created (in encounter order) as needed.
     */
    String treePath();

    /** The component shown when this page is selected. */
    JComponent component();

    /**
     * Commits pending free-text edits that have not been written yet. Called on
     * every dialog-close path (Close button, Escape, window decoration). Commits
     * must skip writing when the value is unchanged, so calling this repeatedly
     * is free.
     */
    default void commitPendingEdits() {}

    /** Releases any listeners the page registered; called when the dialog is disposed. */
    default void dispose() {}
}
