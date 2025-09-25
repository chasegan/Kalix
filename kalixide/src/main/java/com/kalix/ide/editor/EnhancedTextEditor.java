package com.kalix.ide.editor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Simplified enhanced text editor component with professional code editor features.
 * Features include:
 * - Better undo/redo system
 * - Dirty file tracking
 * - Search and replace functionality (via TextSearchManager)
 * - Go to line functionality (via TextNavigationManager)
 * - File drag and drop (via FileDropManager)
 */
public class EnhancedTextEditor extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedTextEditor.class);
    
    private RSyntaxTextArea textArea;
    private RTextScrollPane scrollPane;
    private UndoManager undoManager;
    
    // State tracking
    private boolean isDirty = false;
    private DirtyStateListener dirtyStateListener;
    private FileDropManager.FileDropHandler fileDropHandler;
    private boolean programmaticUpdate = false; // Flag to prevent dirty marking during programmatic text changes
    
    // External document listeners
    private final java.util.List<DocumentListener> externalDocumentListeners = new java.util.ArrayList<>();
    
    // Manager instances
    private TextNavigationManager navigationManager;
    private TextSearchManager searchManager;
    private FileDropManager dropManager;
    
    public interface DirtyStateListener {
        void onDirtyStateChanged(boolean isDirty);
    }
    
    public EnhancedTextEditor() {
        initializeComponents();
        setupLayout();
        initializeManagers();
        setupKeyBindings();
        setupDocumentListener();
        setupDragAndDrop();
    }
    
    private void initializeComponents() {
        undoManager = new UndoManager();
        
        textArea = new RSyntaxTextArea();
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI); // INI syntax highlighting
        textArea.setLineWrap(false); // Disable line wrapping
        textArea.setWrapStyleWord(false);
        
        // Enable bracket matching
        textArea.setBracketMatchingEnabled(true);
        
        // Apply theme-aware colors
        updateThemeColors();
        
        // Enable mark occurrences
        textArea.setMarkOccurrences(true);
        textArea.setMarkOccurrencesDelay(300); // 300ms delay before highlighting
        
        // Enable undo/redo tracking
        textArea.getDocument().addUndoableEditListener(undoManager);
        
        scrollPane = new RTextScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);
    }
    
    /**
     * Initializes the manager instances.
     */
    private void initializeManagers() {
        navigationManager = new TextNavigationManager(textArea, this);
        searchManager = new TextSearchManager(textArea, this);
        dropManager = new FileDropManager(file -> {
            if (fileDropHandler != null) {
                fileDropHandler.onFileDropped(file);
            }
        });
    }
    
    private void setupKeyBindings() {
        InputMap inputMap = textArea.getInputMap();
        ActionMap actionMap = textArea.getActionMap();
        
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
                navigationManager.showGoToLineDialog();
            }
        });
        
        // Find
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK), "find");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "find");
        
        actionMap.put("find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.showFindDialog();
            }
        });
        
        // Find and Replace
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.META_DOWN_MASK), "replace");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "replace");
        
        actionMap.put("replace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.showFindReplaceDialog();
            }
        });
    }
    
    private void setupDocumentListener() {
        // Set up document change listener for dirty tracking and external listeners
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    setDirty(true);
                    notifyExternalListeners((listener, event) -> listener.insertUpdate(event), e);
                }
            }
            
            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    setDirty(true);
                    notifyExternalListeners((listener, event) -> listener.removeUpdate(event), e);
                }
            }
            
            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    setDirty(true);
                    notifyExternalListeners((listener, event) -> listener.changedUpdate(event), e);
                }
            }
        });
    }
    
    /**
     * Sets up drag and drop functionality for the text editor.
     */
    private void setupDragAndDrop() {
        // Use the FileDropManager to handle drag and drop for both components
        dropManager.setupDragAndDrop(this, textArea);
    }
    
    // Core functionality methods
    
    public boolean canUndo() {
        return undoManager.canUndo();
    }
    
    public boolean canRedo() {
        return undoManager.canRedo();
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
    
    public void setText(String text) {
        programmaticUpdate = true;
        try {
            textArea.setText(text);
            textArea.setCaretPosition(0);
            setDirty(false);
            undoManager.discardAllEdits();
        } finally {
            programmaticUpdate = false;
        }
    }
    
    public String getText() {
        return textArea.getText();
    }
    
    public void cut() {
        textArea.cut();
    }
    
    public void copy() {
        textArea.copy();
    }
    
    public void paste() {
        textArea.paste();
    }
    
    public void selectAll() {
        textArea.selectAll();
    }
    
    
    // Dirty state management
    public boolean isDirty() {
        return isDirty;
    }
    
    public void setDirty(boolean dirty) {
        if (this.isDirty != dirty) {
            this.isDirty = dirty;
            if (dirtyStateListener != null) {
                dirtyStateListener.onDirtyStateChanged(dirty);
            }
        }
    }
    
    public void setDirtyStateListener(DirtyStateListener listener) {
        this.dirtyStateListener = listener;
    }
    
    public RSyntaxTextArea getTextArea() {
        return textArea;
    }
    
    /**
     * Sets the handler for file drop events.
     * @param handler The handler to call when files are dropped
     */
    public void setFileDropHandler(FileDropManager.FileDropHandler handler) {
        this.fileDropHandler = handler;
    }
    
    // Manager access methods (if needed)
    
    /**
     * Add an external document listener to be notified of text changes.
     */
    public void addDocumentListener(DocumentListener listener) {
        externalDocumentListeners.add(listener);
    }
    
    /**
     * Remove an external document listener.
     */
    public void removeDocumentListener(DocumentListener listener) {
        externalDocumentListeners.remove(listener);
    }
    
    /**
     * Notify external document listeners of changes.
     */
    private void notifyExternalListeners(java.util.function.BiConsumer<DocumentListener, DocumentEvent> method, DocumentEvent e) {
        for (DocumentListener listener : externalDocumentListeners) {
            try {
                method.accept(listener, e);
            } catch (Exception ex) {
                logger.warn("Error in document listener: {}", ex.getMessage());
            }
        }
    }
    
    public TextNavigationManager getNavigationManager() {
        return navigationManager;
    }
    
    public TextSearchManager getSearchManager() {
        return searchManager;
    }
    
    public FileDropManager getDropManager() {
        return dropManager;
    }
    
    /**
     * Updates the text editor colors based on the current UI theme.
     * This method should be called when the theme changes.
     */
    public void updateThemeColors() {
        if (textArea == null) {
            return;
        }
        
        // Apply background and foreground colors from current theme
        Color bgColor = UIManager.getColor("TextArea.background");
        Color fgColor = UIManager.getColor("TextArea.foreground");
        Color selectionBgColor = UIManager.getColor("TextArea.selectionBackground");
        
        if (bgColor != null) {
            textArea.setBackground(bgColor);
        }
        
        if (fgColor != null) {
            textArea.setForeground(fgColor);
        }
        
        // Set current line highlight color based on theme
        if (selectionBgColor != null) {
            // Create a more subtle version of the selection color for line highlight
            int alpha = 50; // Much more subtle than selection
            Color lineHighlightColor = new Color(
                selectionBgColor.getRed(),
                selectionBgColor.getGreen(), 
                selectionBgColor.getBlue(),
                alpha
            );
            textArea.setCurrentLineHighlightColor(lineHighlightColor);
        } else {
            // Fallback: determine if dark theme and set appropriate color
            if (isDarkTheme()) {
                textArea.setCurrentLineHighlightColor(new Color(255, 255, 255, 25)); // Light highlight for dark theme
            } else {
                textArea.setCurrentLineHighlightColor(new Color(0, 0, 0, 25)); // Dark highlight for light theme
            }
        }
        
        // Enable current line highlighting
        textArea.setHighlightCurrentLine(true);
        
        textArea.repaint();
    }
    
    /**
     * Determines if the current theme is dark based on the background color.
     * @return true if dark theme, false if light theme
     */
    private boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        // Consider theme dark if the sum of RGB values is less than 384 (128 * 3)
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) < 384;
    }
}