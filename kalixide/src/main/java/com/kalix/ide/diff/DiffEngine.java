package com.kalix.ide.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;
import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Utility class for computing differences between text strings.
 * Uses the Myers diff algorithm via java-diff-utils library.
 */
public class DiffEngine {
    private static final Logger logger = LoggerFactory.getLogger(DiffEngine.class);

    /**
     * In-band markers wrapping changed segments in the generated diff rows.
     * The generator can only emit string tags (not positions), so an improbable
     * multi-character sentinel built from Unicode private-use characters is used
     * instead of a printable character: a literal '~' (or any other model text)
     * must survive the diff view unmangled, and highlight ranges must not shift.
     * {@link #stripInlineMarkers} converts the markers back into position ranges.
     */
    static final String INLINE_MARKER_OPEN = "\uE000\uE001";
    static final String INLINE_MARKER_CLOSE = "\uE001\uE000";

    /**
     * A character range (within the cleaned text) that should be highlighted as an
     * inline change.
     */
    public record InlineChange(int startOffset, int endOffset) {}

    /**
     * Computes the difference between two strings and generates diff rows for display.
     *
     * @param original The original text
     * @param modified The modified text
     * @return DiffResult containing diff rows and statistics
     */
    public static DiffResult computeDiff(String original, String modified) {
        try {
            // Split into lines
            List<String> originalLines = splitLines(original);
            List<String> modifiedLines = splitLines(modified);

            // Configure diff row generator with character-level inline diffs
            DiffRowGenerator generator = DiffRowGenerator.create()
                .showInlineDiffs(true)          // Enable character-level highlighting
                .inlineDiffByWord(true)         // Use word boundaries for better readability
                .mergeOriginalRevised(false)    // Keep separate columns for side-by-side view
                .reportLinesUnchanged(true)     // Include unchanged lines for context
                .lineNormalizer(line -> line)   // No normalization
                .oldTag(open -> open ? INLINE_MARKER_OPEN : INLINE_MARKER_CLOSE)
                .newTag(open -> open ? INLINE_MARKER_OPEN : INLINE_MARKER_CLOSE)
                .build();

            // Generate diff rows
            List<DiffRow> rows = generator.generateDiffRows(originalLines, modifiedLines);

            // Compute patch for statistics
            Patch<String> patch = DiffUtils.diff(originalLines, modifiedLines);

            return new DiffResult(rows, patch);

        } catch (Exception e) {
            logger.error("Error computing diff", e);
            // Return empty result on error
            return new DiffResult(List.of(), null);
        }
    }

    /**
     * Removes the inline-change markers from a generated diff row line, recording the
     * character ranges (in cleaned-text coordinates, shifted by {@code baseOffset})
     * that should be highlighted.
     *
     * @param line          a diff row line potentially containing inline markers
     * @param baseOffset    offset of this line within the document being assembled
     * @param inlineChanges receives one {@link InlineChange} per marked segment
     * @return the line with all markers removed
     */
    public static String stripInlineMarkers(String line, int baseOffset, List<InlineChange> inlineChanges) {
        StringBuilder cleaned = new StringBuilder(line.length());
        int i = 0;
        int changeStart = -1;

        while (i < line.length()) {
            if (line.startsWith(INLINE_MARKER_OPEN, i)) {
                changeStart = cleaned.length();
                i += INLINE_MARKER_OPEN.length();
            } else if (line.startsWith(INLINE_MARKER_CLOSE, i)) {
                if (changeStart >= 0) {
                    inlineChanges.add(new InlineChange(
                        baseOffset + changeStart,
                        baseOffset + cleaned.length()
                    ));
                    changeStart = -1;
                }
                i += INLINE_MARKER_CLOSE.length();
            } else {
                cleaned.append(line.charAt(i));
                i++;
            }
        }

        return cleaned.toString();
    }

    /**
     * Splits text into lines, preserving empty lines.
     */
    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        // Use -1 to keep trailing empty strings
        return Arrays.asList(text.split("\n", -1));
    }
}
