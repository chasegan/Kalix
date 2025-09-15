package com.kalix.gui.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses INI format hydrological model definitions into simplified data model.
 * Focused on extracting nodes and links for visualization purposes.
 */
public class ModelParser {
    private static final Logger logger = LoggerFactory.getLogger(ModelParser.class);

    private static final Pattern NODE_SECTION_PATTERN = Pattern.compile("^\\[node\\.([^\\]]+)\\]$");
    private static final Pattern TYPE_PATTERN = Pattern.compile("^type\\s*=\\s*(.+)$");
    private static final Pattern LOC_PATTERN = Pattern.compile("^loc\\s*=\\s*([0-9.-]+)\\s*,\\s*([0-9.-]+)$");

    /**
     * Parse INI model text and extract nodes and links.
     */
    public static ParseResult parse(String iniText) {
        List<ModelNode> nodes = new ArrayList<>();
        List<ModelLink> links = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(iniText))) {
            String line;
            String currentNodeName = null;
            String currentNodeType = null;
            Double currentNodeX = null;
            Double currentNodeY = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }

                // Check for node section header
                Matcher nodeMatcher = NODE_SECTION_PATTERN.matcher(line);
                if (nodeMatcher.matches()) {
                    // Save previous node if complete
                    if (currentNodeName != null && currentNodeType != null && 
                        currentNodeX != null && currentNodeY != null) {
                        nodes.add(new ModelNode(currentNodeName, currentNodeType, currentNodeX, currentNodeY));
                    }

                    // Start new node
                    currentNodeName = nodeMatcher.group(1);
                    currentNodeType = null;
                    currentNodeX = null;
                    currentNodeY = null;
                    continue;
                }

                // Parse node properties
                if (currentNodeName != null) {
                    // Parse type
                    Matcher typeMatcher = TYPE_PATTERN.matcher(line);
                    if (typeMatcher.matches()) {
                        currentNodeType = typeMatcher.group(1).trim();
                        continue;
                    }

                    // Parse location
                    Matcher locMatcher = LOC_PATTERN.matcher(line);
                    if (locMatcher.matches()) {
                        try {
                            currentNodeX = Double.parseDouble(locMatcher.group(1));
                            currentNodeY = Double.parseDouble(locMatcher.group(2));
                        } catch (NumberFormatException e) {
                            // Skip invalid coordinates
                        }
                        continue;
                    }
                }
            }

            // Save final node if complete
            if (currentNodeName != null && currentNodeType != null && 
                currentNodeX != null && currentNodeY != null) {
                nodes.add(new ModelNode(currentNodeName, currentNodeType, currentNodeX, currentNodeY));
            }

        } catch (Exception e) {
            // For now, return partial results on parse errors
            logger.error("Error parsing model: {}", e.getMessage());
        }

        return new ParseResult(nodes, links);
    }

    /**
     * Result of parsing operation.
     */
    public static class ParseResult {
        private final List<ModelNode> nodes;
        private final List<ModelLink> links;

        public ParseResult(List<ModelNode> nodes, List<ModelLink> links) {
            this.nodes = nodes;
            this.links = links;
        }

        public List<ModelNode> getNodes() {
            return nodes;
        }

        public List<ModelLink> getLinks() {
            return links;
        }
    }
}