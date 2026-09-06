package com.kalix.ide.document;

import com.kalix.ide.MapPanel;
import com.kalix.ide.dataview.DataViewOpener;
import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.dataview.DataViewSession;
import com.kalix.ide.dataview.VirtualTextArea;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;
import com.kalix.ide.preferences.PreferenceKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * Represents a single open document — one per tab — and the bundle of state and
 * views that belong to it. Every document owns its backing {@link File} (nullable
 * = untitled) and the {@link EnhancedTextEditor} editing its text; a
 * {@link DocumentKind#MODEL} document additionally owns the
 * {@link HydrologicalModel} parsed from that text and the {@link MapPanel}
 * visualising it (both {@code null} for other kinds).
 *
 * <p>A {@code KalixDocument} owns the per-document wiring that used to live in
 * {@code KalixIDE}: parsing text into the model on edits, bidirectional text&lt;-&gt;map
 * synchronisation, and per-document auto-zoom when the model first gains nodes.
 * Because each document owns its own editor instance, undo/redo history is naturally
 * per-document via RSyntaxTextArea's native undo stack — no shared or custom
 * {@code UndoManager} is involved.
 *
 * <p>The workspace layer builds each tab's content from these views (the editor,
 * plus {@link #getContextView()} when present — see
 * {@code docs/multi-document-architecture.md}, Addendum).
 *
 * <p>Application-level concerns (status bar, title bar, file watching, theme
 * registration, linter/autocomplete service wiring) are intentionally <em>not</em>
 * owned here — they observe or attach to the active document from {@code KalixIDE}.
 */
public class KalixDocument implements OpenModel {

    private static final Logger logger = LoggerFactory.getLogger(KalixDocument.class);

    private final DocumentKind kind;
    private final EnhancedTextEditor editor;
    /** {@code null} for non-model kinds — see {@link #getMapPanel()}. */
    private final MapPanel mapPanel;
    /** {@code null} for non-model kinds — see {@link #getModel()}. */
    private final HydrologicalModel model;

    // --- DATA-kind bundle (all null for other kinds) ---
    /** {@code null} for non-data kinds, or when the data view failed to open. */
    private final DataViewSession dataViewSession;
    private final DataViewPanel dataViewPanel;
    private final VirtualTextArea largeTextArea;
    private final JScrollPane largeTextScroller;
    /** True for a DATA document above the editable-text gate: virtual read-only views. */
    private final boolean largeReadOnly;

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
        this(DocumentKind.MODEL);
    }

    /**
     * Creates a document of the given kind. MODEL documents build the full
     * bundle; other kinds build the editor alone, leaving model and map
     * {@code null} and the contextual view empty.
     */
    public KalixDocument(DocumentKind kind) {
        this(kind, null);
    }

    /**
     * Creates a document of the given kind for the given backing file. DATA
     * documents require the file at construction (their virtual views read it
     * directly); for other kinds it may be {@code null} (untitled).
     */
    public KalixDocument(DocumentKind kind, File file) {
        this(kind, file, kind == DocumentKind.DATA && exceedsEditableGate(file));
    }

    /** Test seam: the gate decision is injectable so tests need no 50MB files. */
    KalixDocument(DocumentKind kind, File file, boolean largeReadOnly) {
        this.kind = kind;
        this.editor = new EnhancedTextEditor();
        if (kind == DocumentKind.MODEL) {
            this.model = new HydrologicalModel();
            // The map panel is bound to its model and editor at construction; all
            // map-side collaborators (text sync, clipboard, context menu, search)
            // are wired inside, symmetrically and exactly once.
            this.mapPanel = new MapPanel(model, editor);
        } else {
            this.model = null;
            this.mapPanel = null;
        }

        if (kind == DocumentKind.DATA) {
            if (file == null) {
                throw new IllegalArgumentException("DATA documents need a backing file");
            }
            this.file = file;
            DataViewSession session = null;
            try {
                session = DataViewOpener.openFor(file);
            } catch (IOException e) {
                // The tab still opens (as a plain editor); only the data views are lost.
                logger.warn("Data view unavailable for {}: {}", file, e.getMessage());
            }
            this.dataViewSession = session;
            this.dataViewPanel = session != null ? new DataViewPanel(session) : null;
            boolean effectiveLarge = largeReadOnly && session != null;
            this.largeTextArea = effectiveLarge ? new VirtualTextArea(session) : null;
            this.largeTextScroller = effectiveLarge ? new JScrollPane(largeTextArea) : null;
            this.largeReadOnly = effectiveLarge;
        } else {
            this.dataViewSession = null;
            this.dataViewPanel = null;
            this.largeTextArea = null;
            this.largeTextScroller = null;
            this.largeReadOnly = false;
        }

        wire();
    }

    /**
     * Whether a data file is too large to load into an editable text buffer
     * (the Editor → Load and Save gate preference). Above the gate a data tab
     * shows virtual read-only views instead — the file never enters an editor
     * buffer (see {@code docs/data-file-viewer.md} for the physics).
     */
    public static boolean exceedsEditableGate(File file) {
        if (file == null) {
            return false;
        }
        long gateBytes = PreferenceKeys.EDITOR_LARGE_FILE_GATE_MB.get() * 1024L * 1024L;
        return file.length() > gateBytes;
    }

    /**
     * Establishes the per-document connections between editor, model and map.
     */
    private void wire() {
        // Wire map panel to editor for "Show on Map" context menu action.
        if (mapPanel != null) {
            editor.setMapPanel(mapPanel);
        }

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
        if (model != null) {
            model.addChangeListener(this::onModelChanged);
        }
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
        if (model == null) {
            return; // non-model documents have nothing to parse into
        }
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

    /** This document's kind, fixed at creation. */
    public DocumentKind getKind() {
        return kind;
    }

    /** Whether this document holds a Kalix model (and therefore a map). */
    public boolean isModel() {
        return kind == DocumentKind.MODEL;
    }

    /** The map visualising this document's model, or {@code null} for non-model kinds. */
    public MapPanel getMapPanel() {
        return mapPanel;
    }

    /**
     * Returns the component shown in the contextual view for this document, or
     * {@code null} if this document has no contextual view (in which case the
     * region collapses). For a model document this is the map; for a data
     * document the virtual table; a TEXT document has none.
     */
    public Component getContextView() {
        return mapPanel != null ? mapPanel : dataViewPanel;
    }

    /**
     * The main (left) component of this document's tab: normally the text
     * editor; for a DATA document above the editable gate, the virtual
     * read-only text view (the file never enters an editor buffer).
     */
    public JComponent getPrimaryView() {
        return largeReadOnly ? largeTextScroller : editor;
    }

    /** The component that should receive focus when this document's tab activates. */
    public Component getPrimaryFocusComponent() {
        return largeReadOnly ? largeTextArea : editor.getTextArea();
    }

    /**
     * Whether this document's text can be edited and saved. False only for a
     * DATA document above the editable gate — its tab is a viewer, and save
     * paths must refuse rather than write an empty buffer over the file.
     */
    public boolean isEditable() {
        return !largeReadOnly;
    }

    /** This document's data session, or {@code null} for non-data kinds. For tests. */
    public DataViewSession getDataViewSession() {
        return dataViewSession;
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

    /** This document's parsed model, or {@code null} for non-model kinds. */
    public HydrologicalModel getModel() {
        return model;
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
     * it every closed tab leaked its entire editor graph. Idempotent.
     */
    public void dispose() {
        editor.dispose();
        if (dataViewSession != null) {
            dataViewSession.close(); // aborts any in-flight indexing within one read
        }
    }
}
