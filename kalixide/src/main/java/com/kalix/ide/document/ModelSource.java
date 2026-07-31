package com.kalix.ide.document;

import java.io.File;

/**
 * A model that an auxiliary window (the Optimiser, and in future the Run Manager)
 * can target: its text, the folder relative paths resolve against, and the name to
 * show for it.
 *
 * <p>This is the narrow read-only face of a {@link KalixDocument}. Auxiliary windows
 * take {@code ModelSource} rather than {@code KalixDocument} so they cannot reach the
 * editor, map panel or dirty state — a window that only needs to <em>read</em> a model
 * should not be able to mutate the document graph.</p>
 *
 * <p><b>Identity vs label</b> (per {@code manifestos/identity-and-labels.md}): the
 * implementing object reference <em>is</em> the identity token — opaque, typed, and
 * stable across rename and Save-As. Never key a collection by
 * {@link #getDisplayName()}; it is a projection, and
 * {@link DocumentLabels#labelFor} may render the very same source differently as
 * other models open and close around it.</p>
 */
public interface ModelSource {

    /**
     * The bare name for this model — a file's basename, or "Untitled".
     *
     * <p>Not unique: two open models can share a basename. Use
     * {@link DocumentLabels#labelFor(ModelSource, java.util.List)} for anything a
     * user reads.</p>
     */
    String getDisplayName();

    /**
     * The file backing this model, or {@code null} if it has never been saved.
     *
     * <p>Unlike the object reference, this survives the model being closed and reopened
     * — reopening a file builds a <em>new</em> document, so identity alone cannot
     * recognise it. Callers that must find "the same file again later" fall back to
     * this; callers that just need "this exact open model" use the reference.</p>
     */
    File getFile();

    /**
     * The folder relative paths in this model resolve against, or {@code null} if the
     * model has never been saved.
     *
     * <p>A {@code null} here means the model cannot be optimised: kalixcli is given
     * this as its working directory, and observed-data paths in an optimisation
     * config are resolved relative to it.</p>
     */
    File getWorkingDirectory();

    /** The model's current text, as a snapshot at the moment of the call. */
    String getText();

    /**
     * Whether this model can be used as an optimisation target. An unsaved model has
     * no folder to resolve data paths against, so it cannot.
     *
     * @see #getWorkingDirectory()
     */
    default boolean isOptimisable() {
        return getWorkingDirectory() != null;
    }
}
