package com.kalix.ide.linter.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses INI model files into structured sections for validation.
 *
 * <p>Comment and header recognition come from {@link IniSyntax}, the IDE's single
 * copy of the engine's line grammar: every line is split into code and
 * {@code #} comment first, so a comment can never masquerade as a header, a
 * property, or a list item, and a header may carry a trailing comment.</p>
 *
 * <p>This parser runs on every lint pass and again for editor context queries,
 * so the per-line work is plain string scanning, no regular expressions: a
 * line is a header if its code starts with {@code [}, a property if it has an
 * {@code =} after a non-empty key, and a list item otherwise.</p>
 */
public class INIModelParser {

    /** Rule name for a line that begins with {@code ;} — not a comment in Kalix. */
    public static final String RULE_SEMICOLON_COMMENT = "semicolon_comment";
    /** Rule name for a line that begins with {@code [} but is not a complete header. */
    public static final String RULE_MALFORMED_SECTION_HEADER = "malformed_section_header";

    /**
     * A lexical problem found while parsing: something the engine would reject (or
     * silently misread) before any schema rule applies. Reported by
     * {@code ModelLinter} under {@code ruleName}, so the schema can set its severity.
     */
    public record SyntaxIssue(int lineNumber, String message, String ruleName) {
    }

    /**
     * One bare entry of a list section ({@code [data]} file paths, {@code [outputs]}
     * references) with the line it was written on. Unlike the text-keyed line
     * maps, the entry lists keep every occurrence, so a duplicated entry can be
     * reported (or renamed) on each of its lines.
     */
    public record ListEntry(String text, int lineNumber) {
    }

    public static class ParsedModel {
        private final List<SyntaxIssue> syntaxIssues = new ArrayList<>();
        private final Map<String, Section> sections = new LinkedHashMap<>();
        private final List<String> inputFiles = new ArrayList<>();
        private final List<ListEntry> inputFileEntries = new ArrayList<>();
        private final List<String> outputReferences = new ArrayList<>();
        private final List<ListEntry> outputReferenceEntries = new ArrayList<>();
        private final Map<String, Integer> outputReferenceLineNumbers = new LinkedHashMap<>(); // Track line numbers for output refs
        private final Map<String, Integer> inputFileLineNumbers = new LinkedHashMap<>(); // Track line numbers for input files
        private final Map<String, String> inputFileAliases = new LinkedHashMap<>();
        private final Map<String, Integer> inputFileAliasLineNumbers = new LinkedHashMap<>();  // Track line numbers for aliases
        private final Map<String, NodeSection> nodes = new LinkedHashMap<>();
        private final List<NodeSection> allNodeSections = new ArrayList<>(); // Track all nodes including duplicates

        public List<SyntaxIssue> getSyntaxIssues() { return syntaxIssues; }
        public Map<String, Section> getSections() { return sections; }
        public List<String> getInputFiles() { return inputFiles; }
        /** Every {@code [data]} file path with its line, in document order, duplicates included. */
        public List<ListEntry> getInputFileEntries() { return inputFileEntries; }
        public List<String> getOutputReferences() { return outputReferences; }
        /** Every {@code [outputs]} reference with its line, in document order, duplicates included. */
        public List<ListEntry> getOutputReferenceEntries() { return outputReferenceEntries; }
        /** Line of the LAST occurrence of each output reference; see {@link #getOutputReferenceEntries()}. */
        public Map<String, Integer> getOutputReferenceLineNumbers() { return outputReferenceLineNumbers; }
        /** Line of the LAST occurrence of each input file path; see {@link #getInputFileEntries()}. */
        public Map<String, Integer> getInputFileLineNumbers() { return inputFileLineNumbers; }
        public Map<String, String> getInputFileAliases() { return inputFileAliases; }
        public Map<String, Integer> getInputFileAliasLineNumbers() { return inputFileAliasLineNumbers; }
        public Map<String, NodeSection> getNodes() { return nodes; }
        public List<NodeSection> getAllNodeSections() { return allNodeSections; }
    }

    public static class Section {
        private final String name;
        private final int startLine;
        private int endLine;
        private final Map<String, Property> properties = new LinkedHashMap<>();
        private final List<Property> allProperties = new ArrayList<>();

        public Section(String name, int startLine) {
            this.name = name;
            this.startLine = startLine;
            this.endLine = startLine; // Will be updated as we parse
        }

        public String getName() { return name; }
        ///  startLine is the line number of the start of the section
        public int getStartLine() { return startLine; }
        ///  endLine is the line number of the end of the section
        public int getEndLine() { return endLine; }
        /** Properties by key; a repeated key resolves to its LAST occurrence. */
        public Map<String, Property> getProperties() { return properties; }
        /** Every property in document order, repeated keys included. */
        public List<Property> getAllProperties() { return allProperties; }

        public void updateEndLine(int lineNumber) {
            this.endLine = lineNumber;
        }

        public void addProperty(String key, String value, int lineNumber) {
            Property property = new Property(key, value, lineNumber);
            properties.put(key, property);
            allProperties.add(property);
        }
    }

    public static class NodeSection extends Section {
        private final String nodeName;
        private String nodeType;

        public NodeSection(String sectionName, String nodeName, int startLine) {
            super(sectionName, startLine);
            this.nodeName = nodeName;
        }

        public String getNodeName() { return nodeName; }
        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    }

    public static class Property {
        private final String key;
        private final String value;
        private final int lineNumber;

        public Property(String key, String value, int lineNumber) {
            this.key = key;
            this.value = value;
            this.lineNumber = lineNumber;
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
        public int getLineNumber() { return lineNumber; }
    }

    /**
     * Parse INI content into structured model.
     */
    public static ParsedModel parse(String content) {
        ParsedModel model = new ParsedModel();
        String[] lines = IniSyntax.splitLines(content);
        Section currentSection = null;

        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            // Code only: the inline '#' comment (if any) is gone from here on.
            String line = IniSyntax.stripComment(lines[i]).trim();

            // Skip empty lines and comment-only lines
            if (line.isEmpty()) {
                continue;
            }
            char first = line.charAt(0);

            // Check for section header. A malformed one ('[node.a', or text after
            // ']') is an engine error, and it still closes the previous section.
            if (first == '[') {
                String sectionName = IniSyntax.sectionName(line);
                if (sectionName == null) {
                    model.getSyntaxIssues().add(new SyntaxIssue(lineNumber,
                        "Malformed section header: expected '[name]' with only a '#' comment after it",
                        RULE_MALFORMED_SECTION_HEADER));
                    currentSection = null;
                    continue;
                }
                currentSection = createSection(sectionName, lineNumber, model);
                continue;
            }

            // ';' is not a comment marker in Kalix (it terminates statements in
            // expression blocks). A line starting with one is a mistake the engine
            // would swallow as a list item; flag it and read no meaning into it.
            if (first == ';') {
                model.getSyntaxIssues().add(new SyntaxIssue(lineNumber,
                    "';' does not start a comment in Kalix models; use '#'",
                    RULE_SEMICOLON_COMMENT));
                continue;
            }

            // Lines before the first section header mean nothing to the linter
            // (the engine rejects them; the missing header is the thing to fix).
            if (currentSection == null) {
                continue;
            }

            if ("data".equals(currentSection.getName())) {
                i = parseDataEntry(lines, i, line, lineNumber, currentSection, model);
                continue;
            }

            // Key-value pair: a non-empty key before the first '='. (The line is
            // trimmed, so any '=' past position 0 leaves a non-empty key.)
            int equals = line.indexOf('=');
            if (equals > 0) {
                String key = line.substring(0, equals).trim();
                LineContinuationResult continuation = collectContinuationLines(lines, i, line.substring(equals + 1));
                String value = continuation.combinedValue;
                i = continuation.lastLineIndex; // Skip processed continuation lines

                currentSection.addProperty(key, value, lineNumber);
                currentSection.updateEndLine(i + 1);

                // Special handling for node type
                if (currentSection instanceof NodeSection && "type".equals(key)) {
                    ((NodeSection) currentSection).setNodeType(value);
                }
                continue;
            }

            // A bare line: an output reference in [outputs], nothing elsewhere
            if ("outputs".equals(currentSection.getName())) {
                LineContinuationResult continuation = collectContinuationLines(lines, i, line);
                i = continuation.lastLineIndex;
                model.getOutputReferences().add(continuation.combinedValue);
                model.getOutputReferenceEntries().add(new ListEntry(continuation.combinedValue, lineNumber));
                model.getOutputReferenceLineNumbers().put(continuation.combinedValue, lineNumber);
                currentSection.updateEndLine(i + 1);
            }
        }

        return model;
    }

    /**
     * One {@code [data]} entry, which takes one of two forms:
     * <ol>
     *   <li>{@code alias = ./path/to/file.csv} — recorded as an alias, an input file,
     *       and a section property (so a repeated alias is a duplicate property
     *       like any other, per {@code DuplicatePropertyValidator});</li>
     *   <li>{@code ./path/to/file.csv} — a direct file path.</li>
     * </ol>
     * Either form may continue onto indented lines; the {@code =} is looked for in
     * the joined text, matching how the engine reads it.
     *
     * @return the index of the last line consumed (the entry's last continuation line)
     */
    private static int parseDataEntry(String[] lines, int index, String line, int lineNumber,
                                      Section section, ParsedModel model) {
        LineContinuationResult continuation = collectContinuationLines(lines, index, line);
        String fullLine = continuation.combinedValue;

        int equals = fullLine.indexOf('=');
        if (equals > 0) {
            String alias = fullLine.substring(0, equals).trim();
            String filePath = fullLine.substring(equals + 1).trim();

            section.addProperty(alias, filePath, lineNumber);
            model.getInputFileAliases().put(alias, filePath);
            model.getInputFileAliasLineNumbers().put(alias, lineNumber);
            model.getInputFiles().add(filePath);
            model.getInputFileEntries().add(new ListEntry(filePath, lineNumber));
            model.getInputFileLineNumbers().put(filePath, lineNumber);
        } else {
            // Direct file path (no alias)
            model.getInputFiles().add(fullLine);
            model.getInputFileEntries().add(new ListEntry(fullLine, lineNumber));
            model.getInputFileLineNumbers().put(fullLine, lineNumber);
        }

        section.updateEndLine(continuation.lastLineIndex + 1);
        return continuation.lastLineIndex;
    }

    /**
     * Helper class to return both the combined value and the last processed line index.
     */
    private static class LineContinuationResult {
        final String combinedValue;
        final int lastLineIndex;

        LineContinuationResult(String combinedValue, int lastLineIndex) {
            this.combinedValue = combinedValue;
            this.lastLineIndex = lastLineIndex;
        }
    }

    /**
     * Collect continuation lines and concatenate them to the initial value.
     * A continuation line is non-empty and starts with whitespace (the rule
     * {@link IniContinuation} documents). Comments are stripped from every
     * line, including the initial value, per {@link IniSyntax}.
     */
    private static LineContinuationResult collectContinuationLines(String[] lines, int startIndex, String initialValue) {
        StringBuilder combinedValue = new StringBuilder(IniSyntax.stripComment(initialValue).trim());
        int currentIndex = startIndex;

        // Look ahead for continuation lines
        for (int i = startIndex + 1; i < lines.length; i++) {
            String nextLine = lines[i];

            // Check if this is a continuation line: non-empty and starts with whitespace
            if (!nextLine.isEmpty() && Character.isWhitespace(nextLine.charAt(0))) {
                // This is a continuation line - remove comments and append it (trimmed)
                String continuationContent = IniSyntax.stripComment(nextLine).trim();
                if (!continuationContent.isEmpty()) {
                    // Add a space before appending to maintain separation
                    int length = combinedValue.length();
                    if (length > 0 && combinedValue.charAt(length - 1) != ' ') {
                        combinedValue.append(' ');
                    }
                    combinedValue.append(continuationContent);
                }
                currentIndex = i; // Update the last processed line
            } else {
                // Not a continuation line - stop processing
                break;
            }
        }

        return new LineContinuationResult(combinedValue.toString(), currentIndex);
    }

    private static final String NODE_SECTION_PREFIX = "node.";

    private static Section createSection(String sectionName, int lineNumber, ParsedModel model) {
        Section section;

        // Check if this is a node section: "node.<name>" with a non-empty name
        if (sectionName.startsWith(NODE_SECTION_PREFIX) && sectionName.length() > NODE_SECTION_PREFIX.length()) {
            String nodeName = sectionName.substring(NODE_SECTION_PREFIX.length());
            section = new NodeSection(sectionName, nodeName, lineNumber);

            // Add to all nodes list (preserves duplicates)
            model.getAllNodeSections().add((NodeSection) section);

            // Also add to nodes map (latest overwrites, but we keep the list for duplicate detection)
            model.getNodes().put(nodeName, (NodeSection) section);
        } else {
            section = new Section(sectionName, lineNumber);
        }

        model.getSections().put(sectionName, section);
        return section;
    }

    /**
     * Get all node names referenced in downstream parameters (ds_1, ds_2, ...;
     * per the shared rule, ds_1_outlet / ds_1_order values are not node names).
     */
    public static Set<String> getDownstreamReferences(ParsedModel model) {
        Set<String> references = new HashSet<>();

        for (NodeSection node : model.getNodes().values()) {
            for (Property prop : node.getProperties().values()) {
                if (com.kalix.ide.linter.utils.ValidationUtils.isDsNodeParam(prop.getKey())) {
                    references.add(prop.getValue());
                }
            }
        }

        return references;
    }

    /**
     * Find duplicate properties within a section.
     * Returns a map of property key to list of line numbers where it appears (only keys with 2+ occurrences).
     */
    public static Map<String, List<Integer>> findDuplicateProperties(Section section) {
        Map<String, List<Integer>> propertyLines = new HashMap<>();

        for (Property prop : section.getAllProperties()) {
            propertyLines.computeIfAbsent(prop.getKey(), k -> new ArrayList<>()).add(prop.getLineNumber());
        }

        Map<String, List<Integer>> duplicates = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : propertyLines.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }

        return duplicates;
    }

    /**
     * Check for duplicate node names.
     */
    public static Map<String, List<Integer>> findDuplicateNodes(ParsedModel model) {
        Map<String, List<Integer>> duplicates = new HashMap<>();
        Map<String, List<Integer>> nodeLines = new HashMap<>();

        // Collect all node sections including duplicates
        for (NodeSection node : model.getAllNodeSections()) {
            String nodeName = node.getNodeName();
            nodeLines.computeIfAbsent(nodeName, k -> new ArrayList<>()).add(node.getStartLine());
        }

        // Find nodes that appear more than once
        for (Map.Entry<String, List<Integer>> entry : nodeLines.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.put(entry.getKey(), entry.getValue());
            }
        }

        return duplicates;
    }
}
