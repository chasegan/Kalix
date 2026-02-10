package com.kalix.ide.editor;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Manages search and replace functionality for RSyntaxTextArea using the SearchEngine API.
 * Provides find and find-replace dialogs with proper integration.
 */
public class TextSearchManager {
    
    private final RSyntaxTextArea textArea;
    private final JComponent parentComponent;
    private final EnhancedTextEditor textEditor;

    // Dialog references
    private JDialog findDialog;
    private JDialog replaceDialog;
    
    // Search fields
    private JTextField searchField;
    private JTextField replaceField;
    private JCheckBox matchCaseCheckBox;
    private JCheckBox wholeWordCheckBox;
    private JCheckBox regexCheckBox;
    private JCheckBox wrapAroundCheckBox;

    // State tracking
    private boolean highlightsActive = false;
    
    /**
     * Creates a new TextSearchManager for the specified text area.
     * 
     * @param textArea the RSyntaxTextArea to manage
     * @param parentComponent the parent component for dialogs
     */
    public TextSearchManager(RSyntaxTextArea textArea, JComponent parentComponent) {
        this.textArea = textArea;
        this.parentComponent = parentComponent;
        this.textEditor = (parentComponent instanceof EnhancedTextEditor)
            ? (EnhancedTextEditor) parentComponent : null;

        // Add document listener to clear highlights when text changes
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                clearHighlights();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                clearHighlights();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                clearHighlights();
            }
        });
    }
    
    /**
     * Shows the find dialog.
     */
    public void showFindDialog() {
        if (findDialog == null) {
            createFindDialog();
        }
        
        // Pre-populate with selected text if any
        String selectedText = textArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            searchField.setText(selectedText);
        }
        
        findDialog.setVisible(true);
        searchField.requestFocus();
        searchField.selectAll();
    }
    
    /**
     * Shows the find and replace dialog.
     */
    public void showFindReplaceDialog() {
        if (replaceDialog == null) {
            createReplaceDialog();
        }
        
        // Pre-populate with selected text if any
        String selectedText = textArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            searchField.setText(selectedText);
        }
        
        replaceDialog.setVisible(true);
        searchField.requestFocus();
        searchField.selectAll();
    }
    
    /**
     * Creates the find dialog.
     */
    private void createFindDialog() {
        Window window = SwingUtilities.getWindowAncestor(parentComponent);
        findDialog = new JDialog(window instanceof Frame ? (Frame) window : null, "Find", true);
        findDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        
        // Search field
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);
        
        searchField = new JTextField(20);
        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(searchField, gbc);
        
        // Options
        matchCaseCheckBox = new JCheckBox("Match case");
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(matchCaseCheckBox, gbc);
        
        wholeWordCheckBox = new JCheckBox("Whole word");
        gbc.gridy = 2;
        panel.add(wholeWordCheckBox, gbc);
        
        regexCheckBox = new JCheckBox("Regular expression");
        gbc.gridy = 3;
        panel.add(regexCheckBox, gbc);

        wrapAroundCheckBox = new JCheckBox("Wrap around");
        wrapAroundCheckBox.setSelected(true); // Default to true
        gbc.gridy = 4;
        panel.add(wrapAroundCheckBox, gbc);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        
        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> findNext());
        buttonPanel.add(findButton);
        
        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(e -> findPrevious());
        buttonPanel.add(findPrevButton);
        
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            clearHighlights();
            findDialog.setVisible(false);
        });
        buttonPanel.add(closeButton);
        
        gbc.gridx = 0; gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);
        
        // Enter key support
        searchField.addActionListener(e -> findNext());
        
        // Escape key support
        findDialog.getRootPane().registerKeyboardAction(
            e -> {
                clearHighlights();
                findDialog.setVisible(false);
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Window close listener
        findDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                clearHighlights();
            }
        });
        
        findDialog.add(panel);
        findDialog.pack();
        findDialog.setLocationRelativeTo(parentComponent);
    }
    
    /**
     * Creates the find and replace dialog.
     */
    private void createReplaceDialog() {
        Window window = SwingUtilities.getWindowAncestor(parentComponent);
        replaceDialog = new JDialog(window instanceof Frame ? (Frame) window : null, "Find and Replace", true);
        replaceDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        
        // Search field
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);
        
        searchField = new JTextField(20);
        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(searchField, gbc);
        
        // Replace field
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Replace:"), gbc);
        
        replaceField = new JTextField(20);
        gbc.gridx = 1; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(replaceField, gbc);
        
        // Options (reuse from find dialog)
        matchCaseCheckBox = new JCheckBox("Match case");
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(matchCaseCheckBox, gbc);
        
        wholeWordCheckBox = new JCheckBox("Whole word");
        gbc.gridy = 3;
        panel.add(wholeWordCheckBox, gbc);
        
        regexCheckBox = new JCheckBox("Regular expression");
        gbc.gridy = 4;
        panel.add(regexCheckBox, gbc);

        wrapAroundCheckBox = new JCheckBox("Wrap around");
        wrapAroundCheckBox.setSelected(true); // Default to true
        gbc.gridy = 5;
        panel.add(wrapAroundCheckBox, gbc);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        
        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> findNext());
        buttonPanel.add(findButton);
        
        JButton replaceButton = new JButton("Replace");
        replaceButton.addActionListener(e -> replaceNext());
        buttonPanel.add(replaceButton);
        
        JButton replaceAllButton = new JButton("Replace All");
        replaceAllButton.addActionListener(e -> replaceAll());
        buttonPanel.add(replaceAllButton);
        
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            clearHighlights();
            replaceDialog.setVisible(false);
        });
        buttonPanel.add(closeButton);

        gbc.gridx = 0; gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);
        
        // Enter key support
        searchField.addActionListener(e -> findNext());
        replaceField.addActionListener(e -> replaceNext());
        
        // Escape key support
        replaceDialog.getRootPane().registerKeyboardAction(
            e -> {
                clearHighlights();
                replaceDialog.setVisible(false);
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Window close listener
        replaceDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                clearHighlights();
            }
        });
        
        replaceDialog.add(panel);
        replaceDialog.pack();
        replaceDialog.setLocationRelativeTo(parentComponent);
    }
    
    /**
     * Finds the next occurrence of the search term.
     */
    private void findNext() {
        String searchText = searchField.getText();
        if (searchText.isEmpty()) {
            return;
        }

        // Record position before find for navigation history
        NavigationHistory.Position beforePos = (textEditor != null)
            ? textEditor.getCurrentPosition() : null;

        SearchContext context = createSearchContext(searchText, true);
        SearchResult result = SearchEngine.find(textArea, context);

        if (result.wasFound()) {
            highlightsActive = true;
            recordNavigationIfLineChanged(beforePos);
        } else {
            showNotFoundMessage();
        }
    }
    
    /**
     * Finds the previous occurrence of the search term.
     */
    private void findPrevious() {
        String searchText = searchField.getText();
        if (searchText.isEmpty()) {
            return;
        }

        // Record position before find for navigation history
        NavigationHistory.Position beforePos = (textEditor != null)
            ? textEditor.getCurrentPosition() : null;

        SearchContext context = createSearchContext(searchText, false);
        SearchResult result = SearchEngine.find(textArea, context);

        if (result.wasFound()) {
            highlightsActive = true;
            recordNavigationIfLineChanged(beforePos);
        } else {
            showNotFoundMessage();
        }
    }
    
    /**
     * Replaces the current selection and finds the next occurrence.
     */
    private void replaceNext() {
        String searchText = searchField.getText();
        String replaceText = replaceField.getText();
        
        if (searchText.isEmpty()) {
            return;
        }
        
        SearchContext context = createSearchContext(searchText, true);
        context.setReplaceWith(replaceText);
        SearchResult result = SearchEngine.replace(textArea, context);
        
        if (!result.wasFound()) {
            showNotFoundMessage();
        }
    }
    
    /**
     * Replaces all occurrences of the search term.
     */
    private void replaceAll() {
        String searchText = searchField.getText();
        String replaceText = replaceField.getText();
        
        if (searchText.isEmpty()) {
            return;
        }
        
        SearchContext context = createSearchContext(searchText, true);
        context.setReplaceWith(replaceText);
        SearchResult result = SearchEngine.replaceAll(textArea, context);
        
        JOptionPane.showMessageDialog(replaceDialog,
            result.getCount() + " occurrence(s) replaced.",
            "Replace All Complete",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    /**
     * Creates a SearchContext with the current options.
     */
    private SearchContext createSearchContext(String searchText, boolean searchForward) {
        SearchContext context = new SearchContext();
        context.setSearchFor(searchText);
        context.setMatchCase(matchCaseCheckBox.isSelected());
        context.setRegularExpression(regexCheckBox.isSelected());
        context.setSearchForward(searchForward);
        context.setWholeWord(wholeWordCheckBox.isSelected());
        context.setSearchWrap(wrapAroundCheckBox.isSelected());
        return context;
    }
    
    /**
     * Shows a message when search text is not found.
     */
    private void showNotFoundMessage() {
        JOptionPane.showMessageDialog(
            findDialog != null && findDialog.isVisible() ? findDialog : replaceDialog,
            "Search text not found.",
            "Not Found",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    /**
     * Clears all search highlights in the text area.
     */
    private void clearHighlights() {
        if (highlightsActive) {
            textArea.clearMarkAllHighlights();
            highlightsActive = false;
        }
    }

    /**
     * Records a navigation jump if the current position is on a different line
     * than the position before the find operation.
     */
    private void recordNavigationIfLineChanged(NavigationHistory.Position beforePos) {
        if (textEditor == null || beforePos == null) {
            return;
        }

        NavigationHistory.Position afterPos = textEditor.getCurrentPosition();
        if (afterPos.line() != beforePos.line()) {
            textEditor.getNavigationHistory().recordJump(beforePos, afterPos);
        }
    }
}
