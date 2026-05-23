package com.kalix.ide.linter.parsing;

/**
 * Utilities for resolving multi-line value continuations in Kalix INI text.
 *
 * <p>Kalix INI files allow a property's value to extend over multiple lines:
 * any line that is non-empty and begins with whitespace continues the value of
 * the property on the line above. A blank line, a section header, or a
 * flush-left comment terminates the chain. This mirrors the rule used by
 * {@link INIModelParser#collectContinuationLines}; if that rule ever changes
 * this class must change with it.</p>
 *
 * <p>The methods here let context-sensitive editor features (autocomplete,
 * right-click commands) ask <em>"which property line owns the cursor's current
 * line?"</em> so they can classify the cursor as being inside a value even when
 * the line itself contains no {@code =}.</p>
 */
public final class IniContinuation {

    private IniContinuation() {
        // utility class
    }

    /**
     * Returns {@code true} if the given line is a continuation line: non-empty
     * and starting with a whitespace character. This is the exact rule used by
     * the parser when joining multi-line values.
     */
    public static boolean isContinuationLine(String line) {
        return line != null && !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    /**
     * Returns the index of the property-header line that owns
     * {@code lines[lineIndex]}, taking continuations into account.
     *
     * <ul>
     *   <li>If the current line is itself a property header, returns
     *       {@code lineIndex}.</li>
     *   <li>If the current line is a continuation, walks back over preceding
     *       continuation lines and returns the index of the property header
     *       that begins the chain.</li>
     *   <li>Returns {@code -1} for blank lines, section headers, flush-left
     *       comments, or any line whose continuation chain does not terminate
     *       at a property header (e.g. an orphan indented line at the top of
     *       the file).</li>
     * </ul>
     *
     * <p>A "property header" is a non-empty line that does not start with
     * whitespace, is not a comment ({@code ;} or {@code #}) or section header
     * ({@code [}), and contains an {@code =} in its un-commented portion.</p>
     */
    public static int findOwningPropertyLine(String[] lines, int lineIndex) {
        if (lines == null || lineIndex < 0 || lineIndex >= lines.length) {
            return -1;
        }

        // Case 1: the current line is itself a property header.
        if (isPropertyHeader(lines[lineIndex])) {
            return lineIndex;
        }

        // Only a continuation line can have an owning header above it.
        if (!isContinuationLine(lines[lineIndex])) {
            return -1;
        }

        // Walk backward over continuation lines until we find the header, or
        // until the chain breaks (blank line, section header, flush-left
        // comment) before we reach one.
        for (int i = lineIndex - 1; i >= 0; i--) {
            String line = lines[i];
            if (isPropertyHeader(line)) {
                return i;
            }
            if (!isContinuationLine(line)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * True if the line begins a {@code key = value} property declaration.
     * Must be non-empty, not start with whitespace, not be a comment or section
     * header, and contain {@code =} in its un-commented portion.
     */
    private static boolean isPropertyHeader(String line) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        char first = line.charAt(0);
        if (first == ';' || first == '#' || first == '[') {
            return false;
        }
        return hasEqualsBeforeComment(line);
    }

    /**
     * True if the line contains {@code =} before any comment marker.
     */
    private static boolean hasEqualsBeforeComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=') {
                return true;
            }
            if (c == ';' || c == '#') {
                return false;
            }
        }
        return false;
    }
}
