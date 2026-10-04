package com.kalix.ide.managers;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The parsed text of the Timeseries tree filter (#397).
 *
 * <p>Spaces (or any whitespace) separate terms, except inside {@code "..."} or
 * {@code /.../}. Inside a regex a backslash stops a {@code /} from closing it; elsewhere a
 * backslash is an ordinary character. A closing quote or slash must be followed by a
 * space or the end. A leading
 * {@code !} makes a term exclude; a trailing lone {@code !} or a {@code ""} is ignored as
 * half-typed, and a {@code !} followed by a space is an error.
 * A series shows if all include terms match it (or there are none) and no exclude term
 * does. Each term is tested against the series name and, separately, the source label,
 * ignoring case:</p>
 * <ul>
 *   <li><b>plain</b> text, bare or quoted, is a substring match; quoting never makes a
 *       regex, so {@code "/x"} looks for {@code /x};</li>
 *   <li>a <b>wildcard</b> term, bare or quoted ({@code *} any run, dots included;
 *       {@code ?} one character within a part), is part-aligned: it must start and end on a dot
 *       boundary, so {@code inflow_*.ds_1} matches {@code node.inflow_3.ds_1} but not
 *       {@code node.inflow_3.ds_10};</li>
 *   <li>a <b>regex</b> is written {@code /.../}, may contain spaces, and uses {@code \/}
 *       for a literal slash.</li>
 * </ul>
 */
public final class SeriesFilter {

    /** No filter: everything shows. */
    public static final SeriesFilter NONE = new SeriesFilter(List.of(), List.of());

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private final List<Pattern> include;
    private final List<Pattern> exclude;

    private SeriesFilter(List<Pattern> include, List<Pattern> exclude) {
        this.include = include;
        this.exclude = exclude;
    }

    /** A filter text the user must fix; the message is shown to them as is. */
    public static final class SyntaxException extends Exception {
        SyntaxException(String message) {
            super(message);
        }
    }

    private enum Kind { TEXT, REGEX }

    /** One term split off the filter text: {@code rest} is what follows it. */
    private record Term(boolean exclude, Kind kind, String body, String rest) {}

    /**
     * Splits the first term off {@code text}, which is non-empty and starts with the term.
     * The body has its quotes or slashes stripped; a lone {@code !} gives an empty body.
     */
    private static Term nextTerm(String text) throws SyntaxException {
        boolean exclude = text.charAt(0) == '!';
        int i = exclude ? 1 : 0;
        if (i == text.length()) {
            return new Term(exclude, Kind.TEXT, "", "");
        }
        // A trailing lone ! is half-typed and skipped; one followed by a space would
        // silently include what the user meant to exclude.
        if (exclude && Character.isWhitespace(text.charAt(i))) {
            throw new SyntaxException("Put the term straight after !");
        }
        char open = text.charAt(i);
        if (open == '"' || open == '/') {
            boolean quoted = open == '"';
            int close = quoted ? text.indexOf('"', i + 1) : regexEnd(text, i + 1);
            if (close < 0) {
                throw new SyntaxException(quoted
                    ? "Quote not closed: end it with \""
                    : "Regex not closed: end it with /");
            }
            String rest = text.substring(close + 1);
            if (!rest.isEmpty() && !Character.isWhitespace(rest.charAt(0))) {
                throw new SyntaxException(quoted
                    ? "Put a space after the closing quote"
                    : "Put a space after the closing / of a regex, or write \\/ for a slash inside it");
            }
            return new Term(exclude, quoted ? Kind.TEXT : Kind.REGEX, text.substring(i + 1, close), rest);
        }
        int end = i;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return new Term(exclude, Kind.TEXT, text.substring(i, end), text.substring(end));
    }

    /** Compiles a term from {@link #nextTerm}. */
    private static Pattern parseTerm(Term term) throws SyntaxException {
        return term.kind() == Kind.REGEX
            ? regex(term.body())
            : plainOrWildcard(term.body());
    }

    /** Parses the whole filter text; see the class comment for the syntax. */
    public static SeriesFilter parse(String text) throws SyntaxException {
        List<Pattern> include = new ArrayList<>();
        List<Pattern> exclude = new ArrayList<>();
        String rest = text.strip();
        while (!rest.isEmpty()) {
            Term term = nextTerm(rest);
            // An empty text term (a lone "!", or "") is half-typed: skip it rather than match everything.
            if (term.kind() == Kind.REGEX || !term.body().isEmpty()) {
                (term.exclude() ? exclude : include).add(parseTerm(term));
            }
            rest = term.rest().stripLeading();
        }
        return include.isEmpty() && exclude.isEmpty() ? NONE : new SeriesFilter(include, exclude);
    }

    public boolean isActive() {
        return !include.isEmpty() || !exclude.isEmpty();
    }

    /** Checks if a series matches the filter. */
    public boolean matches(String seriesName, String sourceLabel) {
        for (Pattern p : exclude) {
            if (hits(p, seriesName, sourceLabel)) return false;
        }
        for (Pattern p : include) {
            if (!hits(p, seriesName, sourceLabel)) return false;
        }
        return true;
    }

    /** Checks if the given pattern matches either the series name or the source label. */
    private static boolean hits(Pattern p, String seriesName, String sourceLabel) {
        return p.matcher(seriesName).find()
            || (sourceLabel != null && p.matcher(sourceLabel).find());
    }

    /**
     * Index of the slash that closes a regex opened before {@code from}, or -1. A
     * backslash skips the character after it, so {@code \/} stays inside the regex.
     */
    private static int regexEnd(String text, int from) {
        for (int j = from; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '/') {
                return j;
            }
        }
        return -1;
    }

    /** Compiles a regex term's {@code body}, the text between its slashes. */
    private static Pattern regex(String body) throws SyntaxException {
        try {
            return Pattern.compile(body, FLAGS);
        } catch (PatternSyntaxException e) {
            throw new SyntaxException("Invalid regex: " + e.getDescription());
        }
    }

    /** Compiles a plain (substring) or wildcard (part-aligned) term. */
    private static Pattern plainOrWildcard(String term) {
        if (term.indexOf('*') < 0 && term.indexOf('?') < 0) {
            return Pattern.compile(Pattern.quote(term), FLAGS);
        }
        // Lookarounds pin both ends to a dot or the string's edge.
        StringBuilder sb = new StringBuilder("(?<![^.])");
        StringBuilder literal = new StringBuilder();
        for (char c : term.toCharArray()) {
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    sb.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                sb.append(c == '*' ? ".*" : "[^.]");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) sb.append(Pattern.quote(literal.toString()));
        sb.append("(?![^.])");
        return Pattern.compile(sb.toString(), FLAGS);
    }
}
