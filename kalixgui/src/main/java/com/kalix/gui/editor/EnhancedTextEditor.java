package com.kalix.gui.editor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced text editor component with professional code editor features.
 * Features include:
 * - Monospace font
 * - Line numbers (toggleable)
 * - Current line highlighting
 * - Better undo/redo system
 * - Dirty file tracking
 */
public class EnhancedTextEditor extends JPanel {
    
    private JTextPane textPane;
    private LineNumberPanel lineNumberPanel;
    private JScrollPane scrollPane;
    private UndoManager undoManager;
    
    // State tracking
    private boolean isDirty = false;
    private boolean showLineNumbers = true;
    private DirtyStateListener dirtyStateListener;
    
    // Styling
    private static final Color CURRENT_LINE_COLOR = new Color(232, 242, 254);
    private static final Color LINE_NUMBER_COLOR = new Color(128, 128, 128);
    private static final Color LINE_NUMBER_BACKGROUND = new Color(248, 248, 248);
    private static final Color SELECTION_HIGHLIGHT_COLOR = new Color(255, 255, 0, 100); // Semi-transparent yellow
    private static final Color BRACKET_HIGHLIGHT_COLOR = new Color(0, 150, 255, 100); // Semi-transparent blue
    
    // Selection highlighting
    private String lastSelectedText = "";
    private final List<Integer[]> selectionHighlights = new ArrayList<>();
    
    // Bracket matching
    private final List<Integer[]> bracketHighlights = new ArrayList<>();
    private static final String BRACKETS = "()[]{}";
    private static final String QUOTES = "\"'`";
    
    // Find and replace
    private String lastSearchTerm = "";
    private int lastFoundPosition = -1;
    
    public interface DirtyStateListener {
        void onDirtyStateChanged(boolean isDirty);
    }
    
    public EnhancedTextEditor() {
        initializeComponents();
        setupLayout();
        setupFont();
        setupKeyBindings();
        setupDocumentListener();
    }
    
    private void initializeComponents() {
        textPane = new JTextPane() {
            @Override
            protected void paintComponent(Graphics g) {
                // Highlight current line
                highlightCurrentLine(g);
                super.paintComponent(g);
                // Highlight selected text instances and brackets on top of text
                highlightSelections(g);
                highlightBrackets(g);
            }
        };
        
        lineNumberPanel = new LineNumberPanel(textPane);
        undoManager = new UndoManager();
        
        // Enable undo/redo tracking
        textPane.getDocument().addUndoableEditListener(undoManager);
        
        scrollPane = new JScrollPane(textPane);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create a panel to hold line numbers and text editor
        JPanel editorPanel = new JPanel(new BorderLayout());
        editorPanel.add(lineNumberPanel, BorderLayout.WEST);
        editorPanel.add(scrollPane, BorderLayout.CENTER);
        
        add(editorPanel, BorderLayout.CENTER);
        
        // Initially show line numbers
        lineNumberPanel.setVisible(showLineNumbers);
    }
    
    private void setupFont() {
        // Try to use high-quality monospace fonts, fallback to available ones
        String[] preferredFonts = {
            "JetBrains Mono", 
            "Fira Code", 
            "Source Code Pro",
            "Consolas",
            "Monaco",
            "Menlo",
            Font.MONOSPACED
        };
        
        Font chosenFont = null;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] availableFonts = ge.getAvailableFontFamilyNames();
        
        for (String fontName : preferredFonts) {
            if (Arrays.asList(availableFonts).contains(fontName) || fontName.equals(Font.MONOSPACED)) {
                chosenFont = new Font(fontName, Font.PLAIN, 14);
                break;
            }
        }
        
        if (chosenFont == null) {
            chosenFont = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        }
        
        textPane.setFont(chosenFont);
        lineNumberPanel.setFont(chosenFont);
    }
    
    private void setupKeyBindings() {
        InputMap inputMap = textPane.getInputMap();
        ActionMap actionMap = textPane.getActionMap();
        
        // Undo/Redo
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK), "undo");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.META_DOWN_MASK), "redo");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        
        actionMap.put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undo();
            }
        });
        
        actionMap.put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redo();
            }
        });
        
        // Toggle line numbers
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.META_DOWN_MASK), "toggleLineNumbers");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "toggleLineNumbers");
        
        actionMap.put("toggleLineNumbers", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleLineNumbers();
            }
        });
        
        // Go to line
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.META_DOWN_MASK), "goToLine");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "goToLine");
        
        actionMap.put("goToLine", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showGoToLineDialog();
            }
        });
        
        // Find
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK), "find");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find");
        
        actionMap.put("find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showFindDialog();
            }
        });
        
        // Find and Replace
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.META_DOWN_MASK), "findReplace");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "findReplace");
        
        actionMap.put("findReplace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showFindReplaceDialog();
            }
        });
    }
    
    private void setupDocumentListener() {
        textPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setDirty(true);
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) {
                setDirty(true);
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) {
                setDirty(true);
            }
        });
        
        // Add caret listener for current line highlighting and selection updates
        textPane.addCaretListener(e -> {
            updateSelectionHighlighting();
            updateBracketHighlighting();
            textPane.repaint();
            lineNumberPanel.repaint();
        });
    }
    
    private void highlightCurrentLine(Graphics g) {
        try {
            int caretPos = textPane.getCaretPosition();
            int lineStart = Utilities.getRowStart(textPane, caretPos);
            int lineEnd = Utilities.getRowEnd(textPane, caretPos);
            
            Rectangle startRect = textPane.modelToView(lineStart);
            Rectangle endRect = textPane.modelToView(lineEnd);
            
            if (startRect != null && endRect != null) {
                g.setColor(CURRENT_LINE_COLOR);
                g.fillRect(0, startRect.y, textPane.getWidth(), startRect.height);
            }
        } catch (BadLocationException ex) {
            // Ignore - just don't highlight
        }
    }
    
    private void highlightSelections(Graphics g) {
        g.setColor(SELECTION_HIGHLIGHT_COLOR);
        for (Integer[] range : selectionHighlights) {
            try {
                Rectangle startRect = textPane.modelToView(range[0]);
                Rectangle endRect = textPane.modelToView(range[1]);
                
                if (startRect != null && endRect != null) {
                    if (startRect.y == endRect.y) {
                        // Single line selection
                        g.fillRect(startRect.x, startRect.y, endRect.x - startRect.x, startRect.height);
                    } else {
                        // Multi-line selection - fill from start to end of first line
                        g.fillRect(startRect.x, startRect.y, textPane.getWidth() - startRect.x, startRect.height);
                        
                        // Fill complete lines in between
                        for (int y = startRect.y + startRect.height; y < endRect.y; y += startRect.height) {
                            g.fillRect(0, y, textPane.getWidth(), startRect.height);
                        }
                        
                        // Fill from start of last line to end position
                        g.fillRect(0, endRect.y, endRect.x, endRect.height);
                    }
                }
            } catch (BadLocationException ex) {
                // Skip this highlight
            }
        }
    }
    
    private void highlightBrackets(Graphics g) {
        g.setColor(BRACKET_HIGHLIGHT_COLOR);
        for (Integer[] range : bracketHighlights) {
            try {
                Rectangle startRect = textPane.modelToView(range[0]);
                Rectangle endRect = textPane.modelToView(range[1]);
                
                if (startRect != null && endRect != null) {
                    // Highlight individual brackets
                    g.fillRect(startRect.x, startRect.y, endRect.x - startRect.x, startRect.height);
                }
            } catch (BadLocationException ex) {
                // Skip this highlight
            }
        }
    }
    
    public void undo() {
        if (undoManager.canUndo()) {
            undoManager.undo();
        }
    }
    
    public void redo() {
        if (undoManager.canRedo()) {
            undoManager.redo();
        }
    }
    
    public boolean canUndo() {
        return undoManager.canUndo();
    }
    
    public boolean canRedo() {
        return undoManager.canRedo();
    }
    
    public void toggleLineNumbers() {
        showLineNumbers = !showLineNumbers;
        lineNumberPanel.setVisible(showLineNumbers);
        revalidate();
        repaint();
    }
    
    public boolean isShowingLineNumbers() {
        return showLineNumbers;
    }
    
    public void setDirty(boolean dirty) {
        if (this.isDirty != dirty) {
            this.isDirty = dirty;
            if (dirtyStateListener != null) {
                dirtyStateListener.onDirtyStateChanged(dirty);
            }
        }
    }
    
    public boolean isDirty() {
        return isDirty;
    }
    
    public void setDirtyStateListener(DirtyStateListener listener) {
        this.dirtyStateListener = listener;
    }
    
    public void setText(String text) {
        textPane.setText(text);
        textPane.setCaretPosition(0);
        setDirty(false);
        undoManager.discardAllEdits();
    }
    
    public String getText() {
        return textPane.getText();
    }
    
    public void cut() {
        textPane.cut();
    }
    
    public void copy() {
        textPane.copy();
    }
    
    public void paste() {
        textPane.paste();
    }
    
    public void selectAll() {
        textPane.selectAll();
    }
    
    private void updateSelectionHighlighting() {
        selectionHighlights.clear();
        
        String selectedText = textPane.getSelectedText();
        if (selectedText != null && !selectedText.trim().isEmpty() && selectedText.length() > 1) {
            // Only highlight if selection has changed to avoid performance issues
            if (!selectedText.equals(lastSelectedText)) {
                lastSelectedText = selectedText;
                
                String fullText = textPane.getText();
                String escapedText = Pattern.quote(selectedText);
                Pattern pattern = Pattern.compile(escapedText, Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(fullText);
                
                int currentSelectionStart = textPane.getSelectionStart();
                int currentSelectionEnd = textPane.getSelectionEnd();
                
                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();
                    
                    // Don't highlight the currently selected text
                    if (!(start == currentSelectionStart && end == currentSelectionEnd)) {
                        selectionHighlights.add(new Integer[]{start, end});
                    }
                }
            }
        } else {
            lastSelectedText = "";
        }
    }
    
    private void updateBracketHighlighting() {
        bracketHighlights.clear();
        
        int caretPos = textPane.getCaretPosition();
        String text = textPane.getText();
        
        if (text == null || text.isEmpty() || caretPos < 0 || caretPos >= text.length()) {
            return;
        }
        
        // Check character at caret position and previous position
        char currentChar = caretPos < text.length() ? text.charAt(caretPos) : '\0';
        char prevChar = caretPos > 0 ? text.charAt(caretPos - 1) : '\0';
        
        // Try to match bracket at current position
        int matchPos = findMatchingBracket(text, caretPos, currentChar, true);
        if (matchPos != -1) {
            bracketHighlights.add(new Integer[]{caretPos, caretPos + 1});
            bracketHighlights.add(new Integer[]{matchPos, matchPos + 1});
            return;
        }
        
        // Try to match bracket at previous position
        if (caretPos > 0) {
            matchPos = findMatchingBracket(text, caretPos - 1, prevChar, true);
            if (matchPos != -1) {
                bracketHighlights.add(new Integer[]{caretPos - 1, caretPos});
                bracketHighlights.add(new Integer[]{matchPos, matchPos + 1});
                return;
            }
        }
        
        // Try to match quotes
        matchPos = findMatchingQuote(text, caretPos, currentChar);
        if (matchPos != -1) {
            bracketHighlights.add(new Integer[]{caretPos, caretPos + 1});
            bracketHighlights.add(new Integer[]{matchPos, matchPos + 1});
            return;
        }
        
        if (caretPos > 0) {
            matchPos = findMatchingQuote(text, caretPos - 1, prevChar);
            if (matchPos != -1) {
                bracketHighlights.add(new Integer[]{caretPos - 1, caretPos});
                bracketHighlights.add(new Integer[]{matchPos, matchPos + 1});
            }
        }
    }
    
    private int findMatchingBracket(String text, int pos, char bracket, boolean forward) {
        if (BRACKETS.indexOf(bracket) == -1) {
            return -1;
        }
        
        char openBracket, closeBracket;
        int direction;
        int startPos;
        
        switch (bracket) {
            case '(':
                openBracket = '('; closeBracket = ')'; direction = 1; startPos = pos + 1;
                break;
            case ')':
                openBracket = '('; closeBracket = ')'; direction = -1; startPos = pos - 1;
                break;
            case '[':
                openBracket = '['; closeBracket = ']'; direction = 1; startPos = pos + 1;
                break;
            case ']':
                openBracket = '['; closeBracket = ']'; direction = -1; startPos = pos - 1;
                break;
            case '{':
                openBracket = '{'; closeBracket = '}'; direction = 1; startPos = pos + 1;
                break;
            case '}':
                openBracket = '{'; closeBracket = '}'; direction = -1; startPos = pos - 1;
                break;
            default:
                return -1;
        }
        
        int count = 1;
        for (int i = startPos; i >= 0 && i < text.length(); i += direction) {
            char c = text.charAt(i);
            if (c == openBracket) {
                count += direction;
            } else if (c == closeBracket) {
                count -= direction;
            }
            
            if (count == 0) {
                return i;
            }
        }
        
        return -1;
    }
    
    private int findMatchingQuote(String text, int pos, char quote) {
        if (QUOTES.indexOf(quote) == -1) {
            return -1;
        }
        
        // Look backward first to find opening quote
        for (int i = pos - 1; i >= 0; i--) {
            if (text.charAt(i) == quote) {
                // Found opening quote, now look forward for closing quote
                for (int j = pos + 1; j < text.length(); j++) {
                    if (text.charAt(j) == quote) {
                        return j;
                    }
                }
                return -1;
            }
        }
        
        // Look forward for closing quote
        for (int i = pos + 1; i < text.length(); i++) {
            if (text.charAt(i) == quote) {
                return i;
            }
        }
        
        return -1;
    }
    
    private void showGoToLineDialog() {
        // Get total line count
        int totalLines = getLineCount();
        int currentLine = getCurrentLineNumber();
        
        String input = JOptionPane.showInputDialog(
            this,
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
                        this,
                        String.format("Line number must be between 1 and %d", totalLines),
                        "Invalid Line Number",
                        JOptionPane.WARNING_MESSAGE
                    );
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(
                    this,
                    "Please enter a valid line number",
                    "Invalid Input",
                    JOptionPane.WARNING_MESSAGE
                );
            }
        }
    }
    
    private void goToLine(int lineNumber) {
        try {
            String text = textPane.getText();
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
            textPane.setCaretPosition(position);
            
            // Try to find the end of the line for selection
            int endPosition = position;
            for (int i = position; i < text.length() && text.charAt(i) != '\n'; i++) {
                endPosition = i + 1;
            }
            
            // Select the entire line
            textPane.select(position, endPosition);
            
            // Ensure the line is visible
            textPane.requestFocusInWindow();
            
        } catch (Exception e) {
            // Fallback - just set caret to start
            textPane.setCaretPosition(0);
        }
    }
    
    private int getLineCount() {
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
    
    private void showFindDialog() {
        JDialog findDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this), "Find", true);
        findDialog.setSize(400, 150);
        findDialog.setLocationRelativeTo(this);
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
    
    private void showFindReplaceDialog() {
        JDialog replaceDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this), "Find and Replace", true);
        replaceDialog.setSize(450, 200);
        replaceDialog.setLocationRelativeTo(this);
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
    
    private void findNext(String searchTerm, boolean caseSensitive) {
        String text = textPane.getText();
        String searchText = caseSensitive ? text : text.toLowerCase();
        String term = caseSensitive ? searchTerm : searchTerm.toLowerCase();
        
        int startPos = Math.max(0, textPane.getCaretPosition());
        int foundPos = searchText.indexOf(term, startPos);
        
        if (foundPos == -1) {
            // Wrap around to beginning
            foundPos = searchText.indexOf(term, 0);
        }
        
        if (foundPos != -1) {
            textPane.setCaretPosition(foundPos);
            textPane.select(foundPos, foundPos + searchTerm.length());
            lastFoundPosition = foundPos;
        } else {
            JOptionPane.showMessageDialog(this, 
                "Search term not found: " + searchTerm, 
                "Find", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void findPrevious(String searchTerm, boolean caseSensitive) {
        String text = textPane.getText();
        String searchText = caseSensitive ? text : text.toLowerCase();
        String term = caseSensitive ? searchTerm : searchTerm.toLowerCase();
        
        int startPos = Math.min(text.length() - 1, textPane.getCaretPosition() - 1);
        int foundPos = searchText.lastIndexOf(term, startPos);
        
        if (foundPos == -1) {
            // Wrap around to end
            foundPos = searchText.lastIndexOf(term);
        }
        
        if (foundPos != -1) {
            textPane.setCaretPosition(foundPos);
            textPane.select(foundPos, foundPos + searchTerm.length());
            lastFoundPosition = foundPos;
        } else {
            JOptionPane.showMessageDialog(this,
                "Search term not found: " + searchTerm,
                "Find", JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    private void replaceNext(String searchTerm, String replaceTerm, boolean caseSensitive) {
        String selectedText = textPane.getSelectedText();
        
        // Check if current selection matches search term
        if (selectedText != null) {
            boolean matches = caseSensitive ? 
                selectedText.equals(searchTerm) : 
                selectedText.equalsIgnoreCase(searchTerm);
            
            if (matches) {
                // Replace current selection
                textPane.replaceSelection(replaceTerm);
            }
        }
        
        // Find next occurrence
        findNext(searchTerm, caseSensitive);
    }
    
    private int replaceAll(String searchTerm, String replaceTerm, boolean caseSensitive) {
        String text = textPane.getText();
        String result;
        int count = 0;
        
        if (caseSensitive) {
            result = text.replace(searchTerm, replaceTerm);
            // Count occurrences
            int index = 0;
            while ((index = text.indexOf(searchTerm, index)) != -1) {
                count++;
                index += searchTerm.length();
            }
        } else {
            // Case insensitive replace
            Pattern pattern = Pattern.compile(Pattern.quote(searchTerm), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            result = matcher.replaceAll(replaceTerm);
            
            // Count matches
            matcher.reset();
            while (matcher.find()) {
                count++;
            }
        }
        
        if (count > 0) {
            textPane.setText(result);
            textPane.setCaretPosition(0);
        }
        
        return count;
    }
    
    private int getCurrentLineNumber() {
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
    
    public JTextPane getTextPane() {
        return textPane;
    }
}