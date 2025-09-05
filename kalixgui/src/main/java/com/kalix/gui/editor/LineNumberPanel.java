package com.kalix.gui.editor;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Utilities;
import java.awt.*;

/**
 * Line number panel that displays line numbers alongside the text editor.
 * Synchronizes with the text editor's scrolling and highlights the current line.
 */
public class LineNumberPanel extends JPanel {
    
    private final JTextPane textPane;
    private static final Color LINE_NUMBER_COLOR = new Color(128, 128, 128);
    private static final Color LINE_NUMBER_BACKGROUND = new Color(248, 248, 248);
    private static final Color CURRENT_LINE_NUMBER_COLOR = new Color(64, 64, 64);
    private static final int MARGIN = 5;
    
    public LineNumberPanel(JTextPane textPane) {
        this.textPane = textPane;
        setBackground(LINE_NUMBER_BACKGROUND);
        setPreferredSize(new Dimension(50, 0));
        
        // Listen for text changes to repaint line numbers
        textPane.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updatePreferredSize();
                repaint();
            }
            
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updatePreferredSize();
                repaint();
            }
            
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updatePreferredSize();
                repaint();
            }
        });
        
        // Listen for caret changes to highlight current line number
        textPane.addCaretListener(e -> repaint());
    }
    
    private void updatePreferredSize() {
        int lineCount = getLineCount();
        String maxLineNumber = String.valueOf(lineCount);
        FontMetrics fm = getFontMetrics(getFont());
        int width = fm.stringWidth(maxLineNumber) + 2 * MARGIN + 10; // Extra padding
        setPreferredSize(new Dimension(width, 0));
        revalidate();
    }
    
    private int getLineCount() {
        if (textPane == null) return 1;
        
        String text = textPane.getText();
        if (text == null || text.isEmpty()) return 1;
        
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
    
    private int getCurrentLine() {
        if (textPane == null) return 1;
        
        try {
            int caretPos = textPane.getCaretPosition();
            int line = 1;
            String text = textPane.getText(0, caretPos);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        } catch (BadLocationException e) {
            return 1;
        }
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        if (textPane == null) return;
        
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Get visible area
        Rectangle clipBounds = g2d.getClipBounds();
        int startOffset = textPane.viewToModel(new Point(0, clipBounds.y));
        int endOffset = textPane.viewToModel(new Point(0, clipBounds.y + clipBounds.height));
        
        try {
            // Calculate starting line number
            int startLine = getLineOfOffset(startOffset) + 1;
            int endLine = getLineOfOffset(endOffset) + 1;
            int currentLine = getCurrentLine();
            
            FontMetrics fm = g2d.getFontMetrics();
            int fontHeight = fm.getHeight();
            int fontAscent = fm.getAscent();
            
            // Draw line numbers
            for (int line = startLine; line <= endLine; line++) {
                try {
                    int offset = getOffsetOfLine(line - 1);
                    Rectangle rect = textPane.modelToView(offset);
                    
                    if (rect != null) {
                        String lineNumber = String.valueOf(line);
                        
                        // Highlight current line number
                        if (line == currentLine) {
                            g2d.setColor(CURRENT_LINE_NUMBER_COLOR);
                            g2d.setFont(getFont().deriveFont(Font.BOLD));
                        } else {
                            g2d.setColor(LINE_NUMBER_COLOR);
                            g2d.setFont(getFont().deriveFont(Font.PLAIN));
                        }
                        
                        int x = getWidth() - fm.stringWidth(lineNumber) - MARGIN;
                        int y = rect.y + fontAscent;
                        
                        g2d.drawString(lineNumber, x, y);
                    }
                } catch (BadLocationException ex) {
                    // Skip this line if there's an error
                }
            }
        } catch (Exception e) {
            // Fallback: just draw basic line numbers
            drawFallbackLineNumbers(g2d);
        }
        
        g2d.dispose();
    }
    
    private void drawFallbackLineNumbers(Graphics2D g2d) {
        int lineCount = getLineCount();
        int currentLine = getCurrentLine();
        FontMetrics fm = g2d.getFontMetrics();
        int fontHeight = fm.getHeight();
        int fontAscent = fm.getAscent();
        
        for (int line = 1; line <= Math.min(lineCount, 100); line++) { // Limit to first 100 lines for performance
            String lineNumber = String.valueOf(line);
            
            if (line == currentLine) {
                g2d.setColor(CURRENT_LINE_NUMBER_COLOR);
                g2d.setFont(getFont().deriveFont(Font.BOLD));
            } else {
                g2d.setColor(LINE_NUMBER_COLOR);
                g2d.setFont(getFont().deriveFont(Font.PLAIN));
            }
            
            int x = getWidth() - fm.stringWidth(lineNumber) - MARGIN;
            int y = (line - 1) * fontHeight + fontAscent;
            
            if (y > getHeight()) break;
            
            g2d.drawString(lineNumber, x, y);
        }
    }
    
    private int getLineOfOffset(int offset) {
        if (textPane == null) return 0;
        
        String text = textPane.getText();
        if (text == null) return 0;
        
        int line = 0;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
    
    private int getOffsetOfLine(int line) {
        if (textPane == null) return 0;
        
        String text = textPane.getText();
        if (text == null) return 0;
        
        int currentLine = 0;
        for (int i = 0; i < text.length(); i++) {
            if (currentLine == line) {
                return i;
            }
            if (text.charAt(i) == '\n') {
                currentLine++;
            }
        }
        return text.length();
    }
    
    @Override
    public void setFont(Font font) {
        super.setFont(font);
        updatePreferredSize();
    }
}