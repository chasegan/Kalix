package com.kalix.ide.editor.search;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An immutable search term plus the options that decide what it matches.
 *
 * <p>Deliberately free of Swing: this is the value that crosses from the dialog on the
 * EDT to the scanner on a background thread, and a value that cannot be mutated in
 * flight is the cheapest way to make that crossing safe. Its {@code equals} also lets
 * callers recognise that a completed scan still describes the current query.</p>
 */
public record SearchQuery(String term, boolean matchCase, boolean wholeWord, boolean regex) {

    public SearchQuery {
        Objects.requireNonNull(term, "term");
    }

    public boolean isEmpty() {
        return term.isEmpty();
    }

    /**
     * Compiles this query's regular expression.
     *
     * <p>Mirrors {@code SearchEngine.getNextMatchPosRegExImpl}: whole-word wraps the
     * pattern in {@code \b…\b}, the flags are {@code MULTILINE} plus case-insensitivity
     * when Match case is off. Reproducing RSTA's construction is what keeps the match
     * count in agreement with the matches Find Next actually visits.</p>
     *
     * @throws PatternSyntaxException if the term is not a valid regular expression
     * @throws IllegalStateException if called on a non-regex query
     */
    public Pattern compile() {
        if (!regex) {
            throw new IllegalStateException("compile() is only meaningful for a regex query");
        }
        int flags = Pattern.MULTILINE;
        if (!matchCase) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(wholeWord ? "\\b" + term + "\\b" : term, flags);
    }

    /**
     * Validates the query without running it, so an unusable pattern can be reported
     * as feedback rather than reaching the engine and failing there.
     *
     * @return a human-readable description of the fault, or null when the query is usable
     */
    public String validationError() {
        if (!regex || term.isEmpty()) {
            return null;
        }
        try {
            compile();
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }
}
