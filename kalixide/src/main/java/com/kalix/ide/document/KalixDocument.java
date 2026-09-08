package com.kalix.ide.document;

import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.model.HydrologicalModel;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.io.File;
import java.util.function.Supplier;

/**
 * A single open document — one per tab — and the bundle of state and views that
 * belong to it. Every document owns its backing {@link File} (nullable =
 * untitled) and the {@link EnhancedTextEditor} editing its text; the subtypes
 * add their kind's bundle:
 *
 * <ul>
 * <li>{@link ModelDocument} — a Kalix model: parsed {@link HydrologicalModel} +
 *     {@link MapPanel}, text↔map sync and per-document auto-zoom;</li>
 * <li>{@link DataDocument} — a delimited data file: virtual data views and the
 *     large-file editable gate (see {@code docs/data-file-viewer.md});</li>
 * <li>{@link TextDocument} — plain text: the editor alone.</li>
 * </ul>
 *
 * <p>{@link #createFor(File)} is the single place where "what does opening this
 * file mean" is decided: {@link DocumentKind#forFile} names the kind, this maps
 * it to a subtype. Because each document owns its own editor instance, undo/redo
 * history is naturally per-document via RSyntaxTextArea's native undo stack —
 * no shared or custom {@code UndoManager} is involved.
 *
 * <p>The workspace layer builds each tab's content from the polymorphic views
 * here — {@link #getPrimaryView()} plus {@link #getContextView()} when present
 * (see {@code docs/multi-document-architecture.md}, Addendum) — and the save
 * paths consult {@link #isEditable()}.
 *
 * <p>Application-level concerns (status bar, title bar, file watching, theme
 * registration, linter/autocomplete service wiring) are intentionally <em>not</em>
 * owned here — they observe or attach to the active document from {@code KalixIDE}.
 */
public abstract class KalixDocument implements OpenModel {

    private final DocumentKind kind;
    private final EnhancedTextEditor editor;

    /** Backing file, or {@code null} for an untitled document. */
    private File file;

    // --- Memoized linter parse (EDT-confined) ---

    /** Bumped on every document edit; keys the memoized linter parse. */
    private long modificationCount = 0;

    /** {@link #modificationCount} at which {@link #cachedParsedModel} was computed, or -1. */
    private long parsedModificationCount = -1;

    /** Memoized linter parse of the editor text; {@code null} also caches a parse failure. */
    private INIModelParser.ParsedModel cachedParsedModel;

    /**
     * Creates the document's editor and its edit wiring. Subtype constructors
     * build their kind's bundle on top. Application-level features that depend
     * on shared services (linter, autocomplete, context commands) are attached
     * to {@link #getEditor()} by the host after construction.
     */
    protected KalixDocument(DocumentKind kind) {
        this.kind = kind;
        this.editor = new EnhancedTextEditor();
        // Per-filetype syntax: the editor defaults to the Kalix INI grammar
        // (models). TEXT drops to plain — a .txt file is not a model — and
        // DataDocument installs the dialect-aware CSV token maker once its
        // session (the dialect authority) exists.
        if (kind == DocumentKind.TEXT) {
            editor.usePlainText();
        }
        // Re-parse on every text change (coalesced by ModelDocument; a no-op for
        // other kinds). The modification count keys the memoized linter parse
        // handed out by getModelSupplier().
        editor.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onDocumentEdit();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onDocumentEdit();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onDocumentEdit();
            }
        });
    }

    /**
     * Creates the right document subtype for the file — the single place where
     * "what does opening this file mean" is decided. {@code null} (a new
     * untitled document) creates a model, seeded by the caller.
     */
    public static KalixDocument createFor(File file) {
        return switch (DocumentKind.forFile(file)) {
            case MODEL -> new ModelDocument();
            case DATA -> {
                // Format dispatch within the kind, like .res.csv's downstream
                // header dispatch: the Pixie pair gets its decode-based bundle.
                String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                yield name.endsWith(".pxt") || name.endsWith(".pxb")
                    ? new PixieDocument(file) : new DataDocument(file);
            }
            case TEXT -> new TextDocument();
        };
    }

    /** Reacts to a single document edit: invalidates the memoized parse, queues a re-parse. */
    private void onDocumentEdit() {
        modificationCount++;
        parseModelFromText(false);
    }

    /**
     * Parses the current editor text into this document's model, if it has one.
     * A safe no-op for kinds without a model — every open path calls this.
     * {@link ModelDocument} overrides with the real (coalesced) parse.
     *
     * @param autoZoomToFit if true, zoom the map to fit after parsing (used on file loads)
     */
    public void parseModelFromText(boolean autoZoomToFit) {
        // Only a model document has a model to parse into.
    }

    /**
     * Returns a supplier that parses the current editor text into a linter
     * {@link INIModelParser.ParsedModel}, for context commands and auto-complete.
     * Returns {@code null} on parse failure.
     *
     * <p>Memoized on the document's modification count: several consumers (context
     * menu, auto-complete, tooltips) call their supplier in the same EDT breath —
     * e.g. a single right-click used to trigger three identical full parses. The
     * parse now runs once per document revision. EDT-confined.</p>
     */
    public Supplier<INIModelParser.ParsedModel> getModelSupplier() {
        return () -> {
            if (parsedModificationCount != modificationCount) {
                try {
                    cachedParsedModel = INIModelParser.parse(editor.getText());
                } catch (Exception e) {
                    cachedParsedModel = null;
                }
                parsedModificationCount = modificationCount;
            }
            return cachedParsedModel;
        };
    }

    // --- Views (subtypes override what their bundle provides) ---

    public EnhancedTextEditor getEditor() {
        return editor;
    }

    /** This document's kind, fixed at creation. */
    public final DocumentKind getKind() {
        return kind;
    }

    /** Whether this document holds a Kalix model (and therefore a map). */
    public final boolean isModel() {
        return kind == DocumentKind.MODEL;
    }

    /** The map visualising this document's model, or {@code null} for non-model kinds. */
    public MapPanel getMapPanel() {
        return null;
    }

    /** This document's parsed model, or {@code null} for non-model kinds. */
    public HydrologicalModel getModel() {
        return null;
    }

    /**
     * The component shown in the contextual view for this document, or
     * {@code null} if it has none (the region collapses). The map for a model
     * document, the virtual table for a data document.
     */
    public Component getContextView() {
        return null;
    }

    /**
     * The main (left) component of this document's tab: the text editor, unless
     * a subtype substitutes a virtual view (a large read-only data document).
     */
    public JComponent getPrimaryView() {
        return editor;
    }

    /** The component that should receive focus when this document's tab activates. */
    public Component getPrimaryFocusComponent() {
        return editor.getTextArea();
    }

    /**
     * Whether this document's text can be edited and saved. False only for a
     * data document above the editable gate — its tab is a viewer, and save
     * paths must refuse rather than write an empty buffer over the file.
     */
    public boolean isEditable() {
        return true;
    }

    /**
     * Brings a data document's virtual views back in line with the file's bytes
     * after they changed — a save from this document's own editor, or an external
     * change reported by the file watcher. A no-op for every other kind — the
     * save and reload paths call this unconditionally.
     */
    public void refreshDataViewFromDisk() {
        // Only a data document has views to refresh.
    }

    /**
     * @return whether the document has a backing file
     */
    public boolean hasFile() {
        return file != null;
    }

    /**
     * @return a short display name for tabs and titles: the file name, or "Untitled"
     */
    public String getDisplayName() {
        return this.hasFile() ? file.getName() : "Untitled";
    }

    /** Only model documents with a working directory can be optimisation targets. */
    @Override
    public boolean isOptimisable() {
        return isModel() && getWorkingDirectory() != null;
    }

    // --- File ---

    /** @return the backing file, or {@code null} if this is an untitled document */
    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    /** @return the directory of the backing file, or {@code null} if untitled */
    public File getWorkingDirectory() {
        return file != null ? file.getParentFile() : null;
    }

    // --- Text / dirty state (delegated to the editor) ---

    public String getText() {
        return editor.getText();
    }

    public void setText(String text) {
        editor.setText(text);
    }

    public boolean isDirty() {
        return editor.isDirty();
    }

    public void setDirty(boolean dirty) {
        editor.setDirty(dirty);
    }

    /** @return the editor caret offset, or 0 if unavailable */
    public int getCaretPosition() {
        try {
            return editor.getTextArea().getCaretPosition();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Sets the editor caret offset, clamped to the document bounds. */
    public void setCaretPosition(int offset) {
        try {
            var textArea = editor.getTextArea();
            int length = textArea.getDocument().getLength();
            textArea.setCaretPosition(Math.max(0, Math.min(offset, length)));
        } catch (Exception e) {
            // Ignore: best-effort caret restore.
        }
    }

    // --- Lifecycle ---

    /**
     * Releases everything this document holds beyond its own object graph — most
     * importantly the editor's global listeners and background executors (linter,
     * auto-complete, input-data registry) via {@link EnhancedTextEditor#dispose()}.
     * Called by {@code DocumentManager.closeDocument} for every close path; without
     * it every closed tab leaked its entire editor graph. Idempotent. Subtypes
     * extend this with their bundle's teardown.
     */
    public void dispose() {
        editor.dispose();
    }
}
