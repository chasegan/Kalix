package com.kalix.ide.document;

import com.kalix.ide.MapPanel;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.io.File;
import java.util.function.Supplier;

/**
 * Represents a single open document (one model file) and the bundle of state and
 * views that belong to it: the backing {@link File}, the {@link EnhancedTextEditor}
 * editing its text, the {@link HydrologicalModel} parsed from that text, and the
 * {@link MapPanel} visualising the model.
 *
 * <p>A {@code KalixDocument} owns the per-document wiring that used to live in
 * {@code KalixIDE}: parsing text into the model on edits, bidirectional text&lt;-&gt;map
 * synchronisation, and per-document auto-zoom when the model first gains nodes.
 * Because each document owns its own editor instance, undo/redo history is naturally
 * per-document via RSyntaxTextArea's native undo stack — no shared or custom
 * {@code UndoManager} is involved.
 *
 * <p>This is the unit that becomes "many" when multi-document support lands
 * (see {@code docs/multi-document-architecture.md}). In Phase 1 there is exactly one.
 *
 * <p>Application-level concerns (status bar, title bar, file watching, theme
 * registration, linter/autocomplete service wiring) are intentionally <em>not</em>
 * owned here — they observe or attach to the active document from {@code KalixIDE}.
 */
public class KalixDocument {

    private static final Logger logger = LoggerFactory.getLogger(KalixDocument.class);

    private final EnhancedTextEditor editor;
    private final MapPanel mapPanel;
    private final HydrologicalModel model;

    /** Backing file, or {@code null} for an untitled document. */
    private File file;

    /** Node count at the last model change, used to auto-zoom on the 0 -&gt; &gt;0 transition. */
    private int previousNodeCount = 0;

    // --- Parse coalescing and memoization (all EDT-confined) ---

    /** Whether a model parse is already queued on the EDT; further edits coalesce into it. */
    private boolean parseQueued = false;

    /** Whether the queued parse should zoom-to-fit (ORed across coalesced requests). */
    private boolean queuedAutoZoom = false;

    /** Bumped on every document edit; keys the memoized linter parse. */
    private long modificationCount = 0;

    /** {@link #modificationCount} at which {@link #cachedParsedModel} was computed, or -1. */
    private long parsedModificationCount = -1;

    /** Memoized linter parse of the editor text; {@code null} also caches a parse failure. */
    private INIModelParser.ParsedModel cachedParsedModel;

    /**
     * Creates a document, constructing its own editor, map and model, and performs
     * all per-document wiring. Application-level features that depend on shared
     * services (linter, autocomplete, context commands, theme registration) are
     * attached to {@link #getEditor()} / {@link #getMapPanel()} by the host after
     * construction.
     */
    public KalixDocument() {
        this.editor = new EnhancedTextEditor();
        this.model = new HydrologicalModel();
        // The map panel is bound to its model and editor at construction; all
        // map-side collaborators (text sync, clipboard, context menu, search)
        // are wired inside, symmetrically and exactly once.
        this.mapPanel = new MapPanel(model, editor);

        wire();
    }

    /**
     * Establishes the per-document connections between editor, model and map.
     */
    private void wire() {
        // Wire map panel to editor for "Show on Map" context menu action.
        editor.setMapPanel(mapPanel);

        // Re-parse the model whenever the text changes (coalesced; see
        // parseModelFromText). The modification count keys the memoized
        // linter parse handed out by getModelSupplier().
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

        // Per-document auto-zoom: fit the view when the model first gains nodes.
        model.addChangeListener(this::onModelChanged);
    }

    /** Reacts to a single document edit: invalidates the memoized parse, queues a re-parse. */
    private void onDocumentEdit() {
        modificationCount++;
        parseModelFromText(false);
    }

    /**
     * Parses the current editor text into the model using incremental parsing.
     *
     * <p>Coalesced: document events arrive per keystroke (and in bursts for
     * multi-event operations like replace), but only one parse is ever queued on
     * the EDT — further requests while one is pending are no-ops, with the
     * zoom-to-fit flag ORed into the pending parse. EDT-confined, like every
     * caller (document listeners and file-load paths).</p>
     *
     * @param autoZoomToFit if true, zoom the map to fit after parsing (used on file loads)
     */
    public void parseModelFromText(boolean autoZoomToFit) {
        queuedAutoZoom |= autoZoomToFit;
        if (parseQueued) {
            return;
        }
        parseQueued = true;
        SwingUtilities.invokeLater(() -> {
            boolean zoomToFit = queuedAutoZoom;
            parseQueued = false;
            queuedAutoZoom = false;
            try {
                String text = editor.getText();
                if (text != null) {
                    model.parseFromIniTextIncremental(text);
                    if (zoomToFit) {
                        mapPanel.zoomToFit();
                    }
                }
            } catch (Exception e) {
                // Log parsing errors but don't disrupt the UI.
                logger.warn("Error parsing model from text: {}", e.getMessage());
            }
        });
    }

    /**
     * Auto-zooms the map to fit when the model transitions from 0 to &gt;0 nodes,
     * i.e. when content first appears (typing into an empty model, or a load).
     */
    private void onModelChanged(ModelChangeEvent event) {
        SwingUtilities.invokeLater(() -> {
            int currentNodeCount = model.getStatistics().getNodeCount();
            if (previousNodeCount == 0 && currentNodeCount > 0) {
                mapPanel.zoomToFit();
            }
            previousNodeCount = currentNodeCount;
        });
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

    // --- Views ---

    public EnhancedTextEditor getEditor() {
        return editor;
    }

    public MapPanel getMapPanel() {
        return mapPanel;
    }

    /**
     * Returns the component shown in the right-hand contextual view when this document
     * is active, or {@code null} if this document has no contextual view (in which case
     * the contextual view region collapses). For a model document this is the map.
     *
     * <p>When non-model document types are introduced (Phase 4), a base type would
     * return {@code null} here and a data type would return a plot.
     */
    public java.awt.Component getContextView() {
        return mapPanel;
    }

    /**
     * @return a short display name for tabs and titles: the file name, or "Untitled"
     */
    public String getDisplayName() {
        return file != null ? file.getName() : "Untitled";
    }

    public HydrologicalModel getModel() {
        return model;
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
     * it every closed tab leaked its entire editor graph. Idempotent.
     */
    public void dispose() {
        editor.dispose();
    }
}
