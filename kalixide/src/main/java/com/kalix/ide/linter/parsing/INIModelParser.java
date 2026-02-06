package com.kalix.ide.linter.parsing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses INI model files into structured sections for validation.
 */
public class INIModelParser {

    private static final Logger logger = LoggerFactory.getLogger(INIModelParser.class);

    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*)\\s*$");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*[;#].*$");
    private static final Pattern NODE_SECTION_PATTERN = Pattern.compile("^node\\.(.+)$");

    public static class ParsedModel {
        private final Map<String, Section> sections = new LinkedHashMap<>();
        private final List<String> inputFiles = new ArrayList<>();
        private final List<String> outputReferences = new ArrayList<>();
        private final Map<String, Integer> outputReferenceLineNumbers = new LinkedHashMap<>(); // Track line numbers for output refs
        private final Map<String, Integer> inputFileLineNumbers = new LinkedHashMap<>(); // Track line numbers for input files
        private final Map<String, NodeSection> nodes = new LinkedHashMap<>();
        private final List<NodeSection> allNodeSections = new ArrayList<>(); // Track all nodes including duplicates

        public Map<String, Section> getSections() { return sections; }
        public List<String> getInputFiles() { return inputFiles; }
        public List<String> getOutputReferences() { return outputReferences; }
        public Map<String, Integer> getOutputReferenceLineNumbers() { return outputReferenceLineNumbers; }
        public Map<String, Integer> getInputFileLineNumbers() { return inputFileLineNumbers; }
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
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public Map<String, Property> getProperties() { return properties; }
        public List<Property> getAllProperties() { return allProperties; }

        public void updateEndLine(int lineNumber) {
            this.endLine = lineNumber;
        }

        public void addProperty(String key, String value, int lineNumber) {
            properties.put(key, new Property(key, value, lineNumber));
            allProperties.add(new Property(key, value, lineNumber));
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

        try {
            String[] lines = content.split("\n");
            int lineNumber = 0;
            Section currentSection = null;

            for (int i = 0; i < lines.length; i++) {
                lineNumber = i + 1;
                String line = lines[i].trim();

                // Skip empty lines and comments
                if (line.isEmpty() || COMMENT_PATTERN.matcher(line).matches()) {
                    continue;
                }

                // Check for section header
                Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
                if (sectionMatcher.matches()) {
                    String sectionName = sectionMatcher.group(1).trim();
                    currentSection = createSection(sectionName, lineNumber, model);
                    continue;
                }

                // Check for key-value pair
                Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(line);
                if (kvMatcher.matches() && currentSection != null) {
                    String key = kvMatcher.group(1).trim();
                    String value = kvMatcher.group(2).trim();

                    // Handle line continuation - collect continuation lines (comments will be removed in collectContinuationLines)
                    LineContinuationResult continuationResult = collectContinuationLines(lines, i, value);
                    value = continuationResult.combinedValue;
                    i = continuationResult.lastLineIndex; // Skip processed continuation lines
                    int endLineNumber = i + 1;

                    currentSection.addProperty(key, value, lineNumber);
                    currentSection.updateEndLine(endLineNumber);

                    // Special handling for node type
                    if (currentSection instanceof NodeSection && "type".equals(key)) {
                        ((NodeSection) currentSection).setNodeType(value);
                    }
                    continue;
                }

                // Handle special sections without key-value pairs
                if (currentSection != null) {
                    if ("inputs".equals(currentSection.getName())) {
                        // Input files are listed directly - handle continuation here too (comments will be removed in collectContinuationLines)
                        LineContinuationResult continuationResult = collectContinuationLines(lines, i, line);
                        String fullLine = continuationResult.combinedValue;
                        i = continuationResult.lastLineIndex;

                        model.getInputFiles().add(fullLine);
                        model.getInputFileLineNumbers().put(fullLine, lineNumber);
                        currentSection.updateEndLine(i + 1);
                    } else if ("outputs".equals(currentSection.getName())) {
                        // Output references are listed directly - handle continuation here too (comments will be removed in collectContinuationLines)
                        LineContinuationResult continuationResult = collectContinuationLines(lines, i, line);
                        String fullLine = continuationResult.combinedValue;
                        i = continuationResult.lastLineIndex;

                        model.getOutputReferences().add(fullLine);
                        model.getOutputReferenceLineNumbers().put(fullLine, lineNumber);
                        currentSection.updateEndLine(i + 1);
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error parsing INI content", e);
        }

        return model;
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
     * Remove comments from a line. Comments start with ';' or '#'.
     */
    private static String removeComments(String line) {
        int commentIndex = -1;

        // Find the first occurrence of comment characters
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ';' || c == '#') {
                commentIndex = i;
                break;
            }
        }

        if (commentIndex >= 0) {
            return line.substring(0, commentIndex).trim();
        }

        return line;
    }

    /**
     * Collect continuation lines and concatenate them to the initial value.
     * A continuation line is non-empty and starts with whitespace.
     */
    private static LineContinuationResult collectContinuationLines(String[] lines, int startIndex, String initialValue) {
        // Remove comments from initial value first
        StringBuilder combinedValue = new StringBuilder(removeComments(initialValue));
        int currentIndex = startIndex;

        // Look ahead for continuation lines
        for (int i = startIndex + 1; i < lines.length; i++) {
            String nextLine = lines[i];

            // Check if this is a continuation line: non-empty and starts with whitespace
            if (!nextLine.isEmpty() && Character.isWhitespace(nextLine.charAt(0))) {
                // This is a continuation line - remove comments and append it (trimmed)
                String continuationContent = removeComments(nextLine).trim();
                if (!continuationContent.isEmpty()) {
                    // Add a space before appending to maintain separation
                    if (!combinedValue.isEmpty() && !combinedValue.toString().endsWith(" ")) {
                        combinedValue.append(" ");
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

    private static Section createSection(String sectionName, int lineNumber, ParsedModel model) {
        Section section;

        // Check if this is a node section
        Matcher nodeMatcher = NODE_SECTION_PATTERN.matcher(sectionName);
        if (nodeMatcher.matches()) {
            String nodeName = nodeMatcher.group(1);
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
     * Get all node names referenced in downstream parameters.
     */
    public static Set<String> getDownstreamReferences(ParsedModel model) {
        Set<String> references = new HashSet<>();

        for (NodeSection node : model.getNodes().values()) {
            for (Property prop : node.getProperties().values()) {
                if (prop.getKey().startsWith("ds_")) {
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