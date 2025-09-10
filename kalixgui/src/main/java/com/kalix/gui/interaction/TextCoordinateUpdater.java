package com.kalix.gui.interaction;

import com.kalix.gui.editor.EnhancedTextEditor;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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
}