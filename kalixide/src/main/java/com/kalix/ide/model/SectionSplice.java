package com.kalix.ide.model;

/**
 * The document edit that splices a block of INI text — one node section or several —
 * into model text at a given offset: which span to remove, and what to put in its place.
 *
 * <p>The third of three collaborators, each with one job. {@link NodeSectionLocator}
 * says where the sections <em>are</em>; {@link NodeInsertionPoint} says where a new one
 * <em>goes</em>; this says how to <em>write</em> it there. Every feature that adds a
 * node section to the text — template insertion, paste — goes through all three, so a
 * fix to any one of them reaches every call site.
 *
 * <p>Separated from the {@code Document} mutation so the fiddly part — how many blank
 * lines end up on each side of the seam — is a pure function of {@code (text, offset,
 * block)} and can be tested without a Swing text component.
 *
 * <p>The whitespace run is normalised on <em>both</em> sides of the offset. Insertion
 * offsets land at a section header (inserting above the first node) as readily as at
 * the end of a section's last line, and a one-sided normalisation grows the gap on
 * repeated insertion at the seam it does not touch. Doing both sides makes the edit
 * idempotent wherever it is applied.
 *
 * <p>The removed span is whole blank <em>lines</em>, never a partial line. It stops at
 * the start of the following content line, so that line's indentation survives — an
 * indented section header is legal, and a naive whitespace scan would swallow its
 * leading spaces. For the same reason it never eats back past the newline that
 * terminates the preceding content line, leaving that line's own trailing spaces alone.
 */
public final class SectionSplice {

    /** Remove {@code [start, end)} from the document, then insert {@code text} at {@code start}. */
    public record Splice(int start, int end, String text) {
    }

    private SectionSplice() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    public static Splice compute(String text, int offset, String block) {
        int anchor = Math.max(0, Math.min(offset, text.length()));

        // Walk back over whitespace to the preceding content character.
        int lastContent = anchor - 1;
        while (lastContent >= 0 && Character.isWhitespace(text.charAt(lastContent))) {
            lastContent--;
        }
        boolean atDocumentStart = (lastContent < 0);

        // Delete from the newline that ends that content line, not from the content
        // itself. With no content above, delete the leading whitespace entirely.
        int gapStart = atDocumentStart ? 0 : anchor;
        if (!atDocumentStart) {
            int newline = text.indexOf('\n', lastContent + 1);
            if (newline != -1 && newline < anchor) {
                gapStart = newline;
            }
        }

        // Walk forward over whitespace to the following content character.
        int nextContent = anchor;
        while (nextContent < text.length() && Character.isWhitespace(text.charAt(nextContent))) {
            nextContent++;
        }
        boolean atDocumentEnd = (nextContent == text.length());

        // Stop at the start of that content line, preserving its indentation.
        int gapEnd = text.length();
        if (!atDocumentEnd) {
            int precedingNewline = nextContent == 0 ? -1 : text.lastIndexOf('\n', nextContent - 1);
            gapEnd = (precedingNewline == -1) ? anchor : Math.max(anchor, precedingNewline + 1);
        }

        // Leading "\n\n" terminates the preceding content line and leaves one blank line.
        String replacement = (atDocumentStart ? "" : "\n\n")
            + block
            + (atDocumentEnd ? "\n" : "\n\n");

        return new Splice(gapStart, gapEnd, replacement);
    }

    /** Applies a splice to a string. The editor applies it to a {@code Document} instead. */
    public static String applyTo(String text, Splice splice) {
        return text.substring(0, splice.start()) + splice.text() + text.substring(splice.end());
    }
}
