package com.kalix.ide.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single INI-section grammar for locating node sections in model text.
 *
 * <p>Every feature that needs to find a node's section in the raw editor text
 * (map-drag coordinate write-back, cut/copy/paste, delete, scroll-to-node) must go
 * through this class rather than rolling its own regex. The grammar deliberately
 * mirrors {@link ModelParser} — the reference grammar — line by line:
 *
 * <ul>
 *   <li>Lines are trimmed before classification, so indented headers and properties
 *       are recognised exactly as the parser recognises them.</li>
 *   <li>Blank lines and comment lines ({@code #} or {@code ;} first) are inert:
 *       they never open or close a section and never match as properties — a
 *       {@code [} or {@code loc =} inside a comment is just a comment.</li>
 *   <li>A node section opens at a line whose trimmed content is exactly
 *       {@code [node.<name>]} and closes at the next line whose trimmed content
 *       starts with {@code [} (any section header), or at end of text. Trailing
 *       blank/comment lines before the next header belong to the section.</li>
 *   <li>Property matches are line-anchored with inline {@code #}/{@code ;} comments
 *       stripped, so {@code refloc = ...} or {@code # loc = ...} never match as a
 *       {@code loc} property.</li>
 *   <li>Duplicate node names resolve to the LAST section, matching the parser
 *       (later sections overwrite earlier ones in the model map). Duplicate
 *       {@code loc} lines within a section likewise resolve to the last.</li>
 * </ul>
 */
public final class NodeSectionLocator {

    /** Matches a node section header on a trimmed line: {@code [node.<name>]}. */
    private static final Pattern NODE_HEADER_PATTERN = Pattern.compile("^\\[node\\.([^\\]]+)\\]$");

    /**
     * Matches a {@code loc} property on a RAW (untrimmed) line, tolerating leading and
     * trailing whitespace and an inline comment, so that the capture-group offsets are
     * valid offsets into the original text. Equivalent to trimming then applying
     * {@link ModelParser}'s LOC_PATTERN.
     */
    private static final Pattern LOC_LINE_PATTERN = Pattern.compile(
        "^\\s*loc\\s*=\\s*([0-9.eE+-]+)(\\s*,\\s*)([0-9.eE+-]+)(?:\\s*[#;].*)?\\s*$");

    /** Matches a downstream-link property on a trimmed line, mirroring ModelParser. */
    private static final Pattern DS_LINE_PATTERN = Pattern.compile(
        "^ds_(\\d+)\\s*=\\s*(.+?)\\s*(?:[#;].*)?$");

    private NodeSectionLocator() {
    }

    /**
     * The character span of a {@code loc} property's value within the whole text:
     * {@code [start, end)} covers the X coordinate through the Y coordinate, and
     * {@code separator} is the original text between them (comma plus whitespace),
     * preserved so a rewrite keeps the author's formatting.
     */
    public record LocValueSpan(int start, int end, String separator) {
    }

    /**
     * A located node section: {@code [start, end)} character offsets spanning the
     * header line through the last line before the next section header (or end of
     * text), and the section's {@code loc} value span, or {@code null} if the section
     * has no parseable {@code loc} line.
     *
     * <p>{@code end} deliberately swallows the blank and comment lines that trail the
     * section — that is what removal wants, so cutting a section takes its trailing
     * gap with it. {@code contentEnd} is the offset just past the section's last
     * <em>content</em> line (the last line that is neither blank nor a comment), which
     * is what <em>insertion</em> wants: a comment block sitting between two nodes
     * conventionally introduces the node below it, so new text belongs before that
     * block, not after it. The two coincide when nothing trails the section.</p>
     */
    public record NodeSection(String nodeName, int start, int end, int contentEnd, LocValueSpan locValue) {
        public int length() {
            return end - start;
        }
    }

    /**
     * A downstream-link property line ({@code ds_N = <target>}) inside a node section:
     * the owning section's node name, the referenced target node, and the whole-line
     * span {@code [lineStart, lineEnd)} including the trailing newline if present.
     */
    public record DsReference(String sourceNode, String target, int lineStart, int lineEnd) {
    }

    /**
     * Finds a node's section in the text. When several sections share the name, the
     * last one is returned, matching {@link ModelParser}'s last-wins resolution.
     *
     * @param text     full model text
     * @param nodeName exact node name (the {@code <name>} in {@code [node.<name>]})
     * @return the located section, or {@code null} if not found
     */
    public static NodeSection find(String text, String nodeName) {
        if (text == null || nodeName == null) {
            return null;
        }

        // Last wins: scan forward and keep overwriting, rather than returning the
        // first match. Expressed over findAll so there is exactly one grammar.
        NodeSection result = null;
        for (NodeSection section : findAll(text)) {
            if (section.nodeName().equals(nodeName)) {
                result = section;
            }
        }
        return result;
    }

    /**
     * Lists every node section in the text, in document order.
     *
     * <p>Duplicate node names are all returned — unlike {@link #find}, which collapses
     * them to the last. Callers that care about the model's view of a name should
     * resolve last-wins themselves; callers that care about the <em>text</em> (where to
     * insert, what to renumber) usually want every occurrence.</p>
     *
     * @param text full model text
     * @return all node sections, in document order; empty if there are none
     */
    public static List<NodeSection> findAll(String text) {
        List<NodeSection> sections = new ArrayList<>();
        if (text == null) {
            return sections;
        }

        String openName = null;
        int openStart = -1;
        int openContentEnd = -1;
        LocValueSpan openLoc = null;

        LineScanner scanner = new LineScanner(text);
        while (scanner.next()) {
            String trimmed = scanner.trimmed();
            if (isBlankOrComment(trimmed)) {
                // Inert: never opens or closes a section, and never extends its content.
                continue;
            }

            if (trimmed.charAt(0) == '[') {
                // Any section header closes the open section, at this line's start.
                if (openName != null) {
                    sections.add(new NodeSection(openName, openStart, scanner.lineStart, openContentEnd, openLoc));
                }
                Matcher header = NODE_HEADER_PATTERN.matcher(trimmed);
                if (header.matches()) {
                    openName = header.group(1);
                    openStart = scanner.lineStart;
                    openContentEnd = scanner.nextLineStart; // the header is itself content
                    openLoc = null;
                } else {
                    openName = null;
                }
                continue;
            }

            if (openName != null) {
                openContentEnd = scanner.nextLineStart;
                Matcher loc = LOC_LINE_PATTERN.matcher(scanner.raw());
                if (loc.matches()) {
                    // Last loc line wins, matching the parser's overwrite behaviour.
                    openLoc = new LocValueSpan(
                        scanner.lineStart + loc.start(1),
                        scanner.lineStart + loc.end(3),
                        loc.group(2));
                }
            }
        }

        if (openName != null) {
            sections.add(new NodeSection(openName, openStart, text.length(), openContentEnd, openLoc));
        }
        return sections;
    }

    /**
     * Lists every downstream-link property line ({@code ds_N = <target>}) that sits
     * inside a node section, in document order. Lines outside any node section are
     * ignored (they mean nothing to the parser).
     *
     * @param text full model text
     * @return all in-section ds-references, with whole-line spans suitable for deletion
     */
    public static List<DsReference> findDsReferences(String text) {
        List<DsReference> references = new ArrayList<>();
        if (text == null) {
            return references;
        }

        String currentNode = null;

        LineScanner scanner = new LineScanner(text);
        while (scanner.next()) {
            String trimmed = scanner.trimmed();
            if (isBlankOrComment(trimmed)) {
                continue;
            }

            if (trimmed.charAt(0) == '[') {
                Matcher header = NODE_HEADER_PATTERN.matcher(trimmed);
                currentNode = header.matches() ? header.group(1) : null;
                continue;
            }

            if (currentNode != null) {
                Matcher ds = DS_LINE_PATTERN.matcher(trimmed);
                if (ds.matches()) {
                    references.add(new DsReference(
                        currentNode, ds.group(2), scanner.lineStart, scanner.nextLineStart));
                }
            }
        }

        return references;
    }

    private static boolean isBlankOrComment(String trimmed) {
        return trimmed.isEmpty() || trimmed.charAt(0) == '#';
    }

    /**
     * Walks a string line by line, exposing the current line's raw content (without
     * the terminator) and its offsets. Handles LF and CRLF, and a final line with no
     * terminator.
     */
    private static final class LineScanner {
        private final String text;
        int lineStart;
        int nextLineStart;
        private int contentEnd;

        LineScanner(String text) {
            this.text = text;
            this.nextLineStart = 0;
        }

        boolean next() {
            if (nextLineStart >= text.length()) {
                return false;
            }
            lineStart = nextLineStart;
            int newline = text.indexOf('\n', lineStart);
            if (newline == -1) {
                contentEnd = text.length();
                nextLineStart = text.length();
            } else {
                contentEnd = newline;
                nextLineStart = newline + 1;
            }
            if (contentEnd > lineStart && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            return true;
        }

        String raw() {
            return text.substring(lineStart, contentEnd);
        }

        String trimmed() {
            return raw().trim();
        }
    }
}
