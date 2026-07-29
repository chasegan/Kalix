package com.kalix.ide.editor.search;

import java.util.regex.MatchResult;

/**
 * Expands a replacement template — {@code $1}, {@code ${name}}, {@code \} escapes —
 * against one match.
 *
 * <h2>Why this is not {@code Matcher.appendReplacement}</h2>
 * The JDK's expansion is private, reachable only through {@code appendReplacement},
 * which appends <em>the text between the previous match and this one</em> along with the
 * expansion. That intervening text is exactly what we are trying not to materialise: two
 * matches at either end of a 200 MB file would have it copy the whole file into a buffer
 * to hand back one short string. Replacing in place needs the expansion alone, so the
 * expansion is reproduced here.
 *
 * <p>The syntax and the error messages follow {@code Matcher} deliberately, including
 * its greedy group-number rule: {@code $12} means group 12 where that group exists, and
 * group 1 followed by a literal {@code 2} where it does not.</p>
 */
public final class ReplacementTemplate {

    private ReplacementTemplate() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Expands {@code template} against {@code match}.
     *
     * @throws IllegalArgumentException if the template is malformed
     * @throws IndexOutOfBoundsException if it references a group the pattern lacks
     */
    public static String expand(String template, MatchResult match) {
        StringBuilder result = new StringBuilder(template.length());
        int cursor = 0;

        while (cursor < template.length()) {
            char c = template.charAt(cursor);
            switch (c) {
                case '\\' -> {
                    cursor++;
                    if (cursor == template.length()) {
                        throw new IllegalArgumentException("character to be escaped is missing");
                    }
                    result.append(template.charAt(cursor));
                    cursor++;
                }
                case '$' -> cursor = appendGroup(template, cursor + 1, match, result);
                default -> {
                    result.append(c);
                    cursor++;
                }
            }
        }
        return result.toString();
    }

    /**
     * Appends the group referenced at {@code cursor} (just past the {@code $}).
     *
     * @return the cursor position after the reference
     */
    private static int appendGroup(String template, int cursor, MatchResult match, StringBuilder result) {
        if (cursor == template.length()) {
            throw new IllegalArgumentException("Illegal group reference: group index is missing");
        }

        String value;
        if (template.charAt(cursor) == '{') {
            int close = template.indexOf('}', cursor);
            if (close < 0) {
                throw new IllegalArgumentException("named capturing group is missing trailing '}'");
            }
            String name = template.substring(cursor + 1, close);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("named capturing group has 0 length name");
            }
            value = match.group(name);
            cursor = close + 1;
        } else {
            if (!Character.isDigit(template.charAt(cursor))) {
                throw new IllegalArgumentException("Illegal group reference");
            }
            int group = template.charAt(cursor) - '0';
            cursor++;
            // Greedy, but only as far as the pattern actually has groups — the rule
            // Matcher uses, so "$12" against a two-group pattern is group 1 then "2".
            while (cursor < template.length() && Character.isDigit(template.charAt(cursor))) {
                int extended = group * 10 + (template.charAt(cursor) - '0');
                if (extended > match.groupCount()) {
                    break;
                }
                group = extended;
                cursor++;
            }
            if (group > match.groupCount()) {
                throw new IndexOutOfBoundsException("No group " + group);
            }
            value = match.group(group);
        }

        // An unmatched optional group contributes nothing, as in Matcher.
        if (value != null) {
            result.append(value);
        }
        return cursor;
    }
}
