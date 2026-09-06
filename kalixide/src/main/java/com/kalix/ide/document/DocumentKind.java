package com.kalix.ide.document;

import com.kalix.ide.constants.AppConstants;

import java.io.File;
import java.util.Locale;

/**
 * What kind of content a {@link KalixDocument} holds, decided from its file at
 * creation and fixed for the document's lifetime.
 *
 * <p>MODEL documents get the full bundle (editor + {@code HydrologicalModel} +
 * {@code MapPanel}); DATA documents get the data-view bundle (a
 * {@code DataViewSession} feeding a table context view, with the text side
 * editable below the large-file gate and a virtual read-only view above it —
 * see {@code docs/data-file-viewer.md}); TEXT documents get the editor alone
 * and a {@code null} contextual view. Per the architecture doc, splitting
 * {@code KalixDocument} into subtypes waits until the DATA bundle grows
 * further (plot view, overlay editing).</p>
 */
public enum DocumentKind {

    /** A Kalix model (.ini, or a new untitled document): editor + model + map. */
    MODEL,

    /** A delimited data file (.csv, including .res.csv): text + virtual table. */
    DATA,

    /** Any other file: plain text editing, no contextual view. */
    TEXT;

    /**
     * The kind a document created for {@code file} should have. {@code null}
     * (a new untitled document) is a MODEL — new documents are seeded with the
     * default model text.
     */
    public static DocumentKind forFile(File file) {
        if (file == null) {
            return MODEL;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(AppConstants.INI_EXTENSION)) {
            return MODEL;
        }
        if (name.endsWith(".csv")) {
            return DATA; // covers .res.csv too; its header dispatch happens downstream
        }
        return TEXT;
    }
}
