package com.kalix.gui.interaction;

import com.kalix.gui.editor.EnhancedTextEditor;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Set;

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
     * Update the coordinate of a node in the text editor.
     * Uses regex to find and replace the loc = x, y line for the specified node.
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
            String currentText = textEditor.getText();
            if (currentText == null || currentText.trim().isEmpty()) {
                return;
            }
            
            // Create regex pattern to match the node section and its loc line
            // Pattern explanation:
            // (\[node\.nodeName\].*?loc\s*=\s*)([0-9.-]+)\s*,\s*([0-9.-]+)
            // Group 1: Everything up to and including "loc = "  
            // Group 2: X coordinate (capture for replacement)
            // Group 3: Y coordinate (capture for replacement)
            String escapedNodeName = Pattern.quote(nodeName);
            String pattern = "((\\[node\\." + escapedNodeName + "\\][^\\[]*?loc\\s*=\\s*)([0-9.-]+)\\s*,\\s*)([0-9.-]+)";
            Pattern nodePattern = Pattern.compile(pattern, Pattern.DOTALL | Pattern.MULTILINE);
            
            Matcher matcher = nodePattern.matcher(currentText);
            
            if (matcher.find()) {
                // Format coordinates with reasonable precision (2 decimal places)
                String formattedX = String.format("%.2f", x);
                String formattedY = String.format("%.2f", y);
                
                // Replace the coordinates while preserving the rest of the formatting
                String beforeLoc = matcher.group(2); // "[node.name]...loc = "
                String replacement = beforeLoc + formattedX + ", " + formattedY;
                
                String updatedText = currentText.substring(0, matcher.start()) + 
                                   replacement + 
                                   currentText.substring(matcher.end());
                
                // Update the text editor
                textEditor.setText(updatedText);
                
                System.out.println("TextCoordinateUpdater: Updated " + nodeName + " to (" + formattedX + ", " + formattedY + ")");
            } else {
                System.err.println("TextCoordinateUpdater: Could not find node section for: " + nodeName);
            }
            
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
     * Delete nodes from the text editor by removing their entire INI sections.
     * Uses regex to find and remove complete [node.name] sections including all content.
     * @param nodeNames Set of node names to delete from text
     */
    public void deleteNodesFromText(Set<String> nodeNames) {
        if (textEditor == null || nodeNames == null || nodeNames.isEmpty()) {
            return;
        }
        
        // Set flag to prevent infinite update loops
        updatingFromModel = true;
        
        try {
            String currentText = textEditor.getText();
            if (currentText == null || currentText.trim().isEmpty()) {
                return;
            }
            
            String updatedText = currentText;
            
            // Delete each node section
            for (String nodeName : nodeNames) {
                updatedText = deleteNodeSectionFromText(updatedText, nodeName);
            }
            
            // Only update if text actually changed
            if (!updatedText.equals(currentText)) {
                textEditor.setText(updatedText);
                System.out.println("TextCoordinateUpdater: Deleted " + nodeNames.size() + " node sections from text");
            }
            
        } catch (Exception e) {
            System.err.println("TextCoordinateUpdater: Error deleting nodes from text: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Clear the flag
            updatingFromModel = false;
        }
    }
    
    /**
     * Delete a single node section from the text.
     * @param text Current text content
     * @param nodeName Node name to delete
     * @return Updated text with node section removed
     */
    private String deleteNodeSectionFromText(String text, String nodeName) {
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
            // Remove the matched section
            String result = text.substring(0, matcher.start()) + text.substring(matcher.end());
            System.out.println("TextCoordinateUpdater: Removed section for node: " + nodeName);
            return result;
        } else {
            System.err.println("TextCoordinateUpdater: Could not find section for node: " + nodeName);
            return text;
        }
    }
}