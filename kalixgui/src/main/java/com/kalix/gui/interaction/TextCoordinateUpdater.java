package com.kalix.gui.interaction;

import com.kalix.gui.editor.EnhancedTextEditor;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;
import javax.swing.text.Document;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CompoundEdit;

/**
 * Handles updating node coordinates in the text editor when nodes are moved via dragging.
 * This provides bidirectional synchronization between the visual map and the text INI format.
 * 
 * Uses regex-based coordinate replacement to preserve INI formatting and comments.
 */
public class TextCoordinateUpdater {
    
    private final EnhancedTextEditor textEditor;
    private boolean updatingFromModel = false; // Prevent infinite update loops
    
    public TextCoordinateUpdater(EnhancedTextEditor textEditor) {
        this.textEditor = textEditor;
    }
    
    /**
     * Update the coordinate of a node in the text editor using document operations.
     * This preserves undo/redo functionality by making targeted document edits.
     * @param nodeName Name of the node to update
     * @param x New X coordinate
     * @param y New Y coordinate
     */
    public void updateNodeCoordinate(String nodeName, double x, double y) {
        if (textEditor == null) {
            return;
        }
        
        // Set flag to prevent infinite update loops
        updatingFromModel = true;
        
        try {
            Document doc = textEditor.getTextArea().getDocument();
            String currentText = doc.getText(0, doc.getLength());
            
            if (currentText == null || currentText.trim().isEmpty()) {
                return;
            }
            
            // Create regex pattern to match the coordinate values specifically
            // Pattern explanation:
            // (\[node\.nodeName\][^\[]*?loc\s*=\s*)([0-9.-]+)(\s*,\s*)([0-9.-]+)
            // Group 1: Everything up to and including "loc = "
            // Group 2: X coordinate (to replace)
            // Group 3: Comma and whitespace
            // Group 4: Y coordinate (to replace)
            String escapedNodeName = Pattern.quote(nodeName);
            String pattern = "(\\[node\\." + escapedNodeName + "[^\\[]*?loc\\s*=\\s*)([0-9.-]+)(\\s*,\\s*)([0-9.-]+)";
            Pattern nodePattern = Pattern.compile(pattern, Pattern.DOTALL | Pattern.MULTILINE);
            
            Matcher matcher = nodePattern.matcher(currentText);
            
            if (matcher.find()) {
                // Format coordinates with reasonable precision (2 decimal places)
                String formattedX = String.format("%.2f", x);
                String formattedY = String.format("%.2f", y);
                
                // Calculate positions and replacement text
                int coordStart = matcher.start(2);  // Start of X coordinate
                int coordEnd = matcher.end(4);      // End of Y coordinate
                String coordReplacement = formattedX + matcher.group(3) + formattedY;
                
                // Use document replace operation (preserves undo/redo)
                doc.remove(coordStart, coordEnd - coordStart);
                doc.insertString(coordStart, coordReplacement, null);
                
                System.out.println("TextCoordinateUpdater: Updated " + nodeName + " to (" + formattedX + ", " + formattedY + ")");
            } else {
                System.err.println("TextCoordinateUpdater: Could not find node section for: " + nodeName);
            }
            
        } catch (BadLocationException e) {
            System.err.println("TextCoordinateUpdater: Bad location error updating coordinates for " + nodeName + ": " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("TextCoordinateUpdater: Error updating coordinates for " + nodeName + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clear the flag
            updatingFromModel = false;
        }
    }
    
    /**
     * Check if currently updating from model (to prevent infinite loops).
     * @return true if currently updating text from model changes
     */
    public boolean isUpdatingFromModel() {
        return updatingFromModel;
    }
    
    /**
     * Delete nodes from the text editor by removing their entire INI sections using document operations.
     * This preserves undo/redo functionality by making targeted document removals.
     * @param nodeNames Set of node names to delete from text
     */
    public void deleteNodesFromText(Set<String> nodeNames) {
        if (textEditor == null || nodeNames == null || nodeNames.isEmpty()) {
            return;
        }
        
        // Set flag to prevent infinite update loops
        updatingFromModel = true;
        
        try {
            Document doc = textEditor.getTextArea().getDocument();
            
            // Create a CompoundEdit for grouping multiple deletions into single undo operation
            CompoundEdit compoundEdit = new CompoundEdit();
            
            // Process nodes in reverse order to maintain document positions
            // Sort by document position (descending) to delete from end to beginning
            String currentText = doc.getText(0, doc.getLength());
            
            // Find all node sections and their positions
            java.util.List<NodeSection> sectionsToDelete = new java.util.ArrayList<>();
            for (String nodeName : nodeNames) {
                NodeSection section = findNodeSection(currentText, nodeName);
                if (section != null) {
                    sectionsToDelete.add(section);
                }
            }
            
            // Sort by start position (descending) to delete from end to beginning
            sectionsToDelete.sort((a, b) -> Integer.compare(b.start, a.start));
            
            // Delete each section using document operations
            for (NodeSection section : sectionsToDelete) {
                doc.remove(section.start, section.length);
                System.out.println("TextCoordinateUpdater: Removed section for node: " + section.nodeName);
            }
            
            if (!sectionsToDelete.isEmpty()) {
                System.out.println("TextCoordinateUpdater: Deleted " + sectionsToDelete.size() + " node sections from text");
            }
            
        } catch (BadLocationException e) {
            System.err.println("TextCoordinateUpdater: Bad location error deleting nodes from text: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("TextCoordinateUpdater: Error deleting nodes from text: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clear the flag
            updatingFromModel = false;
        }
    }
    
    /**
     * Helper class to represent a node section in the document.
     */
    private static class NodeSection {
        final String nodeName;
        final int start;
        final int length;
        
        NodeSection(String nodeName, int start, int length) {
            this.nodeName = nodeName;
            this.start = start;
            this.length = length;
        }
    }
    
    /**
     * Find a node section in the text and return its position information.
     * @param text Current text content
     * @param nodeName Node name to find
     * @return NodeSection with position info, or null if not found
     */
    private NodeSection findNodeSection(String text, String nodeName) {
        // Create regex pattern to match the entire node section
        // Pattern explanation:
        // \[node\.nodeName\]          - Match [node.nodeName] header
        // (?:\r?\n|$)                 - Match newline or end of string after header
        // (?:(?!\[)[^\r\n]*(?:\r?\n|$))* - Match all lines that don't start with [ (non-section lines)
        String escapedNodeName = Pattern.quote(nodeName);
        String pattern = "\\[node\\." + escapedNodeName + "\\](?:\\r?\\n|$)(?:(?!\\[)[^\\r\\n]*(?:\\r?\\n|$))*";
        Pattern nodePattern = Pattern.compile(pattern, Pattern.MULTILINE);
        
        Matcher matcher = nodePattern.matcher(text);
        
        if (matcher.find()) {
            return new NodeSection(nodeName, matcher.start(), matcher.end() - matcher.start());
        } else {
            System.err.println("TextCoordinateUpdater: Could not find section for node: " + nodeName);
            return null;
        }
    }
}