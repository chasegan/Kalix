package com.kalix.ide.editor.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Works out every edit a Replace All will make, before any of them are made.
 *
 * <p>Planning is separated from applying so that the expensive half — walking the whole
 * document — happens off the EDT and can be cancelled, while the half that must happen
 * on the EDT is reduced to a list of ready-made edits. It also means the user can be
 * told exactly what is about to happen, and how much of it, before anything changes.</p>
 *
 * <p>Literal replacement needs no per-match work: every match becomes the same text. A
 * regex replacement does, because the template may reference groups, so those matches
 * are re-walked with a {@link Matcher} over the same sequence — anchors and boundaries
 * evaluated against the whole document, not against isolated match text, which is why
 * the matches are not simply re-examined one at a time.</p>
 */
public final class ReplacementPlanner {

    private ReplacementPlanner() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Builds the plan, in document order.
     *
     * @param text       the document to plan against
     * @param query      what to match
     * @param template   the replacement, with {@code $n} references when {@code query} is a regex
     * @param maxMatches cap, as for {@link MatchScanner#scan}
     * @throws SearchCancelledException if {@code text} reports cancellation mid-scan
     * @throws IllegalArgumentException if the template is malformed
     * @throws IndexOutOfBoundsException if the template references a group the pattern lacks
     */
    public static List<Replacement> plan(CharSequence text, SearchQuery query,
                                         String template, int maxMatches) {
        if (query.isEmpty() || text.length() == 0 || maxMatches <= 0) {
            return List.of();
        }
        return query.regex()
            ? planRegex(text, query, template, maxMatches)
            : planLiteral(text, query, template, maxMatches);
    }

    /** Every match becomes the same text, so the ranges from a normal scan suffice. */
    private static List<Replacement> planLiteral(CharSequence text, SearchQuery query,
                                                 String template, int maxMatches) {
        MatchScan scan = MatchScanner.scan(text, query, maxMatches);
        List<Replacement> plan = new ArrayList<>(scan.count());
        for (MatchScan.MatchRange range : scan.matches()) {
            plan.add(new Replacement(range.start(), range.end(), template));
        }
        return plan;
    }

    /**
     * Walks the matches with a live {@link Matcher} so each match's groups are available
     * to {@link ReplacementTemplate}.
     */
    private static List<Replacement> planRegex(CharSequence text, SearchQuery query,
                                               String template, int maxMatches) {
        Matcher matcher = query.compile().matcher(text);
        List<Replacement> plan = new ArrayList<>();

        int from = 0;
        int limit = text.length();
        while (from <= limit && matcher.find(from)) {
            plan.add(new Replacement(matcher.start(), matcher.end(),
                ReplacementTemplate.expand(template, matcher)));
            if (plan.size() >= maxMatches) {
                break;
            }
            // A zero-length match would otherwise pin the matcher in place.
            from = matcher.end() > matcher.start() ? matcher.end() : matcher.start() + 1;
        }
        return plan;
    }
}
