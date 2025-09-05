package com.kalix.gui.editor;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Syntax highlighter for INI and TOML files.
 * Provides color highlighting for sections, properties, comments, strings, and numbers.
 */
public class IniTomlSyntaxHighlighter {
    
    // Color definitions for syntax highlighting
    private static final Color SECTION_COLOR = new Color(0, 102, 153);      // Dark blue for [sections]
    private static final Color PROPERTY_NAME_COLOR = new Color(153, 0, 85);  // Dark magenta for property names
    private static final Color PROPERTY_VALUE_COLOR = new Color(0, 128, 0);  // Green for property values
    private static final Color COMMENT_COLOR = new Color(128, 128, 128);     // Gray for comments
    private static final Color STRING_COLOR = new Color(196, 26, 22);        // Red for strings
    private static final Color NUMBER_COLOR = new Color(25, 23, 124);        // Dark blue for numbers
    private static final Color DEFAULT_COLOR = Color.BLACK;                   // Default text color
    
    // Regular expression patterns for different syntax elements
    private static final Pattern SECTION_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$", Pattern.MULTILINE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("^\\s*[#;].*$", Pattern.MULTILINE);
    private static final Pattern PROPERTY_PATTERN = Pattern.compile("^\\s*([^=\\s#;\\[]+)\\s*=\\s*(.*)$", Pattern.MULTILINE);
    private static final Pattern STRING_PATTERN = Pattern.compile("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(\\.\\d+)?\\b");
    
    private final StyledDocument document;
    private final Style defaultStyle;
    private final Style sectionStyle;
    private final Style propertyNameStyle;
    private final Style propertyValueStyle;
    private final Style commentStyle;
    private final Style stringStyle;
    private final Style numberStyle;
    
    public IniTomlSyntaxHighlighter(JTextPane textPane) {
        this.document = textPane.getStyledDocument();
        
        // Create styles
        this.defaultStyle = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setForeground(defaultStyle, DEFAULT_COLOR);
        
        this.sectionStyle = document.addStyle("section", defaultStyle);
        StyleConstants.setForeground(sectionStyle, SECTION_COLOR);
        StyleConstants.setBold(sectionStyle, true);
        
        this.propertyNameStyle = document.addStyle("propertyName", defaultStyle);
        StyleConstants.setForeground(propertyNameStyle, PROPERTY_NAME_COLOR);
        StyleConstants.setBold(propertyNameStyle, true);
        
        this.propertyValueStyle = document.addStyle("propertyValue", defaultStyle);
        StyleConstants.setForeground(propertyValueStyle, PROPERTY_VALUE_COLOR);
        
        this.commentStyle = document.addStyle("comment", defaultStyle);
        StyleConstants.setForeground(commentStyle, COMMENT_COLOR);
        StyleConstants.setItalic(commentStyle, true);
        
        this.stringStyle = document.addStyle("string", defaultStyle);
        StyleConstants.setForeground(stringStyle, STRING_COLOR);
        
        this.numberStyle = document.addStyle("number", defaultStyle);
        StyleConstants.setForeground(numberStyle, NUMBER_COLOR);
    }
    
    /**
     * Applies syntax highlighting to the entire document.
     */
    public void highlightSyntax() {
        try {
            String text = document.getText(0, document.getLength());
            
            // Reset all text to default style
            document.setCharacterAttributes(0, document.getLength(), defaultStyle, true);
            
            // Apply highlighting in order of priority (comments first, then others)
            highlightComments(text);
            highlightSections(text);
            highlightProperties(text);
            highlightStringsAndNumbers(text);
            
        } catch (BadLocationException e) {
            System.err.println("Error highlighting syntax: " + e.getMessage());
        }
    }
    
    /**
     * Highlights section headers like [section_name].
     */
    private void highlightSections(String text) {
        Matcher matcher = SECTION_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            document.setCharacterAttributes(start, end - start, sectionStyle, true);
        }
    }
    
    /**
     * Highlights comments (lines starting with # or ;).
     */
    private void highlightComments(String text) {
        Matcher matcher = COMMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            document.setCharacterAttributes(start, end - start, commentStyle, true);
        }
    }
    
    /**
     * Highlights property names and values (key=value pairs).
     */
    private void highlightProperties(String text) {
        Matcher matcher = PROPERTY_PATTERN.matcher(text);
        while (matcher.find()) {
            // Check if this line is a comment (starts with # or ;)
            String lineStart = text.substring(matcher.start(), Math.min(matcher.start() + 10, text.length())).trim();
            if (lineStart.startsWith("#") || lineStart.startsWith(";")) {
                continue; // Skip comment lines
            }
            
            // Highlight property name (group 1)
            int nameStart = matcher.start(1);
            int nameEnd = matcher.end(1);
            document.setCharacterAttributes(nameStart, nameEnd - nameStart, propertyNameStyle, true);
            
            // Highlight property value (group 2)
            int valueStart = matcher.start(2);
            int valueEnd = matcher.end(2);
            if (valueStart >= 0 && valueEnd >= valueStart) {
                document.setCharacterAttributes(valueStart, valueEnd - valueStart, propertyValueStyle, true);
            }
        }
    }
    
    /**
     * Highlights strings and numbers within property values.
     */
    private void highlightStringsAndNumbers(String text) {
        // Only highlight strings and numbers in property values, not in comments or sections
        Matcher propertyMatcher = PROPERTY_PATTERN.matcher(text);
        
        while (propertyMatcher.find()) {
            // Check if this line is a comment
            String lineStart = text.substring(propertyMatcher.start(), Math.min(propertyMatcher.start() + 10, text.length())).trim();
            if (lineStart.startsWith("#") || lineStart.startsWith(";")) {
                continue; // Skip comment lines
            }
            
            // Get the property value part
            int valueStart = propertyMatcher.start(2);
            int valueEnd = propertyMatcher.end(2);
            
            if (valueStart >= 0 && valueEnd >= valueStart) {
                String valueText = text.substring(valueStart, valueEnd);
                
                // Highlight strings within the value
                Matcher stringMatcher = STRING_PATTERN.matcher(valueText);
                while (stringMatcher.find()) {
                    int start = valueStart + stringMatcher.start();
                    int end = valueStart + stringMatcher.end();
                    document.setCharacterAttributes(start, end - start, stringStyle, true);
                }
                
                // Highlight numbers within the value (but not inside strings)
                Matcher numberMatcher = NUMBER_PATTERN.matcher(valueText);
                while (numberMatcher.find()) {
                    int start = valueStart + numberMatcher.start();
                    int end = valueStart + numberMatcher.end();
                    
                    // Check if this number is inside a string by looking at its style
                    AttributeSet attrs = document.getCharacterElement(start).getAttributes();
                    if (!stringStyle.equals(attrs)) {
                        document.setCharacterAttributes(start, end - start, numberStyle, true);
                    }
                }
            }
        }
    }
    
    /**
     * Applies syntax highlighting to a specific range of text.
     * This can be used for incremental highlighting during editing.
     * 
     * @param offset The starting offset in the document
     * @param length The length of text to highlight
     */
    public void highlightRange(int offset, int length) {
        try {
            // For simplicity, re-highlight the entire document
            // This could be optimized to only highlight the affected lines
            highlightSyntax();
        } catch (Exception e) {
            System.err.println("Error highlighting range: " + e.getMessage());
        }
    }
}