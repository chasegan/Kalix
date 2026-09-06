package com.kalix.ide.document;

import com.kalix.ide.dataview.DataViewOpener;
import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.dataview.DataViewSession;
import com.kalix.ide.dataview.VirtualTextArea;
import com.kalix.ide.preferences.PreferenceKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.File;
import java.io.IOException;

/**
 * A delimited data file ({@code .csv}, including {@code .res.csv}): the virtual
 * data-view bundle — a {@link DataViewSession} feeding a table context view,
 * with the text side editable below the large-file gate and a virtual read-only
 * text view above it (see {@code docs/data-file-viewer.md}).
 */
public class DataDocument extends KalixDocument {

    private static final Logger logger = LoggerFactory.getLogger(DataDocument.class);

    /**
     * {@code null} when the data view failed to open. Volatile, non-final:
     * rebuilt after saves (see {@link #refreshDataViewAfterSave()}).
     */
    private volatile DataViewSession dataViewSession;
    private final DataViewPanel dataViewPanel;
    private final VirtualTextArea largeTextArea;
    private final JScrollPane largeTextScroller;
    /** True above the editable-text gate: virtual read-only views. */
    private final boolean largeReadOnly;

    /** Data documents require their backing file at construction (the views read it directly). */
    public DataDocument(File file) {
        this(file, exceedsEditableGate(file));
    }

    /** Test seam: the gate decision is injectable so tests need no 50MB files. */
    DataDocument(File file, boolean largeReadOnly) {
        super(DocumentKind.DATA);
        if (file == null) {
            throw new IllegalArgumentException("DATA documents need a backing file");
        }
        setFile(file); // the load path may setFile again with the same file; harmless

        DataViewSession session = null;
        try {
            session = DataViewOpener.openFor(file);
        } catch (IOException e) {
            // The tab still opens; only the data views are lost.
            logger.warn("Data view unavailable for {}: {}", file, e.getMessage());
        }
        this.dataViewSession = session;
        this.dataViewPanel = session != null ? new DataViewPanel(session) : null;
        boolean virtualText = largeReadOnly && session != null;
        this.largeTextArea = virtualText ? new VirtualTextArea(session) : null;
        this.largeTextScroller = virtualText ? new JScrollPane(largeTextArea) : null;
        // Above the gate the document is read-only EVEN IF the session failed:
        // in that case the editor buffer is empty (the load path never reads the
        // file), and an editable empty buffer over a real file is one Save All
        // away from truncating it to nothing.
        this.largeReadOnly = largeReadOnly;
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

    /** A data document's contextual view is its virtual table (null if the open failed). */
    @Override
    public Component getContextView() {
        return dataViewPanel;
    }

    @Override
    public JComponent getPrimaryView() {
        // A read-only document whose session failed falls back to its (empty,
        // unsaveable) editor rather than a null component.
        return largeReadOnly && largeTextScroller != null ? largeTextScroller : getEditor();
    }

    @Override
    public Component getPrimaryFocusComponent() {
        return largeReadOnly && largeTextArea != null ? largeTextArea : getEditor().getTextArea();
    }

    @Override
    public boolean isEditable() {
        return !largeReadOnly;
    }

    /** This document's data session, or {@code null} if the open failed. Package-private, for tests. */
    DataViewSession getDataViewSession() {
        return dataViewSession;
    }

    /**
     * Rebuilds the data views from the file after this document's text was saved.
     * A below-gate data document is editable, so a save moves every byte offset
     * the session's index recorded — parsing from stale checkpoints would render
     * misaligned garbage presented as data. The fresh session is opened off the
     * EDT and swapped in on it; the old session closes after the swap. No-op for
     * read-only views (nothing can be saved) and failed sessions.
     */
    @Override
    public void refreshDataViewAfterSave() {
        if (dataViewPanel == null || !isEditable() || getFile() == null) {
            return;
        }
        File target = getFile(); // Save As may have re-pointed the document; read the new bytes
        Thread reloader = new Thread(() -> {
            try {
                DataViewSession fresh = DataViewOpener.openFor(target);
                SwingUtilities.invokeLater(() -> {
                    DataViewSession old = dataViewSession;
                    dataViewSession = fresh;
                    dataViewPanel.replaceSession(fresh);
                    if (old != null) {
                        old.close();
                    }
                });
            } catch (IOException e) {
                logger.warn("Data view refresh failed for {}: {}", target, e.getMessage());
            }
        }, "kalix-dataview-reload");
        reloader.setDaemon(true);
        reloader.start();
    }

    @Override
    public void dispose() {
        super.dispose();
        DataViewSession session = dataViewSession;
        if (session != null) {
            session.close(); // aborts any in-flight indexing within one read
        }
    }
}
