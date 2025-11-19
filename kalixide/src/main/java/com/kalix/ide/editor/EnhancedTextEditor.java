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
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.kalix.ide.linter.LinterManager;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.factories.LinterComponentFactory;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.managers.FontManager;

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

    // Custom syntax style for Kalix INI with line continuation
    private static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    // Static block to register custom TokenMaker
    static {
        registerCustomTokenMaker();
    }

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
    private LinterManager linterManager;
    private com.kalix.ide.editor.commands.ContextCommandManager contextCommandManager;
    
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
        textArea.setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI); // Test simplified custom TokenMaker
        textArea.setLineWrap(false); // Disable line wrapping
        textArea.setWrapStyleWord(false);

        // CRITICAL: Set monospace font to prevent cursor position mismatch
        // Without this, proportional fonts cause cursor to appear ahead of actual typing position
        configureMonospaceFont();

        // Enable bracket matching
        textArea.setBracketMatchingEnabled(true);

        // Apply theme-aware colors
        updateThemeColors();

        // Apply saved syntax theme
        applySavedSyntaxTheme();
        
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
     * Configures a monospace font for the text area.
     * This is critical to prevent cursor position misalignment issues where the cursor
     * appears ahead of the actual typing position due to proportional font usage.
     *
     * Uses the embedded JetBrains Mono font with automatic fallback to system fonts.
     */
    private void configureMonospaceFont() {
        // Get the saved font size from preferences (default: 12pt)
        int fontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12);

        // Use FontManager to get the best available monospace font
        // This will use embedded JetBrains Mono if available, otherwise fall back to system fonts
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        textArea.setFont(monoFont);
    }

    /**
     * Updates the font size of the text editor.
     * This method can be called at runtime to change the font size dynamically.
     *
     * @param fontSize The new font size in points
     */
    public void updateFontSize(int fontSize) {
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        textArea.setFont(monoFont);
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

    /**
     * Initialize the linter manager with the schema manager.
     * This should be called after the EnhancedTextEditor is created.
     */
    public void initializeLinter(SchemaManager schemaManager) {
        if (linterManager != null) {
            linterManager.dispose();
        }
        linterManager = LinterComponentFactory.createLinterManager(textArea, schemaManager);
    }

    /**
     * Initialize the context command system.
     * This enables context-aware commands like rename node.
     *
     * @param parentFrame   Parent frame for dialogs
     * @param modelSupplier Supplier for the current parsed model
     */
    public void initializeContextCommands(JFrame parentFrame,
                                          java.util.function.Supplier<com.kalix.ide.linter.parsing.INIModelParser.ParsedModel> modelSupplier) {
        contextCommandManager = new com.kalix.ide.editor.commands.ContextCommandManager(
            textArea, parentFrame, modelSupplier);
        contextCommandManager.initialize();

        // Setup custom popup menu with context commands
        setupContextMenu();
    }

    /**
     * Sets up the right-click context menu with context-aware commands.
     */
    private void setupContextMenu() {
        textArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            private void showContextMenu(MouseEvent e) {
                // Build a fresh menu each time
                JPopupMenu menu = createContextMenu();

                // Show menu at click location
                menu.show(e.getComponent(), e.getX(), e.getY());
            }

            /**
             * Factory method that creates a fresh context menu with standard actions
             * and context-aware commands.
             */
            private JPopupMenu createContextMenu() {
                JPopupMenu menu = new JPopupMenu();

                // Add standard editing actions
                menu.add(createMenuItem("Undo", textArea.getAction(org.fife.ui.rtextarea.RTextArea.UNDO_ACTION)));
                menu.add(createMenuItem("Redo", textArea.getAction(org.fife.ui.rtextarea.RTextArea.REDO_ACTION)));
                menu.addSeparator();
                menu.add(createMenuItem("Cut", textArea.getAction(org.fife.ui.rtextarea.RTextArea.CUT_ACTION)));
                menu.add(createMenuItem("Copy", textArea.getAction(org.fife.ui.rtextarea.RTextArea.COPY_ACTION)));
                menu.add(createMenuItem("Paste", textArea.getAction(org.fife.ui.rtextarea.RTextArea.PASTE_ACTION)));
                menu.add(createMenuItem("Delete", textArea.getAction(org.fife.ui.rtextarea.RTextArea.DELETE_ACTION)));
                menu.addSeparator();
                menu.add(createMenuItem("Select All", textArea.getAction(org.fife.ui.rtextarea.RTextArea.SELECT_ALL_ACTION)));

                // Add context-aware commands if available
                if (contextCommandManager != null) {
                    java.util.List<com.kalix.ide.editor.commands.EditorCommand> commands =
                        contextCommandManager.getApplicableCommands();

                    if (!commands.isEmpty()) {
                        menu.addSeparator();

                        // Group commands by category
                        java.util.Map<String, java.util.List<com.kalix.ide.editor.commands.EditorCommand>> commandsByCategory =
                            new java.util.LinkedHashMap<>();

                        for (com.kalix.ide.editor.commands.EditorCommand command : commands) {
                            String category = command.getMetadata().getCategory();
                            commandsByCategory.computeIfAbsent(category, k -> new java.util.ArrayList<>()).add(command);
                        }

                        // Add menu items grouped by category
                        for (java.util.Map.Entry<String, java.util.List<com.kalix.ide.editor.commands.EditorCommand>> entry : commandsByCategory.entrySet()) {
                            String category = entry.getKey();
                            java.util.List<com.kalix.ide.editor.commands.EditorCommand> categoryCommands = entry.getValue();

                            if (!category.isEmpty()) {
                                // Commands with category - create submenu
                                JMenu submenu = new JMenu(category);
                                for (com.kalix.ide.editor.commands.EditorCommand command : categoryCommands) {
                                    JMenuItem item = new JMenuItem(command.getMetadata().getDisplayName());
                                    item.addActionListener(ae -> contextCommandManager.executeCommand(command));
                                    submenu.add(item);
                                }
                                menu.add(submenu);
                            } else {
                                // Commands with no category - add directly
                                for (com.kalix.ide.editor.commands.EditorCommand command : categoryCommands) {
                                    JMenuItem item = new JMenuItem(command.getMetadata().getDisplayName());
                                    item.addActionListener(ae -> contextCommandManager.executeCommand(command));
                                    menu.add(item);
                                }
                            }
                        }
                    }
                }

                return menu;
            }

            /**
             * Helper to create a menu item from an action.
             */
            private JMenuItem createMenuItem(String name, Action action) {
                JMenuItem item = new JMenuItem(name);
                if (action != null) {
                    item.addActionListener(action);
                    item.setEnabled(action.isEnabled());
                }
                return item;
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

        // Toggle Comment
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.META_DOWN_MASK), "toggleComment");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK), "toggleComment");

        actionMap.put("toggleComment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleComment();
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

    /**
     * Toggles comments on the current line or all lines in the selection.
     * Uses "#" as the comment character. If a line starts with "#" (after whitespace),
     * it removes the comment. Otherwise, it adds a comment.
     */
    public void toggleComment() {
        try {
            int selectionStart = textArea.getSelectionStart();
            int selectionEnd = textArea.getSelectionEnd();

            // Get line numbers for start and end of selection
            int startLine = textArea.getLineOfOffset(selectionStart);
            int endLine = textArea.getLineOfOffset(selectionEnd);

            // If selection ends at the start of a line, don't include that line
            if (selectionEnd > selectionStart && selectionEnd == textArea.getLineStartOffset(endLine)) {
                endLine--;
            }

            // Check if all lines are commented (to decide whether to comment or uncomment)
            boolean allCommented = true;
            for (int line = startLine; line <= endLine; line++) {
                int lineStart = textArea.getLineStartOffset(line);
                int lineEnd = textArea.getLineEndOffset(line);
                String lineText = textArea.getText(lineStart, lineEnd - lineStart);
                String trimmed = lineText.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    allCommented = false;
                    break;
                }
            }

            // Now toggle comments on all lines
            int newSelectionStart = selectionStart;
            int newSelectionEnd = selectionEnd;
            int offsetDelta = 0;

            for (int line = startLine; line <= endLine; line++) {
                int lineStart = textArea.getLineStartOffset(line);
                int lineEnd = textArea.getLineEndOffset(line);
                String lineText = textArea.getText(lineStart, lineEnd - lineStart);

                String newLine;
                int lineDelta = 0;

                if (allCommented) {
                    // Uncomment: remove "# " or "#" from the start (after whitespace)
                    int firstNonWhitespace = 0;
                    while (firstNonWhitespace < lineText.length() && Character.isWhitespace(lineText.charAt(firstNonWhitespace))) {
                        firstNonWhitespace++;
                    }

                    if (firstNonWhitespace < lineText.length() && lineText.charAt(firstNonWhitespace) == '#') {
                        String before = lineText.substring(0, firstNonWhitespace);
                        String after = lineText.substring(firstNonWhitespace + 1);

                        // Also remove the space after # if present
                        if (after.startsWith(" ")) {
                            after = after.substring(1);
                            lineDelta = -2;
                        } else {
                            lineDelta = -1;
                        }
                        newLine = before + after;
                    } else {
                        newLine = lineText;
                    }
                } else {
                    // Comment: add "# " at the start (after whitespace)
                    int firstNonWhitespace = 0;
                    while (firstNonWhitespace < lineText.length() && Character.isWhitespace(lineText.charAt(firstNonWhitespace))) {
                        firstNonWhitespace++;
                    }

                    String before = lineText.substring(0, firstNonWhitespace);
                    String after = lineText.substring(firstNonWhitespace);
                    newLine = before + "# " + after;
                    lineDelta = 2;
                }

                // Replace the line
                textArea.replaceRange(newLine, lineStart, lineEnd);

                // Update selection offsets
                if (lineStart <= selectionStart) {
                    newSelectionStart += lineDelta;
                }
                if (lineStart < selectionEnd) {
                    newSelectionEnd += lineDelta;
                }

                offsetDelta += lineDelta;
            }

            // Restore selection
            textArea.setSelectionStart(newSelectionStart);
            textArea.setSelectionEnd(newSelectionEnd);

        } catch (Exception ex) {
            logger.error("Error toggling comments", ex);
        }
    }

    /**
     * Normalizes all line endings in the document to Unix format (LF).
     * Converts all \r\n (Windows) and standalone \r (old Mac) to \n.
     * Marks the document as dirty if changes were made.
     */
    public void normalizeLineEndings() {
        try {
            String currentText = textArea.getText();

            // Check if normalization is needed
            if (!currentText.contains("\r")) {
                return; // Already normalized
            }

            // Replace all \r\n with \n, then remove any remaining \r
            String normalizedText = currentText.replace("\r\n", "\n").replace("\r", "\n");

            // Update text
            int caretPosition = textArea.getCaretPosition();
            programmaticUpdate = true;
            textArea.setText(normalizedText);
            programmaticUpdate = false;

            // Restore caret position (adjust if needed)
            int newCaretPosition = Math.min(caretPosition, normalizedText.length());
            textArea.setCaretPosition(newCaretPosition);

            // Mark as dirty since we modified the content
            setDirty(true);

        } catch (Exception ex) {
            logger.error("Error normalizing line endings", ex);
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
    
    public TextSearchManager getSearchManager() {
        return searchManager;
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
            int alpha = 80; // More visible for better navigation feedback
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

    // Linter integration methods

    /**
     * Get the linter manager for this editor.
     */
    public LinterManager getLinterManager() {
        return linterManager;
    }

    /**
     * Sets the base directory supplier for the linter to resolve relative file paths.
     * This should be set to the directory of the currently loaded model file.
     *
     * @param baseDirectorySupplier Supplier that returns the base directory (null if no file is loaded)
     */
    public void setLinterBaseDirectorySupplier(java.util.function.Supplier<java.io.File> baseDirectorySupplier) {
        if (linterManager != null) {
            linterManager.setBaseDirectorySupplier(baseDirectorySupplier);
        }
    }

    /**
     * Dispose of resources when the editor is no longer needed.
     */
    public void dispose() {
        if (linterManager != null) {
            linterManager.dispose();
            linterManager = null;
        }
    }

    /**
     * Register the custom TokenMaker for Kalix INI format with line continuation support.
     * This method is called once when the class is loaded.
     */
    private static void registerCustomTokenMaker() {
        try {
            AbstractTokenMakerFactory factory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
            factory.putMapping(SYNTAX_STYLE_KALIX_INI, "com.kalix.ide.editor.KalixIniTokenMaker");
        } catch (Exception e) {
            logger.error("Failed to register custom Kalix INI TokenMaker", e);
        }
    }

    /**
     * Applies the saved syntax theme from preferences on startup.
     * This ensures the syntax theme is loaded when the editor is first created.
     */
    private void applySavedSyntaxTheme() {
        try {
            // Get the saved syntax theme from preferences
            String savedThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            SyntaxTheme.Theme savedTheme = SyntaxTheme.getThemeByName(savedThemeName);

            // Apply the saved theme
            updateSyntaxTheme(savedTheme);

        } catch (Exception e) {
            logger.warn("Failed to apply saved syntax theme, using default: {}", e.getMessage());
            // Fallback to Light theme if anything goes wrong
            updateSyntaxTheme(SyntaxTheme.Theme.LIGHT);
        }
    }

    /**
     * Updates the syntax highlighting theme for the text editor.
     * This method applies custom colors for different token types based on the selected theme.
     *
     * @param syntaxTheme The syntax theme to apply
     */
    public void updateSyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        if (textArea == null) {
            return;
        }

        // For now, we'll apply syntax theme through RSyntaxTextArea's style mechanism
        // RSyntaxTextArea uses SyntaxScheme to define token colors
        org.fife.ui.rsyntaxtextarea.SyntaxScheme syntaxScheme = textArea.getSyntaxScheme();

        if (syntaxScheme != null) {
            // Apply colors from our SyntaxTheme to RSyntaxTextArea's token types
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.IDENTIFIER).foreground = syntaxTheme.getIdentifierColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.OPERATOR).foreground = syntaxTheme.getOperatorColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = syntaxTheme.getStringColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD).foreground = syntaxTheme.getReservedWordColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL).foreground = syntaxTheme.getCommentColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.WHITESPACE).foreground = syntaxTheme.getWhitespaceColor();

            // Refresh the text area to apply the new colors
            textArea.repaint();
        }
    }
}