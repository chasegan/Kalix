package com.kalix.ide.editor.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the windowed document view: that it reads the same characters a String would,
 * across window boundaries, and that it can be stopped mid-scan.
 */
class DocumentCharSequenceTest {

    /** Comfortably more than the 64K window, so reads must cross several boundaries. */
    private static final int LONG_ENOUGH_TO_SPAN_WINDOWS = 200_000;

    private static PlainDocument documentOf(String text) throws BadLocationException {
        PlainDocument document = new PlainDocument();
        document.insertString(0, text, null);
        return document;
    }

    private static DocumentCharSequence over(String text) throws BadLocationException {
        return new DocumentCharSequence(documentOf(text), () -> false);
    }

    @Test
    @DisplayName("Reads the same characters as the underlying text")
    void readsSameCharacters() throws BadLocationException {
        String text = "[node_a]\nx = 1234.0\n";
        DocumentCharSequence sequence = over(text);

        assertEquals(text.length(), sequence.length());
        for (int i = 0; i < text.length(); i++) {
            assertEquals(text.charAt(i), sequence.charAt(i), "at " + i);
        }
    }

    @Test
    @DisplayName("Reads correctly across window boundaries, forwards and backwards")
    void readsAcrossWindowBoundaries() throws BadLocationException {
        StringBuilder builder = new StringBuilder(LONG_ENOUGH_TO_SPAN_WINDOWS);
        for (int i = 0; i < LONG_ENOUGH_TO_SPAN_WINDOWS; i++) {
            builder.append((char) ('a' + (i % 26)));
        }
        String text = builder.toString();
        DocumentCharSequence sequence = over(text);

        // Forwards, straight through every window.
        for (int i = 0; i < text.length(); i++) {
            assertEquals(text.charAt(i), sequence.charAt(i), "forward at " + i);
        }
        // Backwards, forcing a refill on nearly every step — the access pattern regex
        // backtracking produces.
        for (int i = text.length() - 1; i >= 0; i--) {
            assertEquals(text.charAt(i), sequence.charAt(i), "backward at " + i);
        }
        // Random-ish jumps far apart, to catch a window that assumes locality.
        for (int i = 0; i < 500; i++) {
            int index = (i * 7919) % text.length();
            assertEquals(text.charAt(index), sequence.charAt(index), "jump at " + index);
        }
    }

    @Test
    @DisplayName("Scanning a document view matches scanning the equivalent String")
    void scanAgreesWithStringScan() throws BadLocationException {
        String text = ("alpha beta gamma dam 002_dam delta\n").repeat(4000);
        SearchQuery query = new SearchQuery("dam", false, false, false);

        List<MatchScan.MatchRange> viaDocument =
            MatchScanner.scan(over(text), query, Integer.MAX_VALUE).matches();
        List<MatchScan.MatchRange> viaString =
            MatchScanner.scan(text, query, Integer.MAX_VALUE).matches();

        assertEquals(viaString, viaDocument);
        assertTrue(viaString.size() > 1000, "expected the fixture to span many windows");
    }

    @Test
    @DisplayName("Out-of-range access is rejected")
    void outOfRange() throws BadLocationException {
        DocumentCharSequence sequence = over("abc");
        assertThrows(IndexOutOfBoundsException.class, () -> sequence.charAt(3));
        assertThrows(IndexOutOfBoundsException.class, () -> sequence.charAt(-1));
    }

    @Test
    @DisplayName("subSequence materialises just the requested span")
    void subSequence() throws BadLocationException {
        DocumentCharSequence sequence = over("[node_a]\nx = 1");
        assertEquals("node_a", sequence.subSequence(1, 7).toString());
    }

    @Test
    @DisplayName("Cancellation stops a scan in progress")
    void cancellationStopsAScan() throws BadLocationException {
        String text = "dam ".repeat(LONG_ENOUGH_TO_SPAN_WINDOWS / 4);
        AtomicBoolean cancelled = new AtomicBoolean();
        DocumentCharSequence sequence =
            new DocumentCharSequence(documentOf(text), cancelled::get);

        cancelled.set(true);
        assertThrows(SearchCancelledException.class,
            () -> MatchScanner.scan(sequence, new SearchQuery("dam", true, false, false),
                Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("Cancellation part-way through abandons the scan rather than completing it")
    void cancellationMidScan() throws BadLocationException {
        String text = "dam ".repeat(LONG_ENOUGH_TO_SPAN_WINDOWS / 4);
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();

        // Trip the flag once the scan is well under way, then confirm it unwinds
        // instead of running to the end of a very long document.
        DocumentCharSequence sequence = new DocumentCharSequence(documentOf(text), () -> {
            if (reads.incrementAndGet() > 2) {
                cancelled.set(true);
            }
            return cancelled.get();
        });

        assertThrows(SearchCancelledException.class,
            () -> MatchScanner.scan(sequence, new SearchQuery("dam", true, false, false),
                Integer.MAX_VALUE));
    }
}
