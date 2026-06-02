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

    /**
     * Creates a document, constructing its own editor, map and model, and performs
     * all per-document wiring. Application-level features that depend on shared
     * services (linter, autocomplete, context commands, theme registration) are
     * attached to {@link #getEditor()} / {@link #getMapPanel()} by the host after
     * construction.
     */
    public KalixDocument() {
        this.editor = new EnhancedTextEditor();
        this.mapPanel = new MapPanel();
        this.model = new HydrologicalModel();

        wire();
    }

    /**
     * Establishes the per-document connections between editor, model and map.
     */
    private void wire() {
        // Connect map panel to this document's data model.
        mapPanel.setModel(model);

        // Set up bidirectional text synchronisation (map drags -> text coordinates).
        mapPanel.setupTextSynchronization(editor);

        // Wire map panel to editor for "Show on Map" context menu action.
        editor.setMapPanel(mapPanel);

        // Re-parse the model whenever the text changes.
        editor.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                parseModelFromText(false);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                parseModelFromText(false);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                parseModelFromText(false);
            }
        });

        // Per-document auto-zoom: fit the view when the model first gains nodes.
        model.addChangeListener(this::onModelChanged);
    }

    /**
     * Parses the current editor text into the model using incremental parsing.
     *
     * @param autoZoomToFit if true, zoom the map to fit after parsing (used on file loads)
     */
    public void parseModelFromText(boolean autoZoomToFit) {
        SwingUtilities.invokeLater(() -> {
            try {
                String text = editor.getText();
                if (text != null) {
                    model.parseFromIniTextIncremental(text);
                    if (autoZoomToFit) {
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
     */
    public Supplier<INIModelParser.ParsedModel> getModelSupplier() {
        return () -> {
            try {
                return INIModelParser.parse(editor.getText());
            } catch (Exception e) {
                return null;
            }
        };
    }

    // --- Views ---

    public EnhancedTextEditor getEditor() {
        return editor;
    }

    public MapPanel getMapPanel() {
        return mapPanel;
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
}
