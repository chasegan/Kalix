package com.kalix.ide.document;

import com.kalix.ide.constants.AppConstants;

import java.io.File;
import java.util.Locale;

/**
 * What kind of content a {@link KalixDocument} holds, decided from its file at
 * creation and fixed for the document's lifetime. Each kind maps to the subtype
 * {@link KalixDocument#createFor} builds: MODEL → {@link ModelDocument}
 * (editor + model + map), DATA → {@link DataDocument} (virtual data views and
 * the large-file gate — see {@code docs/data-file-viewer.md}), TEXT →
 * {@link TextDocument} (the editor alone).
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
