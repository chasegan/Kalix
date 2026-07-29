package com.kalix.ide.editor.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates every match of a {@link SearchQuery} in a {@link CharSequence}.
 *
 * <h2>Fidelity to RSTA</h2>
 * These rules are not invented, they are reproduced from {@code SearchEngine}, because a
 * counter that disagrees with the matches Find Next actually visits is worse than no
 * counter at all. Three rules matter, and two of them are easy to get wrong:
 * <ul>
 *   <li><b>Matches do not overlap.</b> The engine resumes after each match, so
 *       {@code "aa"} occurs twice in {@code "aaaa"}, not three times.</li>
 *   <li><b>Whole-word differs between modes.</b> Literal search asks whether the
 *       neighbouring characters are letters or digits, so an underscore is a boundary
 *       and {@code 002_dam} contains a whole-word {@code dam}. Regex search wraps the
 *       pattern in {@code \b…\b}, where {@code _} <em>is</em> a word character, so the
 *       same search finds nothing. The asymmetry is RSTA's; it is preserved rather than
 *       smoothed over, and it is load-bearing for Kalix node names.</li>
 *   <li><b>Regex flags are {@code MULTILINE}</b> plus case-insensitivity when Match
 *       case is off — see {@link SearchQuery#compile()}.</li>
 * </ul>
 *
 * <p>Pure and stateless: give it a String in a test, a {@link DocumentCharSequence} in
 * production. Cancellation is the sequence's concern, not this class's — a cancelled
 * scan surfaces as {@link SearchCancelledException} propagating out of {@code charAt}.</p>
 */
public final class MatchScanner {

    private MatchScanner() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Finds every match, stopping early at {@code maxMatches}.
     *
     * @param text       the text to scan
     * @param query      what to look for
     * @param maxMatches cap on results; bounds memory on a pathological search (a
     *                   single-character term across a huge file) at the cost of an
     *                   exact total, which {@link MatchScan#truncated()} then reports
     * @return the matches, in document order
     * @throws java.util.regex.PatternSyntaxException if a regex query is invalid
     * @throws SearchCancelledException if {@code text} reports cancellation mid-scan
     */
    public static MatchScan scan(CharSequence text, SearchQuery query, int maxMatches) {
        if (query.isEmpty() || text.length() == 0 || maxMatches <= 0) {
            return MatchScan.empty();
        }
        return query.regex()
            ? scanRegex(text, query, maxMatches)
            : scanLiteral(text, query, maxMatches);
    }

    private static MatchScan scanRegex(CharSequence text, SearchQuery query, int maxMatches) {
        Pattern pattern = query.compile();
        Matcher matcher = pattern.matcher(text);
        List<MatchScan.MatchRange> matches = new ArrayList<>();

        int from = 0;
        int limit = text.length();
        while (from <= limit && matcher.find(from)) {
            matches.add(new MatchScan.MatchRange(matcher.start(), matcher.end()));
            if (matches.size() > maxMatches) {
                return truncatedAt(matches, maxMatches);
            }
            // A zero-length match (e.g. "x*") would otherwise pin the matcher in place.
            from = matcher.end() > matcher.start() ? matcher.end() : matcher.start() + 1;
        }
        return new MatchScan(matches, false);
    }

    private static MatchScan scanLiteral(CharSequence text, SearchQuery query, int maxMatches) {
        String term = query.term();
        boolean matchCase = query.matchCase();
        boolean wholeWord = query.wholeWord();
        int termLength = term.length();
        int limit = text.length() - termLength;

        // Compare the first character before the rest: it rejects the overwhelming
        // majority of offsets in one read, which matters when the sequence is a
        // windowed view over a very large document.
        char first = term.charAt(0);
        char firstUpper = Character.toUpperCase(first);
        char firstLower = Character.toLowerCase(first);

        List<MatchScan.MatchRange> matches = new ArrayList<>();
        for (int i = 0; i <= limit; i++) {
            char c = text.charAt(i);
            if (matchCase ? c != first : (c != firstUpper && c != firstLower)) {
                continue;
            }
            if (!regionMatches(text, i, term, matchCase)) {
                continue;
            }
            if (wholeWord && !isWholeWord(text, i, termLength)) {
                continue;
            }
            matches.add(new MatchScan.MatchRange(i, i + termLength));
            if (matches.size() > maxMatches) {
                return truncatedAt(matches, maxMatches);
            }
            // Resume after the match: the engine does not report overlapping hits.
            i += termLength - 1;
        }
        return new MatchScan(matches, false);
    }

    /**
     * Trims an over-collected list back to the cap and flags it truncated.
     *
     * <p>Scanning deliberately runs one match past {@code maxMatches} before giving up,
     * so that "truncated" means there really is more to find. Stopping the moment the
     * cap was reached would report a document with exactly {@code maxMatches} matches as
     * having "{@code maxMatches}+", which is simply untrue.</p>
     */
    private static MatchScan truncatedAt(List<MatchScan.MatchRange> matches, int maxMatches) {
        return new MatchScan(matches.subList(0, maxMatches), true);
    }

    /** {@code String.regionMatches} for an arbitrary CharSequence. */
    private static boolean regionMatches(CharSequence text, int offset, String term, boolean matchCase) {
        for (int i = 0; i < term.length(); i++) {
            char a = text.charAt(offset + i);
            char b = term.charAt(i);
            if (a == b) {
                continue;
            }
            if (matchCase) {
                return false;
            }
            // Fold both ways, as String.equalsIgnoreCase does: neither direction alone
            // is correct for every alphabet.
            if (Character.toUpperCase(a) != Character.toUpperCase(b)
                && Character.toLowerCase(a) != Character.toLowerCase(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the characters either side of {@code text[offset, offset+length)} are not
     * letters or digits — {@code SearchEngine.isWholeWord}, reproduced. Running off
     * either end of the text counts as a boundary.
     */
    private static boolean isWholeWord(CharSequence text, int offset, int length) {
        boolean freeBefore = offset == 0
            || !Character.isLetterOrDigit(text.charAt(offset - 1));
        boolean freeAfter = offset + length >= text.length()
            || !Character.isLetterOrDigit(text.charAt(offset + length));
        return freeBefore && freeAfter;
    }
}
