package com.kalix.ide.editor;

import com.kalix.ide.utils.ErrorHandler;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * Manages text navigation functionality for text components.
 * Extracted from EnhancedTextEditor to improve maintainability.
 */
public class TextNavigationManager {
    
    private final JTextComponent textComponent;
    private final Component parentComponent;
    
    /**
     * Creates a new TextNavigationManager.
     * 
     * @param textComponent the text component to navigate in
     * @param parentComponent the parent component for dialog positioning
     */
    public TextNavigationManager(JTextComponent textComponent, Component parentComponent) {
        this.textComponent = textComponent;
        this.parentComponent = parentComponent;
    }
    
    /**
     * Shows the Go to Line dialog.
     */
    public void showGoToLineDialog() {
        // Get total line count
        int totalLines = getLineCount();

        String input = JOptionPane.showInputDialog(
            parentComponent,
            String.format("Go to line (1-%d):", totalLines),
            "Go to Line",
            JOptionPane.PLAIN_MESSAGE
        );
        
        if (input != null && !input.trim().isEmpty()) {
            try {
                int targetLine = Integer.parseInt(input.trim());
                
                if (targetLine >= 1 && targetLine <= totalLines) {
                    goToLine(targetLine);
                } else {
                    JOptionPane.showMessageDialog(
                        parentComponent,
                        String.format("Line number must be between 1 and %d", totalLines),
                        "Invalid Line Number",
                        JOptionPane.WARNING_MESSAGE
                    );
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(
                    parentComponent,
                    "Please enter a valid line number",
                    "Invalid Input",
                    JOptionPane.WARNING_MESSAGE
                );
            }
        }
    }
    
    /**
     * Goes to the specified line number.
     * 
     * @param lineNumber the line number to go to (1-based)
     */
    public void goToLine(int lineNumber) {
        try {
            String text = textComponent.getText();
            int position = 0;
            int currentLine = 1;
            
            // Find the start position of the target line
            for (int i = 0; i < text.length() && currentLine < lineNumber; i++) {
                if (text.charAt(i) == '\n') {
                    currentLine++;
                    position = i + 1;
                }
            }
            
            // Set caret position to start of line
            textComponent.setCaretPosition(position);
            
            // Try to find the end of the line for selection
            int endPosition = position;
            for (int i = position; i < text.length() && text.charAt(i) != '\n'; i++) {
                endPosition = i + 1;
            }
            
            // Select the entire line
            textComponent.select(position, endPosition);
            
            // Ensure the line is visible
            textComponent.requestFocusInWindow();
            
        } catch (Exception e) {
            // Log the error and provide fallback behavior
            ErrorHandler.logWarning("Failed to navigate to line " + lineNumber + ", using fallback position", "text navigation");
            textComponent.setCaretPosition(0);
        }
    }
    
    /**
     * Gets the total number of lines in the text.
     * 
     * @return the line count
     */
    public int getLineCount() {
        String text = textComponent.getText();
        if (text == null || text.isEmpty()) return 1;
        
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
    
    /**
     * Gets the current line number (1-based) where the caret is positioned.
     * 
     * @return the current line number
     */
    public int getCurrentLineNumber() {
        try {
            int caretPos = textComponent.getCaretPosition();
            if (caretPos == 0) return 1;
            
            String text = textComponent.getText(0, caretPos);
            int lines = 1;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            return lines;
        } catch (Exception e) {
            ErrorHandler.logWarning("Failed to get current line number", "text navigation");
            return 1;
        }
    }
}