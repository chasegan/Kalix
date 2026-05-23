package com.kalix.ide.tableview;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and formats values of the form
 * {@code c1 * data.x + c2 * data.y - c3 * data.z}, i.e. a linear combination
 * of data references with optional numeric coefficients.
 *
 * <p>Grammar:</p>
 * <pre>
 *   linearCombination := term (('+' | '-') term)*
 *   term              := [number '*'] dataRef
 *   number            := digits ['.' digits] [('e'|'E') ['+'|'-'] digits]
 *   dataRef           := 'data.' (identChar | '[' anything ']')+
 * </pre>
 *
 * <p>A leading sign on the first term is permitted. A coefficient defaults to
 * {@code 1.0} when omitted. Whitespace is irrelevant; an inline {@code #} or
 * {@code ;} comment terminates the value. Callers should pre-join multi-line
 * continuations before calling {@link #parse(String)}.</p>
 *
 * <p>The parser is intentionally strict: a pure constant like {@code 4}, or a
 * constant added to a data reference like {@code 4 + data.foo}, does not
 * parse. {@link #canParse(String)} is a cheap way to gate UI affordances.</p>
 */
public final class LinearCombinationParser {

    /** A single signed term in the combination. */
    public static final class Term {
        public final double coefficient;
        public final String dataRef;

        public Term(double coefficient, String dataRef) {
            this.coefficient = coefficient;
            this.dataRef = dataRef;
        }
    }

    private LinearCombinationParser() {}

    /**
     * Parses the given value.
     *
     * @return a non-empty list of terms, or {@code null} if the input is not a
     *         valid linear combination of data references.
     */
    public static List<Term> parse(String value) {
        if (value == null) {
            return null;
        }
        List<Token> tokens = tokenize(value);
        if (tokens == null) {
            return null;
        }
        return parseTerms(tokens);
    }

    /** True iff {@link #parse(String)} would return a non-null result. */
    public static boolean canParse(String value) {
        return parse(value) != null;
    }

    /**
     * Formats the terms as a single line, using {@code +} / {@code -} as
     * English-style separators (e.g. {@code 0.5 * data.x - 0.3 * data.y}).
     */
    public static String formatInline(List<Term> terms) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            appendTerm(sb, terms.get(i), i == 0);
        }
        return sb.toString();
    }

    /**
     * Formats the terms with one term per line; subsequent lines are indented
     * by {@code continuationIndent} spaces so the value reads as a continuation
     * of {@code propertyKey = …} in the editor.
     */
    public static String formatMultiLine(List<Term> terms, int continuationIndent) {
        if (terms == null || terms.isEmpty()) {
            return "";
        }
        String indent = continuationIndent > 0 ? " ".repeat(continuationIndent) : "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i == 0) {
                appendTerm(sb, terms.get(i), true);
            } else {
                // Place the +/- at the end of the previous line so the next line
                // begins with the new term's number, aligned under the previous.
                Term next = terms.get(i);
                sb.append(next.coefficient < 0 ? " -" : " +").append('\n').append(indent);
                appendUnsignedTerm(sb, Math.abs(next.coefficient), next.dataRef);
            }
        }
        return sb.toString();
    }

    private static void appendTerm(StringBuilder sb, Term t, boolean isFirst) {
        if (isFirst) {
            if (t.coefficient < 0) {
                sb.append('-');
                appendUnsignedTerm(sb, -t.coefficient, t.dataRef);
            } else {
                appendUnsignedTerm(sb, t.coefficient, t.dataRef);
            }
        } else {
            sb.append(t.coefficient < 0 ? " - " : " + ");
            appendUnsignedTerm(sb, Math.abs(t.coefficient), t.dataRef);
        }
    }

    private static void appendUnsignedTerm(StringBuilder sb, double absCoef, String dataRef) {
        sb.append(formatCoefficient(absCoef)).append(" * ").append(dataRef);
    }

    /**
     * Formats a coefficient for display: drops a redundant {@code ".0"} on
     * integer-valued numbers (so {@code 2.0} becomes {@code "2"}) and otherwise
     * uses {@link Double#toString(double)} to preserve full precision. Used
     * both internally during output formatting and externally when populating
     * the coefficient column of the table.
     */
    public static String formatCoefficient(double v) {
        if (!Double.isInfinite(v) && v == Math.floor(v)) {
            long asLong = (long) v;
            if ((double) asLong == v) {
                return Long.toString(asLong);
            }
        }
        return Double.toString(v);
    }

    // --- Tokenizer ----------------------------------------------------------

    private enum TokenType { NUMBER, DATA_REF, PLUS, MINUS, STAR }

    private static final class Token {
        final TokenType type;
        final String text;
        final double number;

        Token(TokenType type, String text, double number) {
            this.type = type;
            this.text = text;
            this.number = number;
        }
    }

    /** Tokenises the input. Returns null on a lexical error. */
    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int pos = 0;
        int len = input.length();
        while (pos < len) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '#' || c == ';') {
                break;  // inline comment terminates the value
            } else if (c == '+') {
                tokens.add(new Token(TokenType.PLUS, "+", 0));
                pos++;
            } else if (c == '-') {
                tokens.add(new Token(TokenType.MINUS, "-", 0));
                pos++;
            } else if (c == '*') {
                tokens.add(new Token(TokenType.STAR, "*", 0));
                pos++;
            } else if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < len && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                if (pos < len && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                    pos++;
                    if (pos < len && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                        pos++;
                    }
                    while (pos < len && Character.isDigit(input.charAt(pos))) {
                        pos++;
                    }
                }
                String text = input.substring(start, pos);
                double value;
                try {
                    value = Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    return null;
                }
                tokens.add(new Token(TokenType.NUMBER, text, value));
            } else if (input.startsWith("data.", pos)) {
                int start = pos;
                pos += "data.".length();
                int bracketDepth = 0;
                while (pos < len) {
                    char ch = input.charAt(pos);
                    if (bracketDepth > 0) {
                        if (ch == ']') {
                            bracketDepth--;
                        } else if (ch == '[') {
                            bracketDepth++;
                        }
                        pos++;
                    } else if (ch == '[') {
                        bracketDepth++;
                        pos++;
                    } else if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_') {
                        pos++;
                    } else {
                        break;
                    }
                }
                if (bracketDepth > 0) {
                    return null;  // unterminated bracket
                }
                tokens.add(new Token(TokenType.DATA_REF, input.substring(start, pos), 0));
            } else {
                return null;  // unknown character
            }
        }
        return tokens;
    }

    // --- Parser -------------------------------------------------------------

    private static List<Term> parseTerms(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        List<Term> terms = new ArrayList<>();
        int[] pos = {0};
        int len = tokens.size();

        // First term: an optional leading sign is folded into the coefficient.
        int sign = 1;
        if (tokens.get(pos[0]).type == TokenType.PLUS) {
            pos[0]++;
        } else if (tokens.get(pos[0]).type == TokenType.MINUS) {
            sign = -1;
            pos[0]++;
        }
        Term term = parseSingleTerm(tokens, pos);
        if (term == null) {
            return null;
        }
        terms.add(new Term(sign * term.coefficient, term.dataRef));

        // Subsequent terms must be preceded by '+' or '-'.
        while (pos[0] < len) {
            TokenType sep = tokens.get(pos[0]).type;
            if (sep == TokenType.PLUS) {
                sign = 1;
            } else if (sep == TokenType.MINUS) {
                sign = -1;
            } else {
                return null;
            }
            pos[0]++;
            term = parseSingleTerm(tokens, pos);
            if (term == null) {
                return null;
            }
            terms.add(new Term(sign * term.coefficient, term.dataRef));
        }

        return terms;
    }

    private static Term parseSingleTerm(List<Token> tokens, int[] pos) {
        if (pos[0] >= tokens.size()) {
            return null;
        }
        Token first = tokens.get(pos[0]);
        if (first.type == TokenType.NUMBER) {
            // Expect: NUMBER STAR DATA_REF
            if (pos[0] + 2 >= tokens.size()) {
                return null;
            }
            Token star = tokens.get(pos[0] + 1);
            Token ref = tokens.get(pos[0] + 2);
            if (star.type != TokenType.STAR || ref.type != TokenType.DATA_REF) {
                return null;
            }
            pos[0] += 3;
            return new Term(first.number, ref.text);
        } else if (first.type == TokenType.DATA_REF) {
            pos[0] += 1;
            return new Term(1.0, first.text);
        }
        return null;
    }
}
