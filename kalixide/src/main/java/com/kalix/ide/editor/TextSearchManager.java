package com.kalix.ide.editor;

import com.kalix.ide.editor.search.AsyncMatchScanner;
import com.kalix.ide.editor.search.MatchScan;
import com.kalix.ide.editor.search.Replacement;
import com.kalix.ide.editor.search.ReplacementPlanner;
import com.kalix.ide.editor.search.SearchQuery;

import org.fife.ui.rsyntaxtextarea.DocumentRange;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Point2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Search and replace for the model editor.
 *
 * <p>The Find and Find/Replace dialogs each own their own set of input fields (held in a
 * {@link SearchDialog} bundle) so they cannot alias each other's state, and both are
 * non-modal so the user can click into the editor to reposition the search origin while
 * a dialog is open.
 *
 * <h2>Why finding does not use {@code SearchEngine}</h2>
 * RSTA's {@code SearchEngine.find} does two things that are fatal on a large file, both
 * on the EDT: its "mark all" pass scans the whole document on every call, and
 * {@code getFindInText} copies the entire remainder of the document into a String just
 * to search it. On the hundred-megabyte CSVs this editor is expected to open, either is
 * a multi-second freeze per keystroke.
 *
 * <p>So finding is built on {@link AsyncMatchScanner} instead. One background scan —
 * cancellable, windowed, never copying the document — produces every match, and that
 * single result then serves navigation, highlighting <em>and</em> the "n of m" counter.
 * The EDT only ever selects a range and paints.
 * {@code MatchScanner} reproduces RSTA's matching rules exactly, so the results are the
 * ones the user would have got anyway.</p>
 *
 * <p>Replacing is built on the same foundation. {@code ReplacementPlanner} works out
 * every edit off the EDT, so the user can be told exactly how many there will be and
 * decline before anything changes; {@link ChunkedReplacer} then applies them behind a
 * modal dialog, in chunks, as one undoable step. {@code SearchEngine} is not used at
 * all — its replace paths rebuild the document on the EDT just as its find paths do.</p>
 *
 * <h2>Feedback model</h2>
 * All feedback is inline, on a status label inside the dialog — never a modal popup. A
 * find loop that misses should cost nothing to continue, and a modal "not found" box
 * charges a dismissal for every miss.
 */
public class TextSearchManager {

    /**
     * Debounce for find-as-you-type. Scans are cancellable, so this is not needed for
     * correctness — it just avoids starting work for a keystroke that is about to be
     * superseded anyway.
     */
    private static final int LIVE_SEARCH_DELAY_MS = 150;

    /**
     * Cap on matches collected per scan. Bounds memory when a one-character term is
     * searched across a huge file; beyond this the counter reports "n+" rather than an
     * exact total, which is all anyone could use at that magnitude anyway.
     */
    private static final int MAX_MATCHES = 100_000;

    /**
     * Safety cap on highlights painted in one pass.
     *
     * <p>Rarely reached, because highlighting follows the viewport rather than the start
     * of the document — only a band around what is on screen is ever painted. It exists
     * for the pathological case of a band dense with matches.</p>
     */
    private static final int MAX_HIGHLIGHTS = 5_000;

    /**
     * Smallest band margin either side of the viewport, in characters. Keeps the band
     * sensible when the visible range is tiny (a collapsed split, a very short view).
     */
    private static final int MIN_HIGHLIGHT_MARGIN = 2_000;

    /**
     * How long a scan may run before the dialog admits to counting.
     *
     * <p>Results are delivered through {@code invokeLater}, so even an instant scan
     * completes a cycle later than the request. Announcing "Counting…" immediately would
     * therefore flash it on every keystroke of every ordinary-sized file. Below this
     * threshold the count simply appears; above it, the delay is long enough to be worth
     * explaining.</p>
     */
    private static final int COUNTING_INDICATOR_DELAY_MS = 120;

    private final RSyntaxTextArea textArea;
    private final JComponent parentComponent;
    private final EnhancedTextEditor textEditor;
    private final AsyncMatchScanner scanner = new AsyncMatchScanner();

    // Dialogs (lazily created); each bundles its own fields so Find and Replace
    // cannot read each other's inputs.
    private SearchDialog findDialog;
    private SearchDialog replaceDialog;

    /** The dialog whose terms {@link #findAgain} repeats; null until one has been used. */
    private SearchDialog lastUsed;

    /**
     * Bumped on every document edit, so a scan that completes after the text moved on
     * can be recognised as stale and dropped. EDT-confined, like every edit.
     */
    private int documentVersion;

    // The most recent completed scan, and what it describes. Held so that stepping
    // through matches, which changes neither, costs no rescan.
    private MatchScan cachedScan;
    private SearchQuery cachedQuery;
    private int cachedVersion = -1;

    /**
     * Non-zero while this class is moving the caret itself. The caret listener uses it to
     * tell the user repositioning the search origin — which must re-anchor
     * find-as-you-type, since the dialogs are non-modal precisely so that is possible —
     * from a search moving the caret, which must not.
     */
    private int programmaticCaretDepth;

    /** Fires only if a scan outlives {@link #COUNTING_INDICATOR_DELAY_MS}. */
    private final Timer countingIndicator;

    /** Dialog the pending {@link #countingIndicator} belongs to. */
    private SearchDialog countingDialog;

    /** The scan currently painted, so scrolling can re-band the highlights. */
    private MatchScan highlightedScan;

    // Document range the painted highlights cover. While the viewport stays inside it,
    // scrolling needs no repaint.
    private int highlightBandStart;
    private int highlightBandEnd;

    private boolean viewportListenerInstalled;

    /** Non-zero while a bulk edit (a Replace All) is rewriting the document. */
    private int bulkEditDepth;

    /**
     * One search dialog and the input fields that belong to it.
     */
    private static final class SearchDialog {
        final JDialog dialog;
        final JTextField searchField = new JTextField(20);
        final JTextField replaceField; // null for the find-only dialog
        final JCheckBox matchCaseCheckBox = new JCheckBox("Match case");
        final JCheckBox wholeWordCheckBox = new JCheckBox("Whole word");
        final JCheckBox regexCheckBox = new JCheckBox("Regular expression");
        final JCheckBox wrapAroundCheckBox = new JCheckBox("Wrap around");
        final JLabel statusLabel = new JLabel(" ");

        /** Debounce timer for find-as-you-type; created in the dialog factory. */
        Timer liveSearchTimer;

        /**
         * Offset that find-as-you-type restarts from. Without it each keystroke would
         * search onward from the previous keystroke's match and the view would run away
         * down the document as the term grows.
         */
        int anchor;

        SearchDialog(JDialog dialog, boolean withReplaceField) {
            this.dialog = dialog;
            this.replaceField = withReplaceField ? new JTextField(20) : null;
            wrapAroundCheckBox.setSelected(true); // Default to true
        }

        SearchQuery query() {
            return new SearchQuery(searchField.getText(), matchCaseCheckBox.isSelected(),
                wholeWordCheckBox.isSelected(), regexCheckBox.isSelected());
        }
    }

    /**
     * Creates a new TextSearchManager for the specified text area.
     *
     * @param textArea the RSyntaxTextArea to manage
     * @param parentComponent the parent component for dialogs
     */
    public TextSearchManager(RSyntaxTextArea textArea, JComponent parentComponent) {
        this.textArea = textArea;
        this.parentComponent = parentComponent;
        this.textEditor = (parentComponent instanceof EnhancedTextEditor)
            ? (EnhancedTextEditor) parentComponent : null;

        this.countingIndicator = new Timer(COUNTING_INDICATOR_DELAY_MS, e -> {
            if (countingDialog != null) {
                setStatus(countingDialog, "Counting…", false);
            }
            // A scan slow enough to announce is slow enough that the highlights still on
            // screen — belonging to the previous term — have become a lie. Retract them.
            // Fast scans never reach here, so the common case still has no flicker.
            clearHighlights();
        });
        this.countingIndicator.setRepeats(false);

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onDocumentChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onDocumentChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onDocumentChanged();
            }
        });

        // Clicking into the editor while a dialog is open re-anchors find-as-you-type
        // there, so the next keystroke searches from where the user just put the caret.
        textArea.addCaretListener(e -> {
            if (programmaticCaretDepth > 0) {
                return;
            }
            reanchor(findDialog, e.getDot());
            reanchor(replaceDialog, e.getDot());
        });
    }

    /**
     * Invalidates everything derived from the old text: the highlights, the cached scan,
     * and any scan still running against it.
     *
     * <p>During a bulk edit the invalidation is deferred to the end. A Replace All fires
     * this once per replacement — twelve thousand edits meant twelve thousand rounds of
     * highlight teardown on the EDT, all but the last of them describing a document that
     * was about to change again. The version counter still advances per edit, since that
     * is what makes an in-flight scan recognisably stale.</p>
     */
    private void onDocumentChanged() {
        documentVersion++;
        if (bulkEditDepth > 0) {
            return;
        }
        invalidateSearchState();
    }

    private void invalidateSearchState() {
        cachedScan = null;
        cachedQuery = null;
        cachedVersion = -1;
        cancelScan();
        clearHighlights();
    }

    /**
     * Marks the start of an edit made of many document mutations, so the per-edit
     * invalidation above collapses into one at the end.
     */
    private void beginBulkEdit() {
        bulkEditDepth++;
    }

    private void endBulkEdit() {
        if (--bulkEditDepth == 0) {
            invalidateSearchState();
        }
    }

    /** Moves a visible dialog's find-as-you-type origin to {@code dot}. */
    private static void reanchor(SearchDialog sd, int dot) {
        if (sd != null && sd.dialog.isVisible()) {
            sd.anchor = dot;
        }
    }

    /**
     * Shows the find dialog.
     */
    public void showFindDialog() {
        if (findDialog == null) {
            findDialog = createFindDialog();
        }
        showDialog(findDialog);
    }

    /**
     * Shows the find and replace dialog.
     */
    public void showFindReplaceDialog() {
        if (replaceDialog == null) {
            replaceDialog = createReplaceDialog();
        }
        showDialog(replaceDialog);
    }

    /**
     * Repeats the most recent search without needing a dialog open — the Find Next /
     * Find Previous shortcuts. Falls back to opening the Find dialog when there is no
     * previous search to repeat.
     *
     * @param forward true to search forwards, false for backwards
     */
    public void findAgain(boolean forward) {
        SearchDialog sd = lastUsed;
        if (sd == null || sd.searchField.getText().isEmpty()) {
            showFindDialog();
            return;
        }
        find(sd, forward, true);
    }

    /**
     * Releases the scanning thread. Called from {@link EnhancedTextEditor#dispose()},
     * alongside the other executor-backed managers.
     */
    public void dispose() {
        scanner.dispose();
        disposeDialog(findDialog);
        disposeDialog(replaceDialog);
    }

    private static void disposeDialog(SearchDialog sd) {
        if (sd != null) {
            sd.liveSearchTimer.stop();
            sd.dialog.dispose();
        }
    }

    /**
     * Pre-populates the search field with the current selection (if any), then shows
     * the dialog and focuses the search field. The dialogs are non-modal, so
     * setVisible returns immediately and the focus request takes effect.
     */
    private void showDialog(SearchDialog sd) {
        String selectedText = textArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            sd.searchField.setText(selectedText);
        }
        // Anchor find-as-you-type at wherever the caret is now, so typing searches
        // forward from the user's current position rather than from the last match.
        sd.anchor = textArea.getSelectionStart();
        sd.searchField.selectAll();
        sd.dialog.setVisible(true);
        sd.dialog.toFront();
        sd.searchField.requestFocusInWindow();
        refreshStatus(sd);
    }

    // ---------------------------------------------------------------- dialogs

    private SearchDialog createFindDialog() {
        SearchDialog sd = new SearchDialog(newDialog("Find"), false);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.searchField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 2;
        panel.add(sd.statusLabel, gbc);

        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(sd.matchCaseCheckBox, gbc);

        gbc.gridy = 3;
        panel.add(sd.wholeWordCheckBox, gbc);

        gbc.gridy = 4;
        panel.add(sd.regexCheckBox, gbc);

        gbc.gridy = 5;
        panel.add(sd.wrapAroundCheckBox, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> find(sd, true, true));
        buttonPanel.add(findButton);

        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(e -> find(sd, false, true));
        buttonPanel.add(findPrevButton);

        buttonPanel.add(createCloseButton(sd));

        gbc.gridx = 0; gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);

        sd.searchField.addActionListener(e -> find(sd, true, true));

        installLiveSearch(sd);
        finishDialog(sd, panel);
        return sd;
    }

    private SearchDialog createReplaceDialog() {
        SearchDialog sd = new SearchDialog(newDialog("Find and Replace"), true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.searchField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Replace:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.replaceField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 2;
        panel.add(sd.statusLabel, gbc);

        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(sd.matchCaseCheckBox, gbc);

        gbc.gridy = 4;
        panel.add(sd.wholeWordCheckBox, gbc);

        gbc.gridy = 5;
        panel.add(sd.regexCheckBox, gbc);

        gbc.gridy = 6;
        panel.add(sd.wrapAroundCheckBox, gbc);

        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> find(sd, true, true));
        buttonPanel.add(findButton);

        // Present here as well as on the Find dialog: stepping backwards is just as
        // useful when replacing, and its absence was an inconsistency rather than a choice.
        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(e -> find(sd, false, true));
        buttonPanel.add(findPrevButton);

        JButton replaceButton = new JButton("Replace");
        replaceButton.addActionListener(e -> replaceNext(sd));
        buttonPanel.add(replaceButton);

        JButton replaceAllButton = new JButton("Replace All");
        replaceAllButton.addActionListener(e -> replaceAll(sd));
        buttonPanel.add(replaceAllButton);

        buttonPanel.add(createCloseButton(sd));

        gbc.gridx = 0; gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);

        sd.searchField.addActionListener(e -> find(sd, true, true));
        sd.replaceField.addActionListener(e -> replaceNext(sd));

        installLiveSearch(sd);
        finishDialog(sd, panel);
        return sd;
    }

    /**
     * Wires find-as-you-type: every edit of the search field restarts a short debounce,
     * and when it fires the search runs from {@link SearchDialog#anchor}.
     *
     * <p>Option checkboxes re-run it too — toggling "Match case" with a term already
     * typed should update the highlights and the count immediately.</p>
     */
    private void installLiveSearch(SearchDialog sd) {
        sd.liveSearchTimer = new Timer(LIVE_SEARCH_DELAY_MS, e -> liveSearch(sd));
        sd.liveSearchTimer.setRepeats(false);

        Runnable restart = () -> {
            // Abandon the running scan now rather than when the debounce fires: the
            // query has already changed, so whatever it finds is for a question nobody
            // is asking any more.
            cancelScan();
            sd.liveSearchTimer.restart();
        };

        sd.searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                restart.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                restart.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                restart.run();
            }
        });

        java.awt.event.ActionListener onOptionToggled = e -> restart.run();
        sd.matchCaseCheckBox.addActionListener(onOptionToggled);
        sd.wholeWordCheckBox.addActionListener(onOptionToggled);
        sd.regexCheckBox.addActionListener(onOptionToggled);
        sd.wrapAroundCheckBox.addActionListener(onOptionToggled);
    }

    /**
     * One find-as-you-type pass: select the first match at or after the anchor and
     * update the counter. Deliberately does not record navigation history — typing a
     * search term is not a series of jumps the user wants to step back through.
     */
    private void liveSearch(SearchDialog sd) {
        int anchor = Math.min(sd.anchor, textArea.getDocument().getLength());
        programmaticCaretDepth++;
        try {
            textArea.setCaretPosition(anchor);
        } finally {
            programmaticCaretDepth--;
        }
        find(sd, true, false);
    }

    private JDialog newDialog(String title) {
        Window window = SwingUtilities.getWindowAncestor(parentComponent);
        JDialog dialog = new JDialog(window instanceof Frame ? (Frame) window : null, title, false);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        return dialog;
    }

    private JButton createCloseButton(SearchDialog sd) {
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> closeDialog(sd));
        return closeButton;
    }

    /** Stops the debounce, abandons any scan, clears highlights and hides the dialog. */
    private void closeDialog(SearchDialog sd) {
        sd.liveSearchTimer.stop();
        cancelScan();
        clearHighlights();
        sd.dialog.setVisible(false);
    }

    private void finishDialog(SearchDialog sd, JPanel panel) {
        sd.dialog.getRootPane().registerKeyboardAction(
            e -> closeDialog(sd),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        sd.dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                sd.liveSearchTimer.stop();
                cancelScan();
                clearHighlights();
            }
        });

        sd.dialog.add(panel);
        sd.dialog.pack();
        sd.dialog.setLocationRelativeTo(parentComponent);
    }

    // ------------------------------------------------------------------ find

    /**
     * Steps to the next (or previous) match, scanning first if the cached result no
     * longer describes the current query and document.
     *
     * @param recordHistory whether a successful jump joins the navigation history;
     *                      false for find-as-you-type passes
     */
    private void find(SearchDialog sd, boolean forward, boolean recordHistory) {
        SearchQuery query = sd.query();
        if (query.isEmpty()) {
            cancelScan();
            clearHighlights();
            setStatus(sd, " ", false);
            return;
        }
        lastUsed = sd;

        String error = query.validationError();
        if (error != null) {
            cancelScan();
            clearHighlights();
            setStatus(sd, "Invalid regex: " + error, true);
            return;
        }

        withScan(sd, query, scan -> jumpTo(sd, scan, forward, recordHistory));
    }

    /**
     * Supplies a scan describing {@code query} against the current document, either from
     * cache or by running one, and hands it to {@code action} on the EDT.
     */
    private void withScan(SearchDialog sd, SearchQuery query, Consumer<MatchScan> action) {
        if (cachedScan != null && cachedVersion == documentVersion && query.equals(cachedQuery)) {
            action.accept(cachedScan);
            return;
        }

        countingDialog = sd;
        countingIndicator.restart();

        int requestedVersion = documentVersion;
        scanner.scan(textArea.getDocument(), query, MAX_MATCHES,
            scan -> {
                stopCountingIndicator();
                // The document may have moved on while the scan ran; its own listener
                // has already dropped the cache, so simply decline to repopulate it.
                if (requestedVersion != documentVersion) {
                    return;
                }
                cachedScan = scan;
                cachedQuery = query;
                cachedVersion = requestedVersion;
                action.accept(scan);
            },
            failure -> {
                stopCountingIndicator();
                setStatus(sd, "Search failed: " + failure, true);
            });
    }

    /**
     * Selects the match the user asked to move to, wrapping if enabled, and repaints the
     * highlights and counter to match.
     */
    private void jumpTo(SearchDialog sd, MatchScan scan, boolean forward, boolean recordHistory) {
        applyHighlights(scan);

        if (scan.isEmpty()) {
            setStatus(sd, "No results", true);
            return;
        }

        // Start from the far edge of the selection, so the match already selected is not
        // simply found again.
        int from = forward
            ? Math.max(textArea.getSelectionStart(), textArea.getSelectionEnd())
            : Math.min(textArea.getSelectionStart(), textArea.getSelectionEnd());

        int index = forward
            ? scan.indexOfFirstStartingAtOrAfter(from)
            : scan.indexOfLastStartingBefore(from);

        boolean wrapped = false;
        if (index < 0) {
            if (!sd.wrapAroundCheckBox.isSelected()) {
                setStatus(sd, "No more results", true);
                return;
            }
            wrapped = true;
            index = forward ? 0 : scan.count() - 1;
        }

        NavigationHistory.Position beforePos = (textEditor != null)
            ? textEditor.getCurrentPosition() : null;

        MatchScan.MatchRange range = scan.matches().get(index);
        programmaticCaretDepth++;
        try {
            textArea.setSelectionStart(range.start());
            textArea.setSelectionEnd(range.end());
        } finally {
            programmaticCaretDepth--;
        }

        if (recordHistory) {
            recordNavigationIfLineChanged(beforePos);
        }
        setStatus(sd, (index + 1) + " of " + total(scan) + (wrapped ? " (wrapped)" : ""), false);
    }

    /** The match total, marked "+" when the scan stopped at the cap. */
    private static String total(MatchScan scan) {
        return scan.truncated() ? scan.count() + "+" : String.valueOf(scan.count());
    }

    /**
     * Paints the mark-all highlights from a scan.
     *
     * <p>These come from the scan, not from RSTA's own mark-all pass — that pass rescans
     * the whole document on the EDT, which is the cost this class exists to avoid.</p>
     */
    // Package-private rather than private so the accumulation regression below can be
    // pinned directly: this shipped once, and inspection is not a guard.
    void applyHighlights(MatchScan scan) {
        highlightedScan = scan;
        installViewportListener();
        paintHighlightBand(true);
    }

    /**
     * Paints the highlights for a band of document around the viewport.
     *
     * <p>Highlighting used to take the first {@link #MAX_HIGHLIGHTS} matches in document
     * order, which on a large file put every highlight in the opening pages and left the
     * rest of the document bare while the counter reported thousands of matches. What a
     * reader needs highlighted is what is in front of them, so the band follows the
     * viewport instead. It also costs far less: a screenful of matches rather than
     * thousands.</p>
     *
     * <p>The band extends a viewport's worth beyond each edge, so ordinary scrolling
     * moves through already-painted text and only crossing the band triggers a repaint.</p>
     *
     * @param force repaint even if the viewport has not left the painted band
     */
    private void paintHighlightBand(boolean force) {
        if (highlightedScan == null) {
            return;
        }
        // Not during a bulk rewrite. Every edit a Replace All makes resizes the view,
        // which fires the viewport listener below; each of those calls would then cost
        // two viewToModel2D layout queries against a document that is mid-rewrite —
        // thousands of them over a large replace, to paint highlights describing text
        // that is being deleted as we measure it. The band is rebuilt once at the end,
        // when endBulkEdit invalidates and the next search repaints.
        if (bulkEditDepth > 0) {
            return;
        }

        int visibleStart = offsetAt(0, 0);
        int visibleEnd = offsetAtBottomRight();
        if (!force && visibleStart >= highlightBandStart && visibleEnd <= highlightBandEnd) {
            return;
        }

        int margin = Math.max(MIN_HIGHLIGHT_MARGIN, visibleEnd - visibleStart);
        int bandStart = Math.max(0, visibleStart - margin);
        int bandEnd = Math.min(textArea.getDocument().getLength(), visibleEnd + margin);

        List<MatchScan.MatchRange> matches = highlightedScan.matches();
        List<DocumentRange> ranges = new ArrayList<>();

        // Start one before the first match at or after the band, since a match may
        // straddle the boundary and still be partly visible. Clamped at zero: with no
        // matches at all, size - 1 is -1, and -1 is still "less than size" — so an
        // unclamped start walked straight into get(-1) on every term that matched nothing.
        int first = highlightedScan.indexOfFirstStartingAtOrAfter(bandStart);
        int index = Math.max(0, first < 0 ? matches.size() - 1 : first - 1);

        for (int i = index; i < matches.size() && ranges.size() < MAX_HIGHLIGHTS; i++) {
            MatchScan.MatchRange range = matches.get(i);
            if (range.start() >= bandEnd) {
                break;
            }
            if (range.end() > bandStart) {
                ranges.add(new DocumentRange(range.start(), range.end()));
            }
        }

        // markAll ADDS highlights — it does not replace them. Without this clear, every
        // pass stacks another set on top of the last, so narrowing a term from "-01" to
        // "-01-" leaves the old matches lit forever and the highlighting only ever grows.
        // (RSTA's own SearchEngine.find clears immediately before its mark-all pass for
        // exactly this reason; taking over the marking meant taking over the clearing.)
        textArea.clearMarkAllHighlights();
        textArea.markAll(ranges);

        highlightBandStart = bandStart;
        highlightBandEnd = bandEnd;
    }

    /** Document offset at a point in the text area's visible rectangle. */
    private int offsetAt(double xFraction, double yFraction) {
        Rectangle visible = textArea.getVisibleRect();
        int offset = textArea.viewToModel2D(new Point2D.Double(
            visible.getX() + visible.getWidth() * xFraction,
            visible.getY() + visible.getHeight() * yFraction));
        return offset < 0 ? 0 : offset;
    }

    private int offsetAtBottomRight() {
        int offset = offsetAt(1.0, 1.0);
        return offset <= 0 ? textArea.getDocument().getLength() : offset;
    }

    /**
     * Re-bands the highlights when the view scrolls. Installed lazily: the text area is
     * put inside its scroll pane after this manager is constructed, so there is no
     * viewport to listen to yet at construction time.
     */
    private void installViewportListener() {
        if (viewportListenerInstalled) {
            return;
        }
        if (textArea.getParent() instanceof JViewport viewport) {
            viewport.addChangeListener(e -> paintHighlightBand(false));
            viewportListenerInstalled = true;
        }
    }

    /** Recomputes the counter for the current term without moving the caret. */
    private void refreshStatus(SearchDialog sd) {
        SearchQuery query = sd.query();
        if (query.isEmpty()) {
            setStatus(sd, " ", false);
            return;
        }
        String error = query.validationError();
        if (error != null) {
            setStatus(sd, "Invalid regex: " + error, true);
            return;
        }
        withScan(sd, query, scan -> {
            applyHighlights(scan);
            if (scan.isEmpty()) {
                setStatus(sd, "No results", true);
                return;
            }
            int ordinal = scan.ordinalOfMatchStartingAt(textArea.getSelectionStart());
            setStatus(sd, ordinal > 0
                ? ordinal + " of " + total(scan)
                : total(scan) + (scan.count() == 1 ? " match" : " matches"), false);
        });
    }

    // --------------------------------------------------------------- replace

    /**
     * Replaces the next occurrence, then steps to the one after it.
     *
     * <p>Planned off the EDT like Replace All, for the same reason: {@code
     * SearchEngine.replace} locates its match with the same whole-document copy that
     * makes {@code find} unusable here. Every replace invalidates the scan anyway — the
     * offsets after the edit have all moved — so re-planning per replacement is not
     * waste, it is the only correct thing to do.</p>
     */
    private void replaceNext(SearchDialog sd) {
        SearchQuery query = sd.query();
        if (query.isEmpty()) {
            return;
        }
        lastUsed = sd;

        String error = query.validationError();
        if (error != null) {
            setStatus(sd, "Invalid regex: " + error, true);
            return;
        }

        String template = sd.replaceField.getText();
        countingDialog = sd;
        countingIndicator.restart();

        int requestedVersion = documentVersion;
        int from = Math.min(textArea.getSelectionStart(), textArea.getSelectionEnd());

        scanner.compute(textArea.getDocument(),
            text -> ReplacementPlanner.plan(text, query, template, MAX_MATCHES),
            plan -> {
                stopCountingIndicator();
                if (requestedVersion != documentVersion) {
                    setStatus(sd, "Document changed — try again", true);
                    return;
                }
                replaceOne(sd, plan, from);
            },
            failure -> {
                stopCountingIndicator();
                setStatus(sd, describeReplacementFailure(failure), true);
            });
    }

    /**
     * Applies the first planned replacement at or after {@code from}, wrapping if
     * enabled, then advances to the following match.
     */
    private void replaceOne(SearchDialog sd, List<Replacement> plan, int from) {
        if (plan.isEmpty()) {
            setStatus(sd, "No results", true);
            return;
        }

        int index = indexAtOrAfter(plan, from);
        if (index < 0) {
            if (!sd.wrapAroundCheckBox.isSelected()) {
                setStatus(sd, "No more results", true);
                return;
            }
            index = 0;
        }

        Replacement replacement = plan.get(index);
        programmaticCaretDepth++;
        // One edit, but still bracketed: undo should retire the replacement as a unit
        // rather than as a remove and an insert.
        textArea.beginAtomicEdit();
        try {
            textArea.getDocument().remove(replacement.start(), replacement.length());
            textArea.getDocument().insertString(replacement.start(), replacement.text(), null);
            textArea.setCaretPosition(replacement.start() + replacement.text().length());
            selectNextAfterReplace(sd, plan, index);
        } catch (javax.swing.text.BadLocationException e) {
            setStatus(sd, "Replace failed: " + e.getMessage(), true);
            return;
        } finally {
            textArea.endAtomicEdit();
            programmaticCaretDepth--;
        }

        setStatus(sd, "Replaced", false);
    }

    /**
     * Moves to the match after the one just replaced, without re-scanning.
     *
     * <p>Re-running the search here was costing a second full-document pass on every
     * Replace — the plan already says where every other match is, and exactly one edit
     * has happened since it was built. Matches after the edit shift by the difference in
     * length; matches before it do not move at all. That is enough to step correctly,
     * and it turns each Replace from two whole-document walks into one.</p>
     */
    private void selectNextAfterReplace(SearchDialog sd, List<Replacement> plan, int replacedIndex) {
        if (plan.size() < 2) {
            return; // Nothing else to step to.
        }

        Replacement replaced = plan.get(replacedIndex);
        int next = replacedIndex + 1;

        int start;
        int end;
        if (next < plan.size()) {
            // Later in the document than the edit, so displaced by its change in length.
            int delta = replaced.text().length() - replaced.length();
            start = plan.get(next).start() + delta;
            end = plan.get(next).end() + delta;
        } else {
            if (!sd.wrapAroundCheckBox.isSelected()) {
                return;
            }
            // Wrapping to the top, which lies before the edit and so has not moved.
            start = plan.get(0).start();
            end = plan.get(0).end();
        }

        textArea.setSelectionStart(start);
        textArea.setSelectionEnd(end);
    }

    /** Index of the first planned replacement starting at or after {@code offset}, or -1. */
    private static int indexAtOrAfter(List<Replacement> plan, int offset) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.get(i).start() >= offset) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Replaces all occurrences of the search term.
     *
     * <p>Three phases, so that the expensive one never touches the EDT: plan every edit
     * off the EDT (cancellable, no copy of the document); tell the user exactly how many
     * there are and let them decline; then apply them behind a modal progress dialog.
     * Unlike finding, this one is not routed through {@code SearchEngine} either — its
     * {@code replaceAll} rebuilds the document on the EDT.</p>
     */
    private void replaceAll(SearchDialog sd) {
        SearchQuery query = sd.query();
        if (query.isEmpty()) {
            return;
        }
        lastUsed = sd;

        String error = query.validationError();
        if (error != null) {
            setStatus(sd, "Invalid regex: " + error, true);
            return;
        }

        String template = sd.replaceField.getText();
        countingDialog = sd;
        countingIndicator.restart();

        int requestedVersion = documentVersion;
        scanner.compute(textArea.getDocument(),
            text -> ReplacementPlanner.plan(text, query, template, MAX_MATCHES),
            plan -> {
                stopCountingIndicator();
                if (requestedVersion != documentVersion) {
                    // The text moved on while we were planning; those offsets describe a
                    // document that no longer exists, so the plan is unusable.
                    setStatus(sd, "Document changed — try again", true);
                    return;
                }
                applyPlan(sd, plan);
            },
            failure -> {
                stopCountingIndicator();
                // A malformed template ($9 against a one-group pattern, a trailing
                // backslash) surfaces here rather than part way through the edits,
                // because planning happens before anything is written.
                setStatus(sd, describeReplacementFailure(failure), true);
            });
    }

    /** Confirms a large replace, then applies the plan. */
    private void applyPlan(SearchDialog sd, List<Replacement> plan) {
        if (plan.isEmpty()) {
            setStatus(sd, "No results", true);
            return;
        }

        if (plan.size() > ChunkedReplacer.PROGRESS_THRESHOLD && !confirmLargeReplace(sd, plan.size())) {
            setStatus(sd, "Cancelled", false);
            return;
        }

        // The replace moves the caret and rewrites the text; neither should be mistaken
        // for the user repositioning the search origin, and the cached scan describes a
        // document that is about to stop existing.
        programmaticCaretDepth++;
        beginBulkEdit();
        try {
            // Synchronous from here: the modal dialog's nested event loop runs the chunks,
            // and setVisible does not return until the run disposes it.
            ChunkedReplacer.apply(textArea, parentComponent, plan,
                outcome -> setStatus(sd, describeOutcome(outcome), !outcome.isComplete()));
        } finally {
            endBulkEdit();
            programmaticCaretDepth--;
        }
    }

    /**
     * Asks before a large replace. Deliberately modal and deliberately exact: the count
     * is already known from planning, and a long operation the user cannot decline is
     * worse than one they chose.
     */
    private boolean confirmLargeReplace(SearchDialog sd, int count) {
        String message = String.format("Replace %,d occurrences of \"%s\"?",
            count, sd.searchField.getText());
        return JOptionPane.showConfirmDialog(sd.dialog, message, "Replace All",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static String describeOutcome(ChunkedReplacer.Outcome outcome) {
        if (outcome.isComplete()) {
            int applied = outcome.applied();
            return String.format("%,d %s replaced", applied,
                applied == 1 ? "occurrence" : "occurrences");
        }
        // A cancelled run is one undoable step, so this is a real offer, not a platitude.
        return String.format("Stopped after %,d of %,d — undo to revert",
            outcome.applied(), outcome.total());
    }

    private static String describeReplacementFailure(RuntimeException failure) {
        if (failure instanceof IndexOutOfBoundsException || failure instanceof IllegalArgumentException) {
            return "Invalid replacement: " + failure.getMessage();
        }
        return "Replace failed: " + failure;
    }


    // ---------------------------------------------------------------- status

    /**
     * Writes the inline feedback line. Problems are coloured; ordinary counts are not,
     * so a miss reads as different in kind from "3 of 17" rather than just different text.
     */
    private void setStatus(SearchDialog sd, String message, boolean problem) {
        sd.statusLabel.setText(message);
        sd.statusLabel.setForeground(problem ? problemColor() : UIManager.getColor("Label.foreground"));
    }

    /** A red that reads on both light and dark themes, preferring the theme's own. */
    private static Color problemColor() {
        Color themed = UIManager.getColor("Actions.Red");
        return themed != null ? themed : new Color(0xC7, 0x52, 0x4A);
    }

    /** Abandons any running scan and retires the pending "Counting…" indicator with it. */
    private void cancelScan() {
        scanner.cancel();
        stopCountingIndicator();
    }

    /**
     * Cancels the pending indicator. Safe to call when none is pending; the point is
     * that no code path may leave "Counting…" on screen after the scan it described has
     * finished, been superseded, or been abandoned.
     */
    private void stopCountingIndicator() {
        countingIndicator.stop();
        countingDialog = null;
    }

    private void clearHighlights() {
        highlightedScan = null;
        highlightBandStart = 0;
        highlightBandEnd = 0;
        textArea.clearMarkAllHighlights();
    }

    /**
     * Records a navigation jump if the current position is on a different line
     * than the position before the find operation.
     */
    private void recordNavigationIfLineChanged(NavigationHistory.Position beforePos) {
        if (textEditor == null || beforePos == null) {
            return;
        }

        NavigationHistory.Position afterPos = textEditor.getCurrentPosition();
        if (afterPos.line() != beforePos.line()) {
            textEditor.getNavigationHistory().recordJump(beforePos, afterPos);
        }
    }
}
