package com.kalix.gui.editor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.File;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced text editor component with professional code editor features.
 * Features include:
 * - Better undo/redo system
 * - Dirty file tracking
 */
public class EnhancedTextEditor extends JPanel {
    
    private JTextPane textPane;
    private JScrollPane scrollPane;
    private UndoManager undoManager;
    
    // State tracking
    private boolean isDirty = false;
    private boolean lineWrap = true;
    private DirtyStateListener dirtyStateListener;
    private FileDropHandler fileDropHandler;
    private boolean programmaticUpdate = false; // Flag to prevent dirty marking during programmatic text changes
    
    
    
    // Find and replace
    private String lastSearchTerm = "";
    private int lastFoundPosition = -1;
    
    public interface DirtyStateListener {
        void onDirtyStateChanged(boolean isDirty);
    }
    
    // Interface for handling file drops
    public interface FileDropHandler {
        void onFileDropped(File file);
    }
    
    public EnhancedTextEditor() {
        initializeComponents();
        setupLayout();
        setupKeyBindings();
        setupDocumentListener();
        setupDragAndDrop();
    }
    
    private void initializeComponents() {
        textPane = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return lineWrap;
            }
        };
        
        undoManager = new UndoManager();
        
        // Enable undo/redo tracking
        textPane.getDocument().addUndoableEditListener(undoManager);
        
        scrollPane = new JScrollPane(textPane);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        
        // Set default line wrap (enabled by default)
        updateLineWrap();
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        add(scrollPane, BorderLayout.CENTER);
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
                if (!programmaticUpdate) {
                    setDirty(true);
                }
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    setDirty(true);
                }
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    setDirty(true);
                }
            }
        });
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
    
    
    public void setLineWrap(boolean wrap) {
        this.lineWrap = wrap;
        updateLineWrap();
    }
    
    public boolean isLineWrap() {
        return lineWrap;
    }
    
    private void updateLineWrap() {
        // Update horizontal scroll policy based on wrap setting
        if (scrollPane != null) {
            scrollPane.setHorizontalScrollBarPolicy(
                lineWrap ? JScrollPane.HORIZONTAL_SCROLLBAR_NEVER : JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            );
        }
        
        // Force the text pane to recalculate its preferred width
        if (textPane != null) {
            textPane.invalidate();
        }
        
        // Force layout update to apply the new wrap setting
        revalidate();
        repaint();
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
        programmaticUpdate = true; // Disable dirty marking
        try {
            textPane.setText(text);
            textPane.setCaretPosition(0);
            setDirty(false);
            undoManager.discardAllEdits();
        } finally {
            programmaticUpdate = false; // Re-enable dirty marking
        }
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
    
    
    
    /**
     * Sets the handler for file drop events.
     * @param handler The handler to call when files are dropped
     */
    public void setFileDropHandler(FileDropHandler handler) {
        this.fileDropHandler = handler;
    }
    
    /**
     * Sets up drag and drop functionality for the text editor.
     */
    private void setupDragAndDrop() {
        // Create drop target for this panel
        new DropTarget(this, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                // Accept the drag
            }
            
            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                // Handle action change if needed
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                // Handle drag exit if needed
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    
                    Transferable transferable = dtde.getTransferable();
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files = (java.util.List<File>) 
                            transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        
                        if (!files.isEmpty()) {
                            File file = files.get(0); // Take the first file
                            String fileName = file.getName().toLowerCase();
                            
                            // Only accept .ini and .toml files
                            if (fileName.endsWith(".ini") || fileName.endsWith(".toml")) {
                                if (fileDropHandler != null) {
                                    fileDropHandler.onFileDropped(file);
                                }
                                dtde.dropComplete(true);
                            } else {
                                System.out.println("Rejected file: " + fileName + " (only .ini and .toml files are accepted)");
                                dtde.dropComplete(false);
                            }
                        }
                    } else {
                        dtde.dropComplete(false);
                    }
                } catch (Exception e) {
                    System.err.println("Error handling file drop: " + e.getMessage());
                    dtde.dropComplete(false);
                }
            }
        });
        
        // Also set up drop target on the text pane itself
        new DropTarget(textPane, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                // Accept the drag
            }
            
            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                // Handle action change if needed
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                // Handle drag exit if needed
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    
                    Transferable transferable = dtde.getTransferable();
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files = (java.util.List<File>) 
                            transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        
                        if (!files.isEmpty()) {
                            File file = files.get(0); // Take the first file
                            String fileName = file.getName().toLowerCase();
                            
                            // Only accept .ini and .toml files
                            if (fileName.endsWith(".ini") || fileName.endsWith(".toml")) {
                                if (fileDropHandler != null) {
                                    fileDropHandler.onFileDropped(file);
                                }
                                dtde.dropComplete(true);
                            } else {
                                System.out.println("Rejected file: " + fileName + " (only .ini and .toml files are accepted)");
                                dtde.dropComplete(false);
                            }
                        }
                    } else {
                        dtde.dropComplete(false);
                    }
                } catch (Exception e) {
                    System.err.println("Error handling file drop: " + e.getMessage());
                    dtde.dropComplete(false);
                }
            }
        });
    }
}