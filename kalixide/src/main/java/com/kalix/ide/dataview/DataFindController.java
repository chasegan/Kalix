package com.kalix.ide.dataview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionListener;
import java.awt.Component;
import java.awt.Toolkit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The unified Find behind any tabular data view — the format-neutral half of
 * the seam whose other half is {@link FindableData}. Owns the one dialog, the
 * F3 semantics, the header-columns (Columns scope) merge, wrap, the inline
 * "n of m" status and the landing — everything that must feel identical across
 * formats. A new format supplies a {@link FindableData} and a {@link JTable};
 * this class supplies the rest.
 *
 * <p>Origin and staleness: the search origin comes from the table selection
 * (with a transient header landing tracked separately, retired whenever the
 * user moves the selection themselves — mirroring the editor's caret
 * re-anchor), and a scan landed against a swapped-out data source is dropped
 * (the {@code data} supplier's current value is the identity).
 */
public final class DataFindController {

    private static final Logger logger = LoggerFactory.getLogger(DataFindController.class);

    private final JTable table;
    private final Supplier<FindableData> data;
    /**
     * Where the dialog is centred. Never the table: a virtual table's bounds
     * are its full virtual extent (millions of pixels tall), so centring on it
     * pins the dialog to a screen edge.
     */
    private final Component placementOwner;

    private DataFindDialog findDialog;
    /** Column of the last find landing on a header, or null; header positions precede all cells. */
    private Integer lastHeaderLanding;
    /** True while find moves the selection itself, so the re-anchor listener ignores it. */
    private boolean programmaticFindSelection;
    /** One scan at a time: holding F3 must not stack full-file scans. */
    private final AtomicBoolean searchInFlight = new AtomicBoolean(false);

    public DataFindController(JTable table, Component placementOwner, Supplier<FindableData> data) {
        this.table = table;
        this.placementOwner = placementOwner;
        this.data = data;
        // A manual selection change repositions the find origin (mirroring the
        // editor's caret re-anchor) and retires any header landing.
        ListSelectionListener reanchorFind = e -> {
            if (!programmaticFindSelection) {
                lastHeaderLanding = null;
            }
        };
        table.getSelectionModel().addListSelectionListener(reanchorFind);
        table.getColumnModel().getSelectionModel().addListSelectionListener(reanchorFind);
    }

    /** Shows the Find dialog (lazily created — it needs a display), pre-filled from the selected cell. */
    public void openFind() {
        if (findDialog == null) {
            findDialog = new DataFindDialog(placementOwner, this);
        }
        String prefill = null;
        int viewRow = table.getSelectedRow();
        int viewColumn = table.getSelectedColumn();
        if (viewRow >= 0 && viewColumn >= 0) {
            Object value = table.getValueAt(viewRow, viewColumn);
            prefill = value != null ? value.toString() : null;
        }
        findDialog.showOver(prefill);
    }

    /** F3 / Shift-F3: repeat the last search, or open the dialog when there is none. */
    public void repeatFind(boolean forward) {
        if (findDialog == null || findDialog.queryText().isBlank()) {
            openFind();
        } else {
            runFind(forward);
        }
    }

    /**
     * One find step: snapshot the spec and origin on the EDT, run the format's
     * scan on a worker (streamed or in-memory — the controller doesn't care),
     * choose the landing (header columns first, then cells — see
     * {@link DataFindNavigator}) and land it back on the EDT with inline
     * status, editor-style.
     */
    void runFind(boolean forward) {
        DataFind.Spec spec = findDialog.spec();
        if (spec.query().isEmpty()) {
            findDialog.setStatus(" ", false);
            return;
        }
        if (!searchInFlight.compareAndSet(false, true)) {
            return; // a scan is already running ("a 1GB file takes a few seconds")
        }
        List<Integer> headerCols = findDialog.columnsScope() ? matchingColumns(spec) : List.of();
        boolean wrap = findDialog.wrapEnabled();
        FindableData target = data.get();
        long headerOffset = target.headerRowOffset();
        long fromRow;
        int fromColumn;
        if (lastHeaderLanding != null) {
            fromRow = -1;
            fromColumn = lastHeaderLanding;
        } else if (table.getSelectedRow() >= 0) {
            fromRow = table.getSelectedRow() + headerOffset;
            fromColumn = Math.max(0, selectedModelColumn());
        } else {
            fromRow = forward ? -2 : Long.MAX_VALUE;
            fromColumn = 0;
        }
        long fromRowFinal = fromRow;
        int fromColumnFinal = fromColumn;
        Thread searcher = new Thread(() -> {
            try {
                DataFind.Scan scan = target.scanForMatches(spec, fromRowFinal, fromColumnFinal);
                // The in-flight flag is released only after the landing applies:
                // released earlier, an F3 already queued behind the landing could
                // snapshot its origin from the pre-landing selection and land the
                // same match twice.
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (data.get() == target) {
                            applyLanding(
                                DataFindNavigator.choose(
                                    scan, headerCols, fromRowFinal, fromColumnFinal, forward, wrap),
                                scan.total() + headerCols.size(), headerOffset);
                        }
                    } finally {
                        searchInFlight.set(false);
                    }
                });
            } catch (IOException e) {
                logger.warn("Data find failed: {}", e.getMessage());
                searchInFlight.set(false);
            }
        }, "kalix-data-find");
        searcher.setDaemon(true);
        searcher.start();
    }

    /** Header model columns whose name matches the spec, ascending — the Columns scope. */
    private List<Integer> matchingColumns(DataFind.Spec spec) {
        List<Integer> matches = new ArrayList<>();
        String needle = spec.matchCase() ? spec.query() : spec.query().toLowerCase(Locale.ROOT);
        for (int column = 0; column < table.getModel().getColumnCount(); column++) {
            String name = table.getModel().getColumnName(column);
            String haystack = spec.matchCase() ? name : name.toLowerCase(Locale.ROOT);
            if (spec.wholeCell() ? haystack.equals(needle) : haystack.contains(needle)) {
                matches.add(column);
            }
        }
        return matches;
    }

    private int selectedModelColumn() {
        int viewColumn = table.getSelectedColumn();
        return viewColumn >= 0 ? table.convertColumnIndexToModel(viewColumn) : -1;
    }

    /** Lands one find step: selection, scroll, header bookkeeping, inline status. EDT only. */
    private void applyLanding(DataFindNavigator.Landing landing, int totalMatches, long headerOffset) {
        if (landing == null) {
            // Editor parity: zero matches anywhere is "No results"; a directional
            // dead end with Wrap off is "No more results".
            findDialog.setStatus(totalMatches == 0 ? "No results" : "No more results", true);
            if (!findDialog.isShowing()) {
                Toolkit.getDefaultToolkit().beep(); // F3 with the dialog closed still gets feedback
            }
            return;
        }
        programmaticFindSelection = true;
        try {
            if (landing.row() == -1) {
                lastHeaderLanding = landing.column();
                int viewColumn = table.convertColumnIndexToView(landing.column());
                if (table.getRowCount() > 0 && viewColumn >= 0) {
                    table.changeSelection(0, viewColumn, false, false);
                    table.scrollRectToVisible(table.getCellRect(0, viewColumn, true));
                }
            } else {
                lastHeaderLanding = null;
                int viewRow = (int) Math.min(Integer.MAX_VALUE, landing.row() - headerOffset);
                int viewColumn = table.convertColumnIndexToView(Math.max(0, landing.column()));
                if (viewRow >= 0 && viewRow < table.getRowCount() && viewColumn >= 0) {
                    table.changeSelection(viewRow, viewColumn, false, false);
                    table.scrollRectToVisible(table.getCellRect(viewRow, viewColumn, true));
                }
            }
        } finally {
            programmaticFindSelection = false;
        }
        if (landing.nearestDate()) {
            findDialog.setStatus("No exact match — nearest later date", false);
        } else {
            findDialog.setStatus(landing.ordinal() + " of " + landing.total()
                + (landing.wrapped() ? " (wrapped)" : ""), false);
        }
    }
}
