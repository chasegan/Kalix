package com.kalix.ide.interaction;

import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.editor.EnhancedTextEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages clipboard operations for map nodes including cut, copy, and paste.
 * Handles extraction of node sections from text, name suffix generation for copies,
 * coordinate translation, and internal link reference updates.
 */
public class MapClipboardManager {
    private static final Logger logger = LoggerFactory.getLogger(MapClipboardManager.class);

    // Patterns for parsing node sections
    private static final Pattern LOC_PATTERN =
        Pattern.compile("loc\\s*=\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)");
    private static final Pattern DS_PATTERN =
        Pattern.compile("^(ds_\\d+)\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern SECTION_HEADER_PATTERN =
        Pattern.compile("^\\s*\\[([^\\]]+)\\]", Pattern.MULTILINE);

    private final HydrologicalModel model;
    private final EnhancedTextEditor textEditor;
    private final TextCoordinateUpdater textUpdater;

    // Clipboard storage
    private ClipboardEntry clipboard;

    public MapClipboardManager(HydrologicalModel model, EnhancedTextEditor textEditor,
                                TextCoordinateUpdater textUpdater) {
        this.model = model;
        this.textEditor = textEditor;
        this.textUpdater = textUpdater;
    }

    /**
     * Cut selected nodes to clipboard.
     * Extracts node sections and stores them, then deletes the original nodes.
     */
    public void cut() {
        Set<String> selectedNodes = model.getSelectedNodes();
        if (selectedNodes.isEmpty()) {
            return;
        }

        // Extract node sections
        List<ClipboardEntry.NodeSectionData> sections = extractNodeSections(selectedNodes);
        if (sections.isEmpty()) {
            return;
        }

        // Store in clipboard with anchor coordinates
        ClipboardEntry.NodeSectionData anchor = sections.get(0);
        clipboard = new ClipboardEntry(sections, true, anchor.x(), anchor.y());

        // Delete the original nodes
        textUpdater.deleteSelectedElements(selectedNodes, Collections.emptySet());
        model.deleteSelectedNodes();

        logger.info("Cut {} nodes to clipboard", sections.size());
    }

    /**
     * Copy selected nodes to clipboard.
     * Extracts node sections and stores them without deleting originals.
     */
    public void copy() {
        Set<String> selectedNodes = model.getSelectedNodes();
        if (selectedNodes.isEmpty()) {
            return;
        }

        // Extract node sections
        List<ClipboardEntry.NodeSectionData> sections = extractNodeSections(selectedNodes);
        if (sections.isEmpty()) {
            return;
        }

        // Store in clipboard with anchor coordinates
        ClipboardEntry.NodeSectionData anchor = sections.get(0);
        clipboard = new ClipboardEntry(sections, false, anchor.x(), anchor.y());

        logger.info("Copied {} nodes to clipboard", sections.size());
    }

    /**
     * Paste clipboard content at the specified map location.
     * @param pasteLocationX World X coordinate for paste
     * @param pasteLocationY World Y coordinate for paste
     */
    public void pasteAtMapLocation(double pasteLocationX, double pasteLocationY) {
        if (clipboard == null || clipboard.nodeSections().isEmpty()) {
            return;
        }

        List<ClipboardEntry.NodeSectionData> sections = clipboard.nodeSections();

        // Calculate offset from anchor to paste location
        double offsetX = pasteLocationX - clipboard.anchorX();
        double offsetY = pasteLocationY - clipboard.anchorY();

        // Prepare text to insert
        List<String> sectionTexts;

        if (clipboard.isCut()) {
            // Cut: use original names, just translate coordinates
            sectionTexts = new ArrayList<>();
            for (ClipboardEntry.NodeSectionData section : sections) {
                String translated = translateCoordinates(section.sectionText(), offsetX, offsetY);
                sectionTexts.add(translated);
            }
        } else {
            // Copy: apply suffix and translate coordinates
            String suffix = generateCopySuffix();
            sectionTexts = applyNameSuffix(sections, suffix);

            // Translate coordinates for each section
            for (int i = 0; i < sectionTexts.size(); i++) {
                sectionTexts.set(i, translateCoordinates(sectionTexts.get(i), offsetX, offsetY));
            }
        }

        // Build the insert text
        StringBuilder insertText = new StringBuilder();
        for (String sectionText : sectionTexts) {
            if (!insertText.isEmpty()) {
                insertText.append("\n");
            }
            // Ensure section doesn't have leading newline
            String trimmed = sectionText.startsWith("\n") ? sectionText.substring(1) : sectionText;
            insertText.append(trimmed);
        }

        // Ensure proper formatting - add newline before if needed
        String textToInsert = insertText.toString();
        if (!textToInsert.startsWith("\n")) {
            textToInsert = "\n" + textToInsert;
        }
        if (!textToInsert.endsWith("\n")) {
            textToInsert = textToInsert + "\n";
        }

        // Find insertion point and insert
        int insertionPoint = findInsertionPointAfterCursor();
        insertTextAtPosition(textToInsert, insertionPoint);

        // Clear clipboard after cut paste (can only paste once)
        if (clipboard.isCut()) {
            clipboard = null;
        }

        logger.info("Pasted {} nodes at ({}, {})", sections.size(), pasteLocationX, pasteLocationY);
    }

    /**
     * Check if clipboard has content available to paste.
     */
    public boolean hasClipboardContent() {
        return clipboard != null && !clipboard.nodeSections().isEmpty();
    }

    /**
     * Check if the current selection can be cut/copied.
     */
    public boolean canCutOrCopy() {
        return model != null && model.getSelectedNodeCount() > 0;
    }

    // ========== INTERNAL METHODS ==========

    /**
     * Extract node sections from text for the given node names.
     * Returns sections ordered by their position in the text file.
     */
    private List<ClipboardEntry.NodeSectionData> extractNodeSections(Set<String> nodeNames) {
        List<ClipboardEntry.NodeSectionData> sections = new ArrayList<>();

        try {
            Document doc = textEditor.getTextArea().getDocument();
            String text = doc.getText(0, doc.getLength());

            // Find all node sections and their positions
            Map<String, Integer> nodePositions = new HashMap<>();
            for (String nodeName : nodeNames) {
                int[] bounds = getNodeSectionBounds(text, nodeName);
                if (bounds != null) {
                    nodePositions.put(nodeName, bounds[0]);
                }
            }

            // Sort by text position
            List<String> sortedNames = new ArrayList<>(nodePositions.keySet());
            sortedNames.sort(Comparator.comparingInt(nodePositions::get));

            // Extract each section in order
            int textOrder = 0;
            for (String nodeName : sortedNames) {
                int[] bounds = getNodeSectionBounds(text, nodeName);
                if (bounds != null) {
                    String sectionText = text.substring(bounds[0], bounds[1]);
                    ClipboardEntry.NodeSectionData data = parseNodeSection(nodeName, sectionText, textOrder++);
                    if (data != null) {
                        sections.add(data);
                    }
                }
            }
        } catch (BadLocationException e) {
            logger.error("Error extracting node sections: {}", e.getMessage());
        }

        return sections;
    }

    /**
     * Find the text boundaries of a node section.
     */
    private int[] getNodeSectionBounds(String text, String nodeName) {
        String escapedNodeName = Pattern.quote(nodeName);
        String pattern = "\\[node\\." + escapedNodeName + "\\](?:\\r?\\n|$)(?:(?!\\[)[^\\r\\n]*(?:\\r?\\n|$))*";
        Pattern nodePattern = Pattern.compile(pattern, Pattern.MULTILINE);

        Matcher matcher = nodePattern.matcher(text);
        if (matcher.find()) {
            return new int[] { matcher.start(), matcher.end() };
        }
        return null;
    }

    /**
     * Parse a node section to extract its properties.
     */
    private ClipboardEntry.NodeSectionData parseNodeSection(String nodeName, String sectionText, int textOrder) {
        // Extract coordinates
        double x = 0, y = 0;
        Matcher locMatcher = LOC_PATTERN.matcher(sectionText);
        if (locMatcher.find()) {
            try {
                x = Double.parseDouble(locMatcher.group(1));
                y = Double.parseDouble(locMatcher.group(2));
            } catch (NumberFormatException e) {
                logger.warn("Invalid coordinates for node {}: {}", nodeName, e.getMessage());
            }
        }

        return new ClipboardEntry.NodeSectionData(nodeName, sectionText, x, y, textOrder);
    }

    /**
     * Generate a unique suffix for copied nodes.
     * Checks existing names and increments until finding unused suffix.
     */
    private String generateCopySuffix() {
        // Get all existing node names
        Set<String> existingNames = new HashSet<>();
        for (ModelNode node : model.getAllNodes()) {
            existingNames.add(node.getName());
        }

        // Find the next available suffix
        int suffixNum = 1;
        while (true) {
            String suffix = "_copy" + suffixNum;
            boolean anyConflict = false;

            // Check if any clipboard node would conflict with existing names
            for (ClipboardEntry.NodeSectionData section : clipboard.nodeSections()) {
                String newName = section.originalName() + suffix;
                if (existingNames.contains(newName)) {
                    anyConflict = true;
                    break;
                }
            }

            if (!anyConflict) {
                return suffix;
            }
            suffixNum++;
        }
    }

    /**
     * Apply a name suffix to all nodes and update internal references.
     * Returns the modified section texts with new names.
     */
    private List<String> applyNameSuffix(List<ClipboardEntry.NodeSectionData> sections, String suffix) {
        // Build a mapping from old names to new names
        Map<String, String> nameMapping = new HashMap<>();
        for (ClipboardEntry.NodeSectionData section : sections) {
            nameMapping.put(section.originalName(), section.originalName() + suffix);
        }

        List<String> modifiedSections = new ArrayList<>();

        for (ClipboardEntry.NodeSectionData section : sections) {
            String text = section.sectionText();
            String oldName = section.originalName();
            String newName = nameMapping.get(oldName);

            // Replace the section header [node.oldName] -> [node.newName]
            text = text.replaceFirst(
                "\\[node\\." + Pattern.quote(oldName) + "\\]",
                "[node." + newName + "]"
            );

            // Replace internal ds_X references if they point to nodes being copied
            for (Map.Entry<String, String> mapping : nameMapping.entrySet()) {
                String oldTarget = mapping.getKey();
                String newTarget = mapping.getValue();
                // Pattern: ds_N = oldTarget (whole line match)
                text = text.replaceAll(
                    "(?m)^(ds_\\d+\\s*=\\s*)" + Pattern.quote(oldTarget) + "(\\s*)$",
                    "$1" + newTarget + "$2"
                );
            }

            modifiedSections.add(text);
        }

        return modifiedSections;
    }

    /**
     * Translate coordinates in a section text by the given offset.
     */
    private String translateCoordinates(String sectionText, double offsetX, double offsetY) {
        Matcher matcher = LOC_PATTERN.matcher(sectionText);
        if (matcher.find()) {
            try {
                double oldX = Double.parseDouble(matcher.group(1));
                double oldY = Double.parseDouble(matcher.group(2));
                double newX = oldX + offsetX;
                double newY = oldY + offsetY;

                String newLoc = String.format("loc = %.2f, %.2f", newX, newY);
                return sectionText.substring(0, matcher.start()) + newLoc + sectionText.substring(matcher.end());
            } catch (NumberFormatException e) {
                logger.warn("Error translating coordinates: {}", e.getMessage());
            }
        }
        return sectionText;
    }

    /**
     * Find the insertion point after the section containing the cursor.
     */
    private int findInsertionPointAfterCursor() {
        try {
            int cursorPos = textEditor.getTextArea().getCaretPosition();
            Document doc = textEditor.getTextArea().getDocument();
            String text = doc.getText(0, doc.getLength());

            // Handle empty document
            if (text.isEmpty()) {
                return 0;
            }

            // Find all section headers and their positions
            Matcher matcher = SECTION_HEADER_PATTERN.matcher(text);
            List<Integer> sectionStarts = new ArrayList<>();
            while (matcher.find()) {
                sectionStarts.add(matcher.start());
            }

            if (sectionStarts.isEmpty()) {
                // No sections found, append at end
                return text.length();
            }

            // Find which section contains the cursor
            int currentSectionStart = -1;
            int nextSectionStart = -1;

            for (int i = 0; i < sectionStarts.size(); i++) {
                int sectionStart = sectionStarts.get(i);
                if (sectionStart <= cursorPos) {
                    currentSectionStart = sectionStart;
                    if (i + 1 < sectionStarts.size()) {
                        nextSectionStart = sectionStarts.get(i + 1);
                    }
                } else {
                    break;
                }
            }

            if (nextSectionStart != -1) {
                // Insert just before the next section
                // But leave any blank line before the next section
                int insertPoint = nextSectionStart;
                while (insertPoint > 0 && text.charAt(insertPoint - 1) == '\n') {
                    insertPoint--;
                }
                // Keep one newline
                if (insertPoint < nextSectionStart) {
                    insertPoint++;
                }
                return insertPoint;
            } else {
                // No next section - insert at end
                return text.length();
            }
        } catch (BadLocationException e) {
            logger.error("Error finding insertion point: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Insert text at the given position using atomic edit operations.
     */
    private void insertTextAtPosition(String textToInsert, int position) {
        try {
            textEditor.getTextArea().beginAtomicEdit();
            try {
                Document doc = textEditor.getTextArea().getDocument();
                doc.insertString(position, textToInsert, null);
            } finally {
                textEditor.getTextArea().endAtomicEdit();
            }
        } catch (BadLocationException e) {
            logger.error("Error inserting text: {}", e.getMessage());
        }
    }
}
