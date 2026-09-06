package com.kalix.ide.document;

import com.kalix.ide.MapPanel;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.awt.Component;

/**
 * A Kalix model document ({@code .ini}, or a new untitled document): the full
 * bundle — the {@link HydrologicalModel} parsed from the editor text and the
 * {@link MapPanel} visualising it, with bidirectional text↔map sync wired once
 * at construction and per-document auto-zoom when the model first gains nodes.
 */
public class ModelDocument extends KalixDocument {

    private static final Logger logger = LoggerFactory.getLogger(ModelDocument.class);

    private final HydrologicalModel model;
    private final MapPanel mapPanel;

    /** Node count at the last model change, used to auto-zoom on the 0 -&gt; &gt;0 transition. */
    private int previousNodeCount = 0;

    // --- Parse coalescing (EDT-confined) ---

    /** Whether a model parse is already queued on the EDT; further edits coalesce into it. */
    private boolean parseQueued = false;

    /** Whether the queued parse should zoom-to-fit (ORed across coalesced requests). */
    private boolean queuedAutoZoom = false;

    public ModelDocument() {
        super(DocumentKind.MODEL);
        this.model = new HydrologicalModel();
        // The map panel is bound to its model and editor at construction; all
        // map-side collaborators (text sync, clipboard, context menu, search)
        // are wired inside, symmetrically and exactly once.
        this.mapPanel = new MapPanel(model, getEditor());
        // Wire map panel to editor for the "Show on Map" context menu action.
        getEditor().setMapPanel(mapPanel);
        // Per-document auto-zoom: fit the view when the model first gains nodes.
        model.addChangeListener(this::onModelChanged);
    }

    @Override
    public HydrologicalModel getModel() {
        return model;
    }

    @Override
    public MapPanel getMapPanel() {
        return mapPanel;
    }

    /** A model's contextual view is its map. */
    @Override
    public Component getContextView() {
        return mapPanel;
    }

    /**
     * Parses the current editor text into the model using incremental parsing.
     *
     * <p>Coalesced: document events arrive per keystroke (and in bursts for
     * multi-event operations like replace), but only one parse is ever queued on
     * the EDT — further requests while one is pending are no-ops, with the
     * zoom-to-fit flag ORed into the pending parse. EDT-confined, like every
     * caller (document listeners and file-load paths).</p>
     */
    @Override
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
                String text = getEditor().getText();
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
}
