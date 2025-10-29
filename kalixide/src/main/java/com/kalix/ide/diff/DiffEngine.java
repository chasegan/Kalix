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
                .oldTag(f -> "~")              // Marker for changed text segments
                .newTag(f -> "~")              // Marker for changed text segments
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
