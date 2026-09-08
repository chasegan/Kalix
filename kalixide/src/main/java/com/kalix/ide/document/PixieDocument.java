package com.kalix.ide.document;

import com.kalix.ide.dataview.PixieDataPanel;
import com.kalix.ide.dataview.PixieDataSession;

import java.awt.Component;
import java.io.File;
import java.util.Locale;

/**
 * A Pixie dataset ({@code .pxt} manifest + {@code .pxb} binary): the text side
 * is the manifest itself — small, human-readable, editable — and the
 * contextual view is the decoded table (the honest rendering of the binary
 * half, which has no text form). Pixie inverts the CSV physics: everything is
 * decoded once into memory (gated before decode), so there is no large-file
 * text gate here — the manifest is always tiny.
 *
 * <p>A document opened from the {@code .pxb} itself (no manifest found beside
 * it) is read-only: its editor buffer never held the binary's bytes, and an
 * empty editable buffer over a real file is one Save All from truncating it.
 */
public class PixieDocument extends KalixDocument {

    private final PixieDataSession session;
    private final PixieDataPanel dataPanel;

    /** Whether the backing file is the editable manifest (vs the binary half). */
    private final boolean manifestBacked;

    public PixieDocument(File file) {
        super(DocumentKind.DATA);
        if (file == null) {
            throw new IllegalArgumentException("Pixie documents need a backing file");
        }
        setFile(file);
        this.manifestBacked = file.getName().toLowerCase(Locale.ROOT).endsWith(".pxt");
        this.session = new PixieDataSession(file);
        this.dataPanel = new PixieDataPanel(session);
    }

    /** The contextual view is the decoded table (the plot mount arrives with it). */
    @Override
    public Component getContextView() {
        return dataPanel;
    }

    @Override
    public boolean isEditable() {
        return manifestBacked; // the .pxt is the modeller's to edit; a .pxb-backed tab is not
    }

    /** The manifest's bytes changed (a save here, or an external write): re-decode. */
    @Override
    public void refreshDataViewFromDisk() {
        session.reloadFromDisk();
    }

    @Override
    public void dispose() {
        super.dispose();
        session.dispose();
    }

    /** This document's session — package-private, for tests. */
    PixieDataSession getSession() {
        return session;
    }
}
