package com.kalix.ide.document;

/**
 * A plain text document (any file that is neither a model nor data): the editor
 * alone — no model, no map, no contextual view (the region collapses). The base
 * class already is exactly this; the subtype exists so every document names its
 * kind explicitly.
 */
public class TextDocument extends KalixDocument {

    public TextDocument() {
        super(DocumentKind.TEXT);
    }
}
