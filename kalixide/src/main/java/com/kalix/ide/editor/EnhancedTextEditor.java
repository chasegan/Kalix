package com.kalix.ide.editor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.linter.LinterManager;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.linter.factories.LinterComponentFactory;
import com.kalix.ide.themes.SyntaxTheme;

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

    private KalixIniTextArea textArea;
    private RTextScrollPane scrollPane;

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
    private AutoCompleteManager autoCompleteManager;
    private com.kalix.ide.editor.commands.ContextCommandManager contextCommandManager;

    // Context command dependencies (stored for programmatic rename access)
    private JFrame commandParentFrame;
    private java.util.function.Supplier<com.kalix.ide.linter.parsing.INIModelParser.ParsedModel> commandModelSupplier;

    // Map panel reference for "Show on Map" context menu action
    private com.kalix.ide.MapPanel mapPanel;
    
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
        // KalixIniTextArea handles font configuration, syntax highlighting, and Windows cursor fix
        textArea = new KalixIniTextArea();

        // Enable bracket matching
        textArea.setBracketMatchingEnabled(true);

        // Apply theme-aware colors
        updateThemeColors();

        scrollPane = new RTextScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Updates the font size of the text editor.
     * Delegates to the underlying KalixIniTextArea.
     *
     * @param fontSize The new font size in points
     */
    public void updateFontSize(int fontSize) {
        textArea.updateFontSize(fontSize);
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
     * Initialize the auto-complete system.
     * This should be called after the EnhancedTextEditor is created.
     *
     * @param schemaManager           Schema manager for node types and parameters
     * @param modelSupplier           Supplier for the current parsed model
     * @param baseDirectorySupplier   Supplier for the base directory to resolve relative input file paths
     */
    public void initializeAutoComplete(SchemaManager schemaManager,
                                       java.util.function.Supplier<com.kalix.ide.linter.parsing.INIModelParser.ParsedModel> modelSupplier,
                                       java.util.function.Supplier<java.io.File> baseDirectorySupplier) {
        if (autoCompleteManager != null) {
            autoCompleteManager.dispose();
        }
        autoCompleteManager = new AutoCompleteManager(textArea, schemaManager, modelSupplier, baseDirectorySupplier);
        autoCompleteManager.install();
    }

    /**
     * Programmatically triggers the auto-completion popup.
     * This is equivalent to pressing Ctrl+Space.
     */
    public void showSuggestions() {
        if (autoCompleteManager != null) {
            autoCompleteManager.showSuggestions();
        }
    }

    /**
     * Scrolls the editor to show the definition of the specified node.
     * Uses the parsed model to find the section start line, then positions
     * the node at 1/4 from the top of the viewport for good context.
     *
     * @param nodeName The name of the node to scroll to
     * @return true if the node was found and scrolled to, false otherwise
     */
    public boolean scrollToNode(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty() || commandModelSupplier == null) {
            return false;
        }

        try {
            // Use parsed model to find the node section
            com.kalix.ide.linter.parsing.INIModelParser.ParsedModel model = commandModelSupplier.get();
            if (model == null) {
                return false;
            }

            com.kalix.ide.linter.parsing.INIModelParser.Section section = model.getSections().get("node." + nodeName);
            if (section == null) {
                return false;
            }

            // Convert 1-based line number to document offset
            int lineNumber = section.getStartLine() - 1; // Convert to 0-based
            int offset = 0;
            String text = textArea.getText();
            String[] lines = text.split("\n", -1);

            for (int i = 0; i < lineNumber && i < lines.length; i++) {
                offset += lines[i].length() + 1; // +1 for newline
            }

            // Set caret position to the start of the node section
            textArea.setCaretPosition(offset);

            // Smart scroll: position the node at 1/4 from the top of the viewport
            if (textArea.getParent() instanceof javax.swing.JViewport viewport) {
                java.awt.Rectangle viewRect = viewport.getViewRect();
                java.awt.Rectangle caretRect = textArea.modelToView(offset);

                if (caretRect != null) {
                    int desiredY = caretRect.y - (viewRect.height / 4);
                    desiredY = Math.max(0, desiredY);
                    viewport.setViewPosition(new java.awt.Point(viewRect.x, desiredY));
                }
            }

            return true;
        } catch (javax.swing.text.BadLocationException e) {
            logger.error("Error scrolling to node {}: {}", nodeName, e.getMessage());
        }

        return false;
    }

    /**
     * Initialize the context command system.
     * This enables context-aware commands like rename node and plot input files.
     *
     * @param parentFrame       Parent frame for dialogs
     * @param modelSupplier     Supplier for the current parsed model
     * @param modelFileSupplier Supplier for the current model file
     */
    public void initializeContextCommands(JFrame parentFrame,
                                          java.util.function.Supplier<com.kalix.ide.linter.parsing.INIModelParser.ParsedModel> modelSupplier,
                                          java.util.function.Supplier<java.io.File> modelFileSupplier) {
        // Store for programmatic access (e.g., rename from map context menu)
        this.commandParentFrame = parentFrame;
        this.commandModelSupplier = modelSupplier;

        contextCommandManager = new com.kalix.ide.editor.commands.ContextCommandManager(
            textArea, parentFrame, modelSupplier, modelFileSupplier, this::applyAtomicReplacements);
        contextCommandManager.initialize();

        // Setup custom popup menu with context commands
        setupContextMenu();
    }

    /**
     * Sets the map panel reference for the "Show on Map" context menu action.
     *
     * @param mapPanel The map panel to navigate to
     */
    public void setMapPanel(com.kalix.ide.MapPanel mapPanel) {
        this.mapPanel = mapPanel;
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
                // Move caret to click position if not clicking within a selection
                int clickOffset = textArea.viewToModel2D(e.getPoint());
                int selStart = textArea.getSelectionStart();
                int selEnd = textArea.getSelectionEnd();

                boolean hasSelection = selStart != selEnd;
                boolean clickInSelection = hasSelection && clickOffset >= selStart && clickOffset <= selEnd;

                if (!clickInSelection) {
                    // Move caret to click position
                    textArea.setCaretPosition(clickOffset);
                }

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

                // Show Suggestions (auto-complete)
                // Tried event-based trigger, but couldn't get stable behaviour.
                // Resorted to using a timer delay to allow UI to reestablish focus
                // before launching autocomplete.
                if (autoCompleteManager != null) {
                    menu.addSeparator();
                    JMenuItem suggestionsItem = new JMenuItem("Show Suggestions (Ctrl+Space)");
                    suggestionsItem.addActionListener(ae -> {
                        javax.swing.Timer timer = new javax.swing.Timer(150, evt -> {
                            textArea.requestFocusInWindow();
                            showSuggestions();
                        });
                        timer.setRepeats(false);
                        timer.start();
                    });
                    menu.add(suggestionsItem);
                }

                // Add navigation items based on context
                if (commandModelSupplier != null) {
                    com.kalix.ide.editor.commands.ContextDetector contextDetector = new com.kalix.ide.editor.commands.ContextDetector();
                    com.kalix.ide.editor.commands.EditorContext ctx = contextDetector.detectContext(
                        textArea.getCaretPosition(), textArea.getText(),
                        textArea.getSelectedText(), commandModelSupplier.get());

                    boolean addedSeparator = false;

                    // "Go to Node Definition" if cursor is on a ds_X property
                    if (ctx.getPropertyKey().isPresent() && ctx.getPropertyValue().isPresent()) {
                        String propKey = ctx.getPropertyKey().get();
                        String propValue = ctx.getPropertyValue().get();
                        if (propKey.matches("ds_\\d+") && !propValue.isEmpty()) {
                            menu.addSeparator();
                            addedSeparator = true;
                            JMenuItem goToNodeItem = new JMenuItem("Go to Node Definition");
                            goToNodeItem.addActionListener(ae -> scrollToNode(propValue));
                            menu.add(goToNodeItem);
                        }
                    }

                    // "Show on Map" if cursor is in a node section
                    if (mapPanel != null && ctx.getNodeName().isPresent()) {
                        String nodeName = ctx.getNodeName().get();
                        if (!addedSeparator) {
                            menu.addSeparator();
                        }
                        JMenuItem showOnMapItem = new JMenuItem("Show on Map");
                        showOnMapItem.addActionListener(ae -> mapPanel.selectNodeFromEditor(nodeName));
                        menu.add(showOnMapItem);
                    }
                }

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
                                    // Customize display name for certain commands
                                    String displayName = command.getMetadata().getDisplayName();

                                    // For rename command, include the node name
                                    if ("rename_node".equals(command.getMetadata().getId())) {
                                        com.kalix.ide.editor.commands.EditorContext context = contextCommandManager.getCurrentContext();
                                        if (context.getNodeName().isPresent()) {
                                            displayName = "Rename " + context.getNodeName().get();
                                        }
                                    }

                                    JMenuItem item = new JMenuItem(displayName);
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

    /**
     * Rename a node programmatically (e.g., from the map context menu).
     * Prompts the user for a new name and updates all references.
     *
     * @param nodeName The current node name to rename
     * @return true if rename was successful, false if cancelled or failed
     */
    public boolean renameNode(String nodeName) {
        if (commandParentFrame == null || commandModelSupplier == null) {
            logger.warn("Context commands not initialized - cannot rename node");
            return false;
        }

        if (nodeName == null || nodeName.trim().isEmpty()) {
            return false;
        }

        // Prompt user for new name
        String newName = (String) javax.swing.JOptionPane.showInputDialog(
            commandParentFrame,
            "Enter new name for node '" + nodeName + "':",
            "Rename Node",
            javax.swing.JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            nodeName
        );

        if (newName == null || newName.trim().isEmpty() || newName.equals(nodeName)) {
            // User cancelled or entered same name
            return false;
        }

        final String trimmedNewName = newName.trim();

        // Get fresh parsed model
        com.kalix.ide.linter.parsing.INIModelParser.ParsedModel parsedModel = commandModelSupplier.get();
        if (parsedModel == null) {
            logger.error("Failed to parse model for rename");
            javax.swing.JOptionPane.showMessageDialog(
                commandParentFrame,
                "Failed to parse model",
                "Error",
                javax.swing.JOptionPane.ERROR_MESSAGE
            );
            return false;
        }

        // Create executor and perform rename
        com.kalix.ide.editor.commands.CommandExecutor executor =
            new com.kalix.ide.editor.commands.CommandExecutor(textArea, commandParentFrame, this::applyAtomicReplacements);

        boolean success = executor.renameNode(nodeName, trimmedNewName, parsedModel);

        if (success) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                javax.swing.JOptionPane.showMessageDialog(
                    commandParentFrame,
                    "Renamed '" + nodeName + "' to '" + trimmedNewName + "'",
                    "Done",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE
                );
            });
        }

        return success;
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
        return textArea.canUndo();
    }

    public boolean canRedo() {
        return textArea.canRedo();
    }
    
    public void undo() {
        if (textArea.canUndo()) {
            textArea.undoLastAction();
        }
    }

    public void redo() {
        if (textArea.canRedo()) {
            textArea.redoLastAction();
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
            textArea.discardAllEdits();
        } finally {
            programmaticUpdate = false;
        }
    }

    /**
     * Applies multiple text replacements as a single atomic undo operation.
     * Each replacement specifies a line number and the old/new text.
     *
     * @param replacements List of line-based text replacements
     */
    public void applyAtomicReplacements(java.util.List<LineReplacement> replacements) {
        if (replacements.isEmpty()) {
            return;
        }

        try {
            javax.swing.text.Document doc = textArea.getDocument();
            String[] lines = textArea.getText().split("\n", -1);

            // Start compound edit for atomic undo
            textArea.beginAtomicEdit();

            try {
                // Apply replacements in reverse order to maintain line positions
                // Sort by line number descending
                java.util.List<LineReplacement> sortedReplacements = new java.util.ArrayList<>(replacements);
                sortedReplacements.sort((a, b) -> Integer.compare(b.lineNumber, a.lineNumber));

                for (LineReplacement replacement : sortedReplacements) {
                    int lineIndex = replacement.lineNumber - 1; // Convert 1-based to 0-based

                    if (lineIndex >= 0 && lineIndex < lines.length) {
                        // Find the start position of this line in the document
                        int startPos = 0;
                        for (int i = 0; i < lineIndex; i++) {
                            startPos += lines[i].length() + 1; // +1 for newline
                        }

                        String originalLine = lines[lineIndex];
                        String newLine = originalLine.replace(replacement.oldText, replacement.newText);

                        // Replace the line content in the document
                        doc.remove(startPos, originalLine.length());
                        doc.insertString(startPos, newLine, null);

                        // Update our lines array for subsequent replacements
                        lines[lineIndex] = newLine;
                    }
                }

                setDirty(true);

            } finally {
                // End compound edit
                textArea.endAtomicEdit();
            }

        } catch (Exception e) {
            logger.error("Error applying atomic replacements", e);
        }
    }

    /**
     * Represents a line-based text replacement.
     */
    public static class LineReplacement {
        public final int lineNumber; // 1-based
        public final String oldText;
        public final String newText;

        public LineReplacement(int lineNumber, String oldText, String newText) {
            this.lineNumber = lineNumber;
            this.oldText = oldText;
            this.newText = newText;
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

        if (bgColor != null) {
            textArea.setBackground(bgColor);
        }

        if (fgColor != null) {
            textArea.setForeground(fgColor);
        }

        // Delegate line highlight color to KalixIniTextArea
        textArea.updateCurrentLineHighlight();

        textArea.repaint();
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
        if (autoCompleteManager != null) {
            autoCompleteManager.dispose();
            autoCompleteManager = null;
        }
        if (linterManager != null) {
            linterManager.dispose();
            linterManager = null;
        }
    }

    /**
     * Updates the syntax highlighting theme for the text editor.
     * Delegates to the underlying KalixIniTextArea.
     *
     * @param syntaxTheme The syntax theme to apply
     */
    public void updateSyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        if (textArea != null) {
            textArea.updateSyntaxTheme(syntaxTheme);
        }
    }
}