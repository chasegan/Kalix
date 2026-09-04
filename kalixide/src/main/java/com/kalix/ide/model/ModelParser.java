package com.kalix.ide.model;

import com.kalix.ide.linter.parsing.IniSyntax;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses INI format hydrological model definitions into simplified data model.
 * Focused on extracting nodes and links for visualization purposes.
 *
 * <p>Runs on the EDT after every (coalesced) document change, so it is a single
 * pass of plain string scanning per line via {@link IniSyntax}: no regular
 * expressions, no reader objects. A node is complete when its section has a
 * {@code type} and a parseable {@code loc}; {@code ds_N} properties become links.</p>
 */
public class ModelParser {

    /**
     * Helper class to store link information during parsing
     */
    private static class LinkInfo {
        final String downstreamNode;
        final boolean isPrimary;

        LinkInfo(String downstreamNode, boolean isPrimary) {
            this.downstreamNode = downstreamNode;
            this.isPrimary = isPrimary;
        }
    }

    private static final String NODE_SECTION_PREFIX = "node.";

    /**
     * Parse INI model text and extract nodes and links.
     */
    public static ParseResult parse(String iniText) {
        List<ModelNode> nodes = new ArrayList<>();
        List<ModelLink> links = new ArrayList<>();

        String currentNodeName = null;
        String currentNodeType = null;
        Double currentNodeX = null;
        Double currentNodeY = null;
        List<LinkInfo> currentNodeLinks = new ArrayList<>();

        for (String rawLine : IniSyntax.splitLines(iniText)) {
            // Code only: a trailing '#' comment on any line is not part of it.
            String line = IniSyntax.stripComment(rawLine).trim();

            // Skip empty lines and comment-only lines
            if (line.isEmpty()) {
                continue;
            }

            // Any section header closes the current node's scope. This keeps the
            // grammar aligned with NodeSectionLocator: properties after e.g.
            // [outputs] must not leak into the preceding node.
            if (line.charAt(0) == '[') {
                // Save previous node if complete
                if (currentNodeName != null && currentNodeType != null
                        && currentNodeX != null && currentNodeY != null) {
                    nodes.add(new ModelNode(currentNodeName, currentNodeType, currentNodeX, currentNodeY));
                    for (LinkInfo linkInfo : currentNodeLinks) {
                        links.add(new ModelLink(currentNodeName, linkInfo.downstreamNode, linkInfo.isPrimary));
                    }
                }

                // Start new node if this is a node header; otherwise leave node scope
                currentNodeName = nodeName(line);
                currentNodeType = null;
                currentNodeX = null;
                currentNodeY = null;
                currentNodeLinks.clear();
                continue;
            }

            if (currentNodeName == null) {
                continue;
            }

            // Parse node properties: key = value, with a non-empty key and value
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (value.isEmpty()) {
                continue;
            }

            switch (key) {
                case "type" -> currentNodeType = value;
                case "loc" -> {
                    double[] xy = parseCoordinates(value);
                    if (xy != null) {
                        currentNodeX = xy[0];
                        currentNodeY = xy[1];
                    }
                }
                default -> {
                    // Downstream links (ds_1, ds_2, ...): ds_1 is primary, all others alternative
                    String linkNumber = downstreamLinkNumber(key);
                    if (linkNumber != null) {
                        currentNodeLinks.add(new LinkInfo(value, "1".equals(linkNumber)));
                    }
                }
            }
        }

        // Save final node if complete
        if (currentNodeName != null && currentNodeType != null
                && currentNodeX != null && currentNodeY != null) {
            nodes.add(new ModelNode(currentNodeName, currentNodeType, currentNodeX, currentNodeY));
            for (LinkInfo linkInfo : currentNodeLinks) {
                links.add(new ModelLink(currentNodeName, linkInfo.downstreamNode, linkInfo.isPrimary));
            }
        }

        return new ParseResult(nodes, links);
    }

    /**
     * The node name of a {@code [node.<name>]} header line (code only, trimmed),
     * or {@code null} for any other header.
     */
    private static String nodeName(String line) {
        String sectionName = IniSyntax.sectionName(line);
        if (sectionName == null || !sectionName.startsWith(NODE_SECTION_PREFIX)
                || sectionName.length() == NODE_SECTION_PREFIX.length()) {
            return null;
        }
        return sectionName.substring(NODE_SECTION_PREFIX.length());
    }

    /**
     * Parses {@code X, Y}: exactly two comma-separated numeric tokens. Tokens are
     * restricted to the characters of a plain decimal or exponent literal
     * ({@code 0-9 . e E + -}) so that spellings {@link Double#parseDouble} would
     * accept but the engine would not ({@code NaN}, {@code 0x1p3}, {@code 1d})
     * are not read as coordinates. Returns {@code null} when the value is not a
     * coordinate pair.
     */
    private static double[] parseCoordinates(String value) {
        int comma = value.indexOf(',');
        if (comma < 0 || value.indexOf(',', comma + 1) >= 0) {
            return null;
        }
        String first = value.substring(0, comma).trim();
        String second = value.substring(comma + 1).trim();
        if (!isNumericToken(first) || !isNumericToken(second)) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(first), Double.parseDouble(second)};
        } catch (NumberFormatException e) {
            return null; // e.g. "1.2.3": right characters, not a number
        }
    }

    private static boolean isNumericToken(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * The digits of a {@code ds_N} key ({@code "1"} for {@code ds_1}), or
     * {@code null} if the key is not a downstream link ({@code ds_1_outlet} is not).
     */
    private static String downstreamLinkNumber(String key) {
        if (key.length() <= 3 || !key.startsWith("ds_")) {
            return null;
        }
        for (int i = 3; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return key.substring(3);
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
