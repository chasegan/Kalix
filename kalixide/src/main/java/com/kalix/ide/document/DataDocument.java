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
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * rebuilt whenever the file's bytes change (see {@link #refreshDataViewFromDisk()}).
     */
    private volatile DataViewSession dataViewSession;
    private final DataViewPanel dataViewPanel;
    private final VirtualTextArea largeTextArea;
    private final JScrollPane largeTextScroller;
    /** True above the editable-text gate: virtual read-only views. */
    private final boolean largeReadOnly;

    /** Set by every refresh request; drained by the single refresh worker. */
    private final AtomicBoolean refreshRequested = new AtomicBoolean(false);
    /** Guards the single refresh worker; a burst of requests coalesces into its drain loop. */
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);
    /** Once the tab is closed, no session may be installed (or left open) by a late rebuild. */
    private volatile boolean disposed = false;

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
        if (dataViewPanel != null) {
            dataViewPanel.setShowInFileHandler(this::showDataLineInText);
        }
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
     * Brings the data views back in line with the file's bytes after they
     * changed — a save from this document's own editor, or an external change.
     * The session's indexes record byte offsets the change may have moved;
     * parsing from stale checkpoints would render misaligned garbage presented
     * as data.
     *
     * <p>All file probing happens on a single refresh worker, never the EDT
     * (external events arrive on the EDT and a stat on a stalled share must not
     * freeze the UI). The worker drains a request flag, so a burst of change
     * events coalesces and the trailing request is never lost. Two paths per
     * request: a pure append (a running simulation writing results) is handled
     * in place by {@link DataViewSession#tryResumeAppend()} — the views simply
     * keep growing, the "live tail"; anything else rebuilds the session and
     * swaps it into the table (and, above the gate, the virtual text view),
     * closing the old one. A rebuild finishing after the tab was closed closes
     * the fresh session instead of installing it.
     */
    @Override
    public void refreshDataViewFromDisk() {
        if (dataViewPanel == null || getFile() == null || disposed) {
            return;
        }
        refreshRequested.set(true);
        maybeStartRefreshWorker();
    }

    private void maybeStartRefreshWorker() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return; // the running worker drains refreshRequested before exiting
        }
        Thread reloader = new Thread(() -> {
            try {
                while (!disposed && refreshRequested.getAndSet(false)) {
                    refreshOnce();
                }
            } finally {
                refreshInFlight.set(false);
                // A request that landed between the drain and the flag clear must
                // not be stranded with no worker to serve it.
                if (refreshRequested.get() && !disposed) {
                    maybeStartRefreshWorker();
                }
            }
        }, "kalix-dataview-reload");
        reloader.setDaemon(true);
        reloader.start();
    }

    /** One refresh: resume in place if the file purely grew, else rebuild and swap. Worker thread. */
    private void refreshOnce() {
        DataViewSession current = dataViewSession;
        if (current != null && current.tryResumeAppend()) {
            return; // pure growth (or already tailing): views extend in place
        }
        File target = getFile(); // Save As may have re-pointed the document; read the new bytes
        try {
            DataViewSession fresh = DataViewOpener.openFor(target);
            SwingUtilities.invokeAndWait(() -> {
                if (disposed) {
                    fresh.close(); // the tab died while we were rebuilding
                    return;
                }
                DataViewSession old = dataViewSession;
                dataViewSession = fresh;
                dataViewPanel.replaceSession(fresh);
                if (largeTextArea != null) {
                    largeTextArea.replaceSession(fresh);
                }
                if (old != null) {
                    old.close();
                }
            });
        } catch (IOException e) {
            logger.warn("Data view refresh failed for {}: {}", target, e.getMessage());
        } catch (InterruptedException | InvocationTargetException e) {
            logger.warn("Data view refresh interrupted for {}: {}", target, e.getMessage());
        }
    }

    /**
     * "Show in file": reveals a data-region physical line in whichever text side
     * this tab has — the virtual text view above the gate, or the real editor
     * below it (offset by any extended format header the editor also shows).
     */
    private void showDataLineInText(long dataLine) {
        if (largeTextArea != null) {
            largeTextArea.showLine(dataLine);
            largeTextArea.requestFocusInWindow();
            return;
        }
        DataViewSession session = dataViewSession;
        long editorLine = dataLine + (session != null ? session.headerLinesBeforeData() : 0);
        var textArea = getEditor().getTextArea();
        try {
            int line = (int) Math.max(0, Math.min(editorLine, textArea.getLineCount() - 1L));
            textArea.setCaretPosition(textArea.getLineStartOffset(line));
            textArea.requestFocusInWindow();
        } catch (BadLocationException e) {
            // Out of range (the file changed underneath); nothing to navigate to.
        }
    }

    @Override
    public void dispose() {
        disposed = true; // a rebuild finishing after this closes its fresh session
        super.dispose();
        DataViewSession session = dataViewSession;
        if (session != null) {
            session.close(); // aborts any in-flight indexing within one read
        }
    }
}
