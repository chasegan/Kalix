package com.kalix.ide.document;

import com.kalix.ide.constants.AppConstants;

import java.io.File;
import java.util.Locale;

/**
 * What kind of content a {@link KalixDocument} holds, decided from its file at
 * creation and fixed for the document's lifetime.
 *
 * <p>MODEL documents get the full bundle (editor + {@code HydrologicalModel} +
 * {@code MapPanel}); TEXT documents get the editor alone and a {@code null}
 * contextual view. Future kinds (e.g. DATA for CSV, with a table/plot context
 * view) slot in here; per the architecture doc, splitting {@code KalixDocument}
 * into subtypes waits until such a kind actually exists.</p>
 */
public enum DocumentKind {

    /** A Kalix model (.ini, or a new untitled document): editor + model + map. */
    MODEL,

    /** Any other file: plain text editing, no model, no contextual view. */
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
        return name.endsWith(AppConstants.INI_EXTENSION) ? MODEL : TEXT;
    }
}
