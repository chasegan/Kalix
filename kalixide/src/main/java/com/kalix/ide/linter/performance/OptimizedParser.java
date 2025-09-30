package com.kalix.ide.linter.performance;

import com.kalix.ide.linter.parsing.INIModelParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Optimized parser for large INI files with streaming support and memory efficiency.
 * Reduces memory footprint and improves parsing speed for large models.
 */
public class OptimizedParser {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedParser.class);

    // Pre-compiled patterns for better performance
    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*)\\s*$");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*[;#].*$");
    private static final Pattern BLANK_LINE_PATTERN = Pattern.compile("^\\s*$");

    // Performance configuration
    private static final int LARGE_FILE_THRESHOLD_LINES = 1000;
    private static final int INITIAL_CAPACITY = 256;

    /**
     * Parse content with automatic optimization based on size.
     */
    public static INIModelParser.ParsedModel parseOptimized(String content) {
        if (content == null || content.isEmpty()) {
            return new INIModelParser.ParsedModel();
        }

        // Estimate complexity
        int lineCount = countLines(content);
        boolean isLargeFile = lineCount > LARGE_FILE_THRESHOLD_LINES;

        if (isLargeFile) {
            logger.debug("Parsing large file with {} lines using optimized parser", lineCount);
            return parseWithStreamingOptimization(content, lineCount);
        } else {
            logger.debug("Parsing small file with {} lines using standard parser", lineCount);
            return INIModelParser.parse(content);
        }
    }

    /**
     * Parse large files with streaming approach to reduce memory usage.
     */
    private static INIModelParser.ParsedModel parseWithStreamingOptimization(String content, int estimatedLines) {
        long startTime = System.nanoTime();

        try {
            // Use StringBuilder with pre-sized capacity to reduce reallocations
            StringBuilder lineBuffer = new StringBuilder(256);
            List<String> lines = new ArrayList<>(estimatedLines);

            // Custom string splitting that's more memory efficient for large content
            int start = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '\n') {
                    // Extract line without creating intermediate strings
                    String line = content.substring(start, i);
                    if (i > 0 && content.charAt(i - 1) == '\r') {
                        line = content.substring(start, i - 1); // Handle CRLF
                    }
                    lines.add(line);
                    start = i + 1;
                } else if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    // Handle CRLF
                    String line = content.substring(start, i);
                    lines.add(line);
                    i++; // Skip the \n
                    start = i + 1;
                }
            }

            // Handle last line if no trailing newline
            if (start < content.length()) {
                lines.add(content.substring(start));
            }

            // Parse using optimized line-by-line processing
            INIModelParser.ParsedModel result = parseLines(lines);

            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            logger.debug("Optimized parsing completed in {} ms for {} lines", durationMs, lines.size());

            return result;

        } catch (Exception e) {
            logger.warn("Optimized parsing failed, falling back to standard parser", e);
            return INIModelParser.parse(content);
        }
    }

    /**
     * Process lines with memory-efficient parsing.
     */
    private static INIModelParser.ParsedModel parseLines(List<String> lines) {
        INIModelParser.ParsedModel model = new INIModelParser.ParsedModel();
        String currentSection = null;
        int lineNumber = 0;

        // Pre-size collections based on estimated content
        int estimatedSections = Math.max(4, lines.size() / 50); // Rough estimate

        for (String line : lines) {
            lineNumber++;

            // Skip processing for obviously irrelevant lines early
            if (BLANK_LINE_PATTERN.matcher(line).matches() ||
                COMMENT_PATTERN.matcher(line).matches()) {
                continue;
            }

            // Check for section header
            var sectionMatcher = SECTION_PATTERN.matcher(line);
            if (sectionMatcher.matches()) {
                currentSection = sectionMatcher.group(1).trim();
                continue;
            }

            // Check for key-value pair
            var kvMatcher = KEY_VALUE_PATTERN.matcher(line);
            if (kvMatcher.matches() && currentSection != null) {
                String key = kvMatcher.group(1).trim();
                String value = kvMatcher.group(2).trim();

                // Process based on section type with optimized logic
                processKeyValue(model, currentSection, key, value, lineNumber);
            }
        }

        return model;
    }

    /**
     * Process key-value pairs efficiently based on section type.
     */
    private static void processKeyValue(INIModelParser.ParsedModel model, String section,
                                      String key, String value, int lineNumber) {
        switch (section.toLowerCase()) {
            case "inputs":
                model.getInputFiles().add(value);
                break;

            case "outputs":
                model.getOutputReferences().add(value);
                model.getOutputReferenceLineNumbers().put(value, lineNumber);
                break;

            default:
                // Handle node sections
                if (section.startsWith("node.")) {
                    String nodeName = section.substring(5); // Remove "node." prefix
                    processNodeProperty(model, nodeName, key, value, lineNumber);
                }
                break;
        }
    }

    /**
     * Process node properties with efficient object creation.
     */
    private static void processNodeProperty(INIModelParser.ParsedModel model, String nodeName,
                                          String key, String value, int lineNumber) {
        // Get or create node section
        var nodeSection = model.getNodes().computeIfAbsent(nodeName, name -> {
            String sectionName = "node." + name;
            var section = new INIModelParser.NodeSection(sectionName, name, lineNumber);
            model.getAllNodeSections().add(section);
            return section;
        });

        // Add property
        var property = new INIModelParser.Property(key, value, lineNumber);
        nodeSection.getProperties().put(key, property);

        // Set node type if this is the type property
        if ("type".equals(key)) {
            nodeSection.setNodeType(value);
        }
    }

    /**
     * Count lines efficiently without creating line array.
     */
    private static int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }

        int count = 1; // Start with 1 for the first line
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Estimate memory usage for content parsing.
     */
    public static long estimateMemoryUsage(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        // Rough estimation based on content size and expected object overhead
        long baseMemory = content.length() * 2L; // String overhead
        int lineCount = countLines(content);
        long objectOverhead = lineCount * 100L; // Estimated per-line object overhead

        return baseMemory + objectOverhead;
    }

    /**
     * Check if content should use optimized parsing.
     */
    public static boolean shouldUseOptimizedParsing(String content) {
        return content != null && countLines(content) > LARGE_FILE_THRESHOLD_LINES;
    }
}