package com.kalix.ide.editor;

import com.kalix.ide.editor.search.MatchScan;
import com.kalix.ide.editor.search.MatchScanner;
import com.kalix.ide.editor.search.SearchQuery;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextAreaHighlighter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Guards against search highlights accumulating instead of replacing.
 *
 * <p>{@code RTextArea.markAll} only ever <em>adds</em> highlights — RSTA's own
 * {@code SearchEngine.find} clears immediately before its mark-all pass for that reason.
 * Taking over the marking without taking over the clearing shipped a bug where narrowing
 * a term left every earlier term's matches lit: typing {@code -01} then {@code -01-} in
 * a file of dates highlighted rows that no longer matched anything.</p>
 *
 * <p>Constructs Swing components, so it is display-gated like {@link EditorDisposalTest}.</p>
 */
class SearchHighlightAccumulationTest {

    /** Dates like the CSV the bug was found in: some contain "-01-", some only "-01". */
    private static final String TEXT = """
        1889-01-01,0,0,0
        1889-01-02,0,0,0
        1889-02-01,0,0,0
        1889-02-02,0,0,0
        """;

    private RSyntaxTextArea textArea;
    private TextSearchManager searchManager;

    @BeforeEach
    void setUp() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        textArea = new RSyntaxTextArea();
        textArea.setText(TEXT);
        searchManager = new TextSearchManager(textArea, new JPanel());
    }

    private int markedCount() {
        return ((RTextAreaHighlighter) textArea.getHighlighter()).getMarkAllHighlightCount();
    }

    private MatchScan scanFor(String term) {
        return MatchScanner.scan(TEXT, new SearchQuery(term, false, false, false), Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Narrowing the term retires the previous term's highlights")
    void narrowingTermReplacesHighlights() {
        // "-01" matches four times, not three: the month in rows 1-2, the day in rows
        // 1 and 3. Row 1 ("1889-01-01") contains it twice — which is exactly the
        // two-highlights-per-row pattern the original bug report showed.
        MatchScan broad = scanFor("-01");
        assertEquals(4, broad.count(), "fixture");
        searchManager.applyHighlights(broad);
        assertEquals(4, markedCount());

        // "-01-" matches only the month in rows 1-2. Row 3 must go dark rather than
        // keep the highlight it earned under the previous term.
        MatchScan narrow = scanFor("-01-");
        assertEquals(2, narrow.count(), "fixture");
        searchManager.applyHighlights(narrow);
        assertEquals(2, markedCount(), "highlights accumulated instead of being replaced");
    }

    @Test
    @DisplayName("Re-applying the same scan does not double the highlights")
    void repeatedApplicationIsIdempotent() {
        MatchScan scan = scanFor("-01-");
        searchManager.applyHighlights(scan);
        int first = markedCount();
        searchManager.applyHighlights(scan);
        assertEquals(first, markedCount(), "re-applying stacked a second set");
    }

    @Test
    @DisplayName("A term with no matches leaves nothing highlighted")
    void noMatchesClearsHighlights() {
        searchManager.applyHighlights(scanFor("-01"));
        assertEquals(4, markedCount());

        searchManager.applyHighlights(scanFor("nothing-here"));
        assertEquals(0, markedCount());
    }
}
