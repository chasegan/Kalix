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
 *
 * <p>The Find and Find/Replace dialogs each own their own set of input fields (held in a
 * {@link SearchDialog} bundle) so they cannot alias each other's state, and both are
 * non-modal so the user can click into the editor to reposition the search origin while
 * a dialog is open.
 */
public class TextSearchManager {

    private final RSyntaxTextArea textArea;
    private final JComponent parentComponent;
    private final EnhancedTextEditor textEditor;

    // Dialogs (lazily created); each bundles its own fields so Find and Replace
    // cannot read each other's inputs.
    private SearchDialog findDialog;
    private SearchDialog replaceDialog;

    /**
     * One search dialog and the input fields that belong to it.
     */
    private static final class SearchDialog {
        final JDialog dialog;
        final JTextField searchField = new JTextField(20);
        final JTextField replaceField; // null for the find-only dialog
        final JCheckBox matchCaseCheckBox = new JCheckBox("Match case");
        final JCheckBox wholeWordCheckBox = new JCheckBox("Whole word");
        final JCheckBox regexCheckBox = new JCheckBox("Regular expression");
        final JCheckBox wrapAroundCheckBox = new JCheckBox("Wrap around");

        SearchDialog(JDialog dialog, boolean withReplaceField) {
            this.dialog = dialog;
            this.replaceField = withReplaceField ? new JTextField(20) : null;
            wrapAroundCheckBox.setSelected(true); // Default to true
        }
    }

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
            findDialog = createFindDialog();
        }
        showDialog(findDialog);
    }

    /**
     * Shows the find and replace dialog.
     */
    public void showFindReplaceDialog() {
        if (replaceDialog == null) {
            replaceDialog = createReplaceDialog();
        }
        showDialog(replaceDialog);
    }

    /**
     * Pre-populates the search field with the current selection (if any), then shows
     * the dialog and focuses the search field. The dialogs are non-modal, so
     * setVisible returns immediately and the focus request takes effect.
     */
    private void showDialog(SearchDialog sd) {
        String selectedText = textArea.getSelectedText();
        if (selectedText != null && !selectedText.isEmpty()) {
            sd.searchField.setText(selectedText);
        }
        sd.searchField.selectAll();
        sd.dialog.setVisible(true);
        sd.dialog.toFront();
        sd.searchField.requestFocusInWindow();
    }

    /**
     * Creates the find dialog.
     */
    private SearchDialog createFindDialog() {
        SearchDialog sd = new SearchDialog(newDialog("Find"), false);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        // Search field
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.searchField, gbc);

        // Options
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(sd.matchCaseCheckBox, gbc);

        gbc.gridy = 2;
        panel.add(sd.wholeWordCheckBox, gbc);

        gbc.gridy = 3;
        panel.add(sd.regexCheckBox, gbc);

        gbc.gridy = 4;
        panel.add(sd.wrapAroundCheckBox, gbc);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> findNext(sd));
        buttonPanel.add(findButton);

        JButton findPrevButton = new JButton("Find Previous");
        findPrevButton.addActionListener(e -> findPrevious(sd));
        buttonPanel.add(findPrevButton);

        buttonPanel.add(createCloseButton(sd));

        gbc.gridx = 0; gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);

        // Enter key support
        sd.searchField.addActionListener(e -> findNext(sd));

        finishDialog(sd, panel);
        return sd;
    }

    /**
     * Creates the find and replace dialog.
     */
    private SearchDialog createReplaceDialog() {
        SearchDialog sd = new SearchDialog(newDialog("Find and Replace"), true);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        // Search field
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(new JLabel("Find:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.searchField, gbc);

        // Replace field
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Replace:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(sd.replaceField, gbc);

        // Options
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(sd.matchCaseCheckBox, gbc);

        gbc.gridy = 3;
        panel.add(sd.wholeWordCheckBox, gbc);

        gbc.gridy = 4;
        panel.add(sd.regexCheckBox, gbc);

        gbc.gridy = 5;
        panel.add(sd.wrapAroundCheckBox, gbc);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton findButton = new JButton("Find Next");
        findButton.addActionListener(e -> findNext(sd));
        buttonPanel.add(findButton);

        JButton replaceButton = new JButton("Replace");
        replaceButton.addActionListener(e -> replaceNext(sd));
        buttonPanel.add(replaceButton);

        JButton replaceAllButton = new JButton("Replace All");
        replaceAllButton.addActionListener(e -> replaceAll(sd));
        buttonPanel.add(replaceAllButton);

        buttonPanel.add(createCloseButton(sd));

        gbc.gridx = 0; gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buttonPanel, gbc);

        // Enter key support
        sd.searchField.addActionListener(e -> findNext(sd));
        sd.replaceField.addActionListener(e -> replaceNext(sd));

        finishDialog(sd, panel);
        return sd;
    }

    /**
     * Creates a non-modal dialog owned by the parent window. Non-modal so the editor
     * stays clickable (to reposition the search origin) while the dialog is open.
     */
    private JDialog newDialog(String title) {
        Window window = SwingUtilities.getWindowAncestor(parentComponent);
        JDialog dialog = new JDialog(window instanceof Frame ? (Frame) window : null, title, false);
        dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        return dialog;
    }

    /** Creates the shared Close button behaviour: clear highlights, hide the dialog. */
    private JButton createCloseButton(SearchDialog sd) {
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            clearHighlights();
            sd.dialog.setVisible(false);
        });
        return closeButton;
    }

    /**
     * Applies the behaviour common to both dialogs: content, Escape-to-close,
     * clear-highlights-on-close, pack and position.
     */
    private void finishDialog(SearchDialog sd, JPanel panel) {
        // Escape key support
        sd.dialog.getRootPane().registerKeyboardAction(
            e -> {
                clearHighlights();
                sd.dialog.setVisible(false);
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Window close listener
        sd.dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                clearHighlights();
            }
        });

        sd.dialog.add(panel);
        sd.dialog.pack();
        sd.dialog.setLocationRelativeTo(parentComponent);
    }

    /**
     * Finds the next occurrence of the search term.
     */
    private void findNext(SearchDialog sd) {
        find(sd, true);
    }

    /**
     * Finds the previous occurrence of the search term.
     */
    private void findPrevious(SearchDialog sd) {
        find(sd, false);
    }

    private void find(SearchDialog sd, boolean forward) {
        String searchText = sd.searchField.getText();
        if (searchText.isEmpty()) {
            return;
        }

        // Record position before find for navigation history
        NavigationHistory.Position beforePos = (textEditor != null)
            ? textEditor.getCurrentPosition() : null;

        SearchContext context = createSearchContext(sd, forward);
        SearchResult result = SearchEngine.find(textArea, context);

        if (result.wasFound()) {
            recordNavigationIfLineChanged(beforePos);
        } else {
            showNotFoundMessage(sd);
        }
    }

    /**
     * Replaces the current selection and finds the next occurrence.
     */
    private void replaceNext(SearchDialog sd) {
        String searchText = sd.searchField.getText();
        if (searchText.isEmpty()) {
            return;
        }

        SearchContext context = createSearchContext(sd, true);
        context.setReplaceWith(sd.replaceField.getText());
        SearchResult result = SearchEngine.replace(textArea, context);

        if (!result.wasFound()) {
            showNotFoundMessage(sd);
        }
    }

    /**
     * Replaces all occurrences of the search term.
     */
    private void replaceAll(SearchDialog sd) {
        String searchText = sd.searchField.getText();
        if (searchText.isEmpty()) {
            return;
        }

        SearchContext context = createSearchContext(sd, true);
        context.setReplaceWith(sd.replaceField.getText());
        SearchResult result = SearchEngine.replaceAll(textArea, context);

        JOptionPane.showMessageDialog(sd.dialog,
            result.getCount() + " occurrence(s) replaced.",
            "Replace All Complete",
            JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Creates a SearchContext with the given dialog's current options.
     */
    private SearchContext createSearchContext(SearchDialog sd, boolean searchForward) {
        SearchContext context = new SearchContext();
        context.setSearchFor(sd.searchField.getText());
        context.setMatchCase(sd.matchCaseCheckBox.isSelected());
        context.setRegularExpression(sd.regexCheckBox.isSelected());
        context.setSearchForward(searchForward);
        context.setWholeWord(sd.wholeWordCheckBox.isSelected());
        context.setSearchWrap(sd.wrapAroundCheckBox.isSelected());
        return context;
    }

    /**
     * Shows a message when search text is not found.
     */
    private void showNotFoundMessage(SearchDialog sd) {
        JOptionPane.showMessageDialog(
            sd.dialog,
            "Search text not found.",
            "Not Found",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    /**
     * Clears all search highlights in the text area. Unconditional: replace
     * operations also produce mark-all highlights, so gating this on a flag set
     * only by the find path would leave stale highlights behind.
     */
    private void clearHighlights() {
        textArea.clearMarkAllHighlights();
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
