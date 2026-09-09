package com.kalix.ide.document;

import com.kalix.ide.dataview.CsvDialect;
import com.kalix.ide.dataview.DataViewOpener;
import com.kalix.ide.dataview.DataViewPanel;
import com.kalix.ide.dataview.DataVizView;
import com.kalix.ide.dataview.DataViewSession;
import com.kalix.ide.dataview.VirtualLineNumberGutter;
import com.kalix.ide.dataview.VirtualTextArea;
import com.kalix.ide.editor.KalixCsvTokenMaker;
import com.kalix.ide.io.CsvGzFormat;
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
    /** The plot-above-table bundle; {@code null} when the data view failed to open. */
    private final DataVizView dataVizView;
    private final VirtualTextArea largeTextArea;
    private final JScrollPane largeTextScroller;
    /** The scroller under its read-only banner; what the tab actually mounts. */
    private final JComponent largePrimaryView;
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
        // A .csv.gz is read-only regardless of size: its bytes on disk are
        // gzip, so there is no text buffer to honestly edit and save back.
        this(file, exceedsEditableGate(file) || CsvGzFormat.isCsvGz(file != null ? file.getName() : null));
    }

    /** Test seam: the gate decision is injectable so tests need no 50MB files. */
    DataDocument(File file, boolean largeReadOnly) {
        super(DocumentKind.DATA);
        if (file == null) {
            throw new IllegalArgumentException("DATA documents need a backing file");
        }
        setFile(file); // the load path may setFile again with the same file; harmless

        DataViewSession session = null;
        String openFailureNote = null;
        try {
            session = DataViewOpener.openFor(file);
        } catch (CsvGzFormat.TooLargeException e) {
            // The one refusal with a story the banner must tell: the payload
            // crossed the in-memory limit, and the user can raise it.
            openFailureNote = e.getMessage() + " (modify limit in preferences)";
            logger.warn("Data view unavailable for {}: {}", file, e.getMessage());
        } catch (IOException e) {
            // The tab still opens; only the data views are lost.
            logger.warn("Data view unavailable for {}: {}", file, e.getMessage());
        }
        this.dataViewSession = session;
        this.dataViewPanel = session != null ? new DataViewPanel(session) : null;
        if (dataViewPanel != null) {
            dataViewPanel.setShowInFileHandler(this::showDataLineInText);
        }
        this.dataVizView = dataViewPanel != null ? new DataVizView(dataViewPanel, session) : null;

        // Per-filetype syntax: the editable text side tokenises as CSV using
        // the sniffed dialect — the same authority the table, extractor and
        // plot use. A failed open falls back to the conventional comma dialect.
        CsvDialect dialect = session != null ? session.dialect() : null;
        getEditor().useTokenMaker(new KalixCsvTokenMaker(
            dialect != null ? dialect.delimiter() : ',',
            dialect != null ? dialect.quote() : '"',
            dialect == null || dialect.hasHeaderRow()));
        boolean virtualText = largeReadOnly && session != null;
        this.largeTextArea = virtualText ? new VirtualTextArea(session) : null;
        this.largeTextScroller = virtualText ? new JScrollPane(largeTextArea) : null;
        if (virtualText) {
            // Editor parity: a line-number gutter, and Find routed to the data
            // views (the editor's Find would search a hidden empty buffer).
            VirtualLineNumberGutter gutter = new VirtualLineNumberGutter(largeTextArea);
            largeTextScroller.setRowHeaderView(gutter);
            largeTextArea.attachGutter(gutter);
            largeTextArea.setFindHandlers(dataViewPanel::openFind, dataViewPanel::repeatFind);
        }
        if (virtualText) {
            this.largePrimaryView = withReadOnlyBanner(largeTextScroller, readOnlyBannerText(file));
        } else if (largeReadOnly && openFailureNote != null) {
            // No views to show, but the tab must still say why (a refused
            // .csv.gz): the banner carries the reason over the empty,
            // unsaveable editor.
            this.largePrimaryView = withReadOnlyBanner(getEditor(), openFailureNote);
        } else {
            this.largePrimaryView = null;
        }
        // Above the gate the document is read-only EVEN IF the session failed:
        // in that case the editor buffer is empty (the load path never reads the
        // file), and an editable empty buffer over a real file is one Save All
        // away from truncating it to nothing.
        this.largeReadOnly = largeReadOnly;
    }

    /** Why this tab is read-only, in the banner's words — gz has its own story. */
    private static String readOnlyBannerText(File file) {
        if (CsvGzFormat.isCsvGz(file.getName())) {
            return "Read only (.csv.gz is viewed decompressed)";
        }
        return String.format(
            "Read only >%dMB (modify threshold in preferences)",
            PreferenceKeys.EDITOR_LARGE_FILE_GATE_MB.get());
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
     * A data document's contextual view: the collapsed-by-default plot region
     * above the virtual table (null if the open failed).
     */
    @Override
    public Component getContextView() {
        return dataVizView;
    }

    @Override
    public JComponent getPrimaryView() {
        // A read-only document whose session failed falls back to its (empty,
        // unsaveable) editor rather than a null component.
        return largeReadOnly && largePrimaryView != null ? largePrimaryView : getEditor();
    }

    /**
     * Wraps the virtual text view under a slim banner announcing WHY the tab is
     * read-only. Before this, nothing anywhere said so: typing was swallowed
     * silently, and the word "read-only" first appeared after a failed save.
     */
    private static JComponent withReadOnlyBanner(JComponent content, String text) {
        javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
        javax.swing.JLabel banner = new javax.swing.JLabel(text);
        banner.setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8));
        banner.setEnabled(false); // muted, theme-following
        panel.add(banner, java.awt.BorderLayout.NORTH);
        panel.add(content, java.awt.BorderLayout.CENTER);
        return panel;
    }

    /** Whether Edit→Find should target the data views rather than the (hidden, empty) editor. */
    public boolean routesFindToDataView() {
        return largeReadOnly && dataViewPanel != null;
    }

    /** Opens the data views' unified Find (menu routing for read-only tabs). */
    public void showDataFind() {
        dataViewPanel.openFind();
    }

    /** Repeats the data views' last find (menu routing for read-only tabs). */
    public void repeatDataFind(boolean forward) {
        dataViewPanel.repeatFind(forward);
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
                if (dataVizView != null) {
                    dataVizView.onSessionReplaced(fresh); // plotted columns re-extract
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
        if (dataVizView != null) {
            dataVizView.dispose(); // in-flight extraction passes abandon their work
        }
        super.dispose();
        DataViewSession session = dataViewSession;
        if (session != null) {
            session.close(); // aborts any in-flight indexing within one read
        }
    }
}
