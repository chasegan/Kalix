package com.kalix.ide.linter.parsing;

/**
 * The line-level lexical grammar of Kalix INI text: what is a comment, and what is
 * a section header. Every IDE parser that reads model text (the linter's
 * {@link INIModelParser}, the map's {@code model/ModelParser}, the
 * {@code model/NodeSectionLocator}, the syntax highlighter, the parameter sheet and
 * the Run Manager's outputs scan) goes through this class, so the IDE has one
 * answer to those questions, and it is the engine's answer.
 *
 * <p>The rule, mirrored from the engine's {@code src/io/custom_ini_parser.rs} and
 * documented on the website's Conventions page ("Comments"):
 * <ul>
 *   <li>{@code #} starts a comment that runs to the end of the line. It may appear
 *       on any line: blank, {@code [section]} header, {@code key = value},
 *       continuation, or bare list item ({@code [data]}, {@code [outputs]}).</li>
 *   <li>A {@code #} inside double quotes is not a comment marker; a backslash
 *       escapes the following character.</li>
 *   <li>{@code ;} is <em>never</em> a comment marker. It terminates statements
 *       inside {@code { ... }} expression blocks, so treating it as a comment would
 *       cut those values in half.</li>
 *   <li>An <em>annotation</em> is a comment line whose first two characters, at
 *       column zero, are {@code #@}, followed by {@code key = value} (ADR-0010 §2).
 *       It is still a comment to every rule above, so parsers that strip comments
 *       see nothing; only {@link #annotation(String)} reads it. Any other prefix
 *       ({@code ##@}, {@code # #@}, an indented {@code #@}) is an ordinary comment,
 *       which is how an annotation is disabled.</li>
 * </ul>
 */
public final class IniSyntax {

    /** The two characters that open an annotation line, at column zero. */
    public static final String ANNOTATION_PREFIX = "#@";

    /**
     * An annotation's key and value (ADR-0010 §2). The key is the trimmed text
     * between {@code #@} and the first {@code =}; the value is the trimmed text
     * after it, kept verbatim — a {@code #} or a second {@code =} inside it is
     * just text.
     */
    public record Annotation(String key, String value) {
    }

    private IniSyntax() {
        // utility class
    }

    /**
     * True if the line opens with {@code #@} at column zero, well-formed or not.
     * A malformed annotation (no {@code =}, or an empty key) is still an
     * annotation attempt for the highlighter and the linter, never a plain comment.
     */
    public static boolean isAnnotationLine(CharSequence line) {
        return line != null && line.length() >= 2 && line.charAt(0) == '#' && line.charAt(1) == '@';
    }

    /**
     * The annotation on a well-formed {@code #@key = value} line, or {@code null}
     * if the line is not an annotation or has no {@code =} or an empty key. The
     * value may be empty.
     */
    public static Annotation annotation(String line) {
        if (!isAnnotationLine(line)) {
            return null;
        }
        int equals = line.indexOf('=', ANNOTATION_PREFIX.length());
        if (equals < 0) {
            return null;
        }
        String key = line.substring(ANNOTATION_PREFIX.length(), equals).strip();
        if (key.isEmpty()) {
            return null;
        }
        return new Annotation(key, line.substring(equals + 1).strip());
    }

    /**
     * Returns the index at which the inline comment starts, or {@code -1} if the
     * line has none. Scans {@code line[from, to)}; a {@code #} inside double quotes
     * is skipped, and a backslash escapes the next character.
     */
    public static int commentStart(CharSequence line, int from, int to) {
        boolean inQuotes = false;
        boolean escapeNext = false;
        for (int i = from; i < to; i++) {
            char c = line.charAt(i);
            if (escapeNext) {
                escapeNext = false;
                continue;
            }
            if (c == '\\') {
                escapeNext = true;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '#' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #commentStart(CharSequence, int, int)} over the whole line. */
    public static int commentStart(String line) {
        return line == null ? -1 : commentStart(line, 0, line.length());
    }

    /**
     * The line with its inline comment removed and trailing whitespace trimmed.
     * Leading whitespace is kept, so continuation lines stay recognisable.
     * Returns {@code ""} for {@code null}.
     */
    public static String stripComment(String line) {
        if (line == null) {
            return "";
        }
        int at = commentStart(line);
        String code = at < 0 ? line : line.substring(0, at);
        return code.stripTrailing();
    }

    /**
     * True if the line carries no code: it is blank, or a comment is all it holds.
     */
    public static boolean isBlankOrComment(String line) {
        return stripComment(line).isBlank();
    }

    /**
     * Splits text into lines on {@code \n}, dropping a trailing {@code \r} from each
     * so CRLF documents parse identically to LF documents. (A "blank" line that
     * survived as {@code "\r"} would count as a continuation line, gluing indented
     * lines after a blank line onto the previous property.) A trailing newline
     * does not produce an extra empty line.
     */
    public static String[] splitLines(String content) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                lines[i] = line.substring(0, line.length() - 1);
            }
        }
        return lines;
    }

    /**
     * True if the line's code (after trimming) starts with {@code [}, i.e. it is a
     * section header, well-formed or not. A malformed header (no closing
     * {@code ]}, or text after it) is still a header attempt, never a property or
     * list item: the engine rejects it, and every scanner should stop treating the
     * previous section as open at that line.
     */
    public static boolean isSectionHeaderLine(String line) {
        String code = stripComment(line).stripLeading();
        return !code.isEmpty() && code.charAt(0) == '[';
    }

    /**
     * The section name of a well-formed header line ({@code [name]} with only
     * whitespace and an optional {@code #} comment around it), or {@code null} if
     * the line is not a well-formed header. The name is returned trimmed and may be
     * empty for {@code []}.
     */
    public static String sectionName(String line) {
        String code = stripComment(line).strip();
        if (code.length() < 2 || code.charAt(0) != '[' || code.charAt(code.length() - 1) != ']') {
            return null;
        }
        return code.substring(1, code.length() - 1).strip();
    }
}
