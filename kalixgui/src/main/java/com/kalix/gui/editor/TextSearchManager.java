package com.kalix.gui.editor;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages search and replace functionality for text components.
 * Extracted from EnhancedTextEditor to improve maintainability.
 */
public class TextSearchManager {
    
    private final JTextComponent textComponent;
    private final Component parentComponent;
    
    // Search state
    private String lastSearchTerm = "";
    private int lastFoundPosition = -1;
    
    /**
     * Creates a new TextSearchManager.
     * 
     * @param textComponent the text component to search in
     * @param parentComponent the parent component for dialog positioning
     */
    public TextSearchManager(JTextComponent textComponent, Component parentComponent) {
        this.textComponent = textComponent;
        this.parentComponent = parentComponent;
    }
    
    /**
     * Shows the find dialog.
     */
    public void showFindDialog() {
        JDialog findDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(parentComponent), "Find", true);
        findDialog.setSize(400, 150);
        findDialog.setLocationRelativeTo(parentComponent);
        findDialog.setLayout(new BorderLayout());
        
        // Create components
        JPanel inputPanel = new JPanel(new FlowLayout());
        JLabel findLabel = new JLabel("Find:");
        JTextField findField = new JTextField(lastSearchTerm, 20);
        JCheckBox caseSensitive = new JCheckBox("Case sensitive");
        
        inputPanel.add(findLabel);
        inputPanel.add(findField);
        inputPanel.add(caseSensitive);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton findNextButton = new JButton("Find Next");
        JButton findPrevButton = new JButton("Find Previous");
        JButton cancelButton = new JButton("Cancel");
        
        buttonPanel.add(findNextButton);
        buttonPanel.add(findPrevButton);
        buttonPanel.add(cancelButton);
        
        findDialog.add(inputPanel, BorderLayout.CENTER);
        findDialog.add(buttonPanel, BorderLayout.SOUTH);
        
        // Button actions
        findNextButton.addActionListener(e -> {
            String searchTerm = findField.getText();
            if (!searchTerm.isEmpty()) {
                lastSearchTerm = searchTerm;
                findNext(searchTerm, caseSensitive.isSelected());
            }
        });
        
        findPrevButton.addActionListener(e -> {
            String searchTerm = findField.getText();
            if (!searchTerm.isEmpty()) {
                lastSearchTerm = searchTerm;
                findPrevious(searchTerm, caseSensitive.isSelected());
            }
        });
        
        cancelButton.addActionListener(e -> findDialog.dispose());
        
        // Enter key for find next
        findField.addActionListener(e -> findNextButton.doClick());
        
        // Focus and show
        findField.selectAll();
        findDialog.setVisible(true);
    }
    
    /**
     * Shows the find and replace dialog.
     */
    public void showFindReplaceDialog() {
        JDialog replaceDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(parentComponent), "Find and Replace", true);
        replaceDialog.setSize(450, 200);
        replaceDialog.setLocationRelativeTo(parentComponent);
        replaceDialog.setLayout(new BorderLayout());
        
        // Create components
        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        
        JLabel findLabel = new JLabel("Find:");
        JTextField findField = new JTextField(lastSearchTerm, 20);
        JLabel replaceLabel = new JLabel("Replace:");
        JTextField replaceField = new JTextField(20);
        JCheckBox caseSensitive = new JCheckBox("Case sensitive");
        
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        gbc.gridx = 0; gbc.gridy = 0;
        inputPanel.add(findLabel, gbc);
        gbc.gridx = 1;
        inputPanel.add(findField, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1;
        inputPanel.add(replaceLabel, gbc);
        gbc.gridx = 1;
        inputPanel.add(replaceField, gbc);
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        inputPanel.add(caseSensitive, gbc);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton findNextButton = new JButton("Find Next");
        JButton replaceButton = new JButton("Replace");
        JButton replaceAllButton = new JButton("Replace All");
        JButton cancelButton = new JButton("Cancel");
        
        buttonPanel.add(findNextButton);
        buttonPanel.add(replaceButton);
        buttonPanel.add(replaceAllButton);
        buttonPanel.add(cancelButton);
        
        replaceDialog.add(inputPanel, BorderLayout.CENTER);
        replaceDialog.add(buttonPanel, BorderLayout.SOUTH);
        
        // Button actions
        findNextButton.addActionListener(e -> {
            String searchTerm = findField.getText();
            if (!searchTerm.isEmpty()) {
                lastSearchTerm = searchTerm;
                findNext(searchTerm, caseSensitive.isSelected());
            }
        });
        
        replaceButton.addActionListener(e -> {
            String searchTerm = findField.getText();
            String replaceTerm = replaceField.getText();
            if (!searchTerm.isEmpty()) {
                lastSearchTerm = searchTerm;
                replaceNext(searchTerm, replaceTerm, caseSensitive.isSelected());
            }
        });
        
        replaceAllButton.addActionListener(e -> {
            String searchTerm = findField.getText();
            String replaceTerm = replaceField.getText();
            if (!searchTerm.isEmpty()) {
                lastSearchTerm = searchTerm;
                int count = replaceAll(searchTerm, replaceTerm, caseSensitive.isSelected());
                JOptionPane.showMessageDialog(replaceDialog, 
                    String.format("Replaced %d occurrence(s)", count),
                    "Replace All", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        
        cancelButton.addActionListener(e -> replaceDialog.dispose());
        
        // Enter key for find next
        findField.addActionListener(e -> findNextButton.doClick());
        
        // Focus and show
        findField.selectAll();
        replaceDialog.setVisible(true);
    }
    
    /**
     * Finds the next occurrence of the search term.
     */
    public void findNext(String searchTerm, boolean caseSensitive) {
        String text = textComponent.getText();
        if (text == null || text.isEmpty() || searchTerm.isEmpty()) {
            return;
        }
        
        int startPos = Math.max(0, textComponent.getCaretPosition());
        
        // Create pattern for search
        Pattern pattern = caseSensitive ? 
            Pattern.compile(Pattern.quote(searchTerm)) :
            Pattern.compile(Pattern.quote(searchTerm), Pattern.CASE_INSENSITIVE);
        
        Matcher matcher = pattern.matcher(text);
        if (matcher.find(startPos)) {
            int foundPos = matcher.start();
            textComponent.setCaretPosition(foundPos);
            textComponent.select(foundPos, foundPos + searchTerm.length());
            lastFoundPosition = foundPos;
        } else {
            // Search from beginning if not found from current position
            if (matcher.find(0)) {
                int foundPos = matcher.start();
                textComponent.setCaretPosition(foundPos);
                textComponent.select(foundPos, foundPos + searchTerm.length());
                lastFoundPosition = foundPos;
            }
        }
    }
    
    /**
     * Finds the previous occurrence of the search term.
     */
    public void findPrevious(String searchTerm, boolean caseSensitive) {
        String text = textComponent.getText();
        if (text == null || text.isEmpty() || searchTerm.isEmpty()) {
            return;
        }
        
        int startPos = Math.min(text.length() - 1, textComponent.getCaretPosition() - 1);
        
        // Create pattern for search
        Pattern pattern = caseSensitive ? 
            Pattern.compile(Pattern.quote(searchTerm)) :
            Pattern.compile(Pattern.quote(searchTerm), Pattern.CASE_INSENSITIVE);
        
        // Find all matches up to current position
        Matcher matcher = pattern.matcher(text.substring(0, startPos + 1));
        int lastMatchStart = -1;
        while (matcher.find()) {
            lastMatchStart = matcher.start();
        }
        
        if (lastMatchStart >= 0) {
            textComponent.setCaretPosition(lastMatchStart);
            textComponent.select(lastMatchStart, lastMatchStart + searchTerm.length());
            lastFoundPosition = lastMatchStart;
        }
    }
    
    /**
     * Replaces the currently selected text if it matches the search term.
     */
    public void replaceNext(String searchTerm, String replaceTerm, boolean caseSensitive) {
        String selectedText = textComponent.getSelectedText();
        if (selectedText != null) {
            boolean matches = caseSensitive ? 
                selectedText.equals(searchTerm) :
                selectedText.equalsIgnoreCase(searchTerm);
            
            if (matches) {
                textComponent.replaceSelection(replaceTerm);
            }
        }
        
        // Find next occurrence
        findNext(searchTerm, caseSensitive);
    }
    
    /**
     * Replaces all occurrences of the search term.
     */
    public int replaceAll(String searchTerm, String replaceTerm, boolean caseSensitive) {
        String text = textComponent.getText();
        if (text == null || text.isEmpty() || searchTerm.isEmpty()) {
            return 0;
        }
        
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        Pattern pattern = Pattern.compile(Pattern.quote(searchTerm), flags);
        Matcher matcher = pattern.matcher(text);
        
        int count = 0;
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replaceTerm));
            count++;
        }
        matcher.appendTail(result);
        
        if (count > 0) {
            textComponent.setText(result.toString());
            textComponent.setCaretPosition(0);
        }
        
        return count;
    }
    
    /**
     * Gets the last search term used.
     */
    public String getLastSearchTerm() {
        return lastSearchTerm;
    }
}