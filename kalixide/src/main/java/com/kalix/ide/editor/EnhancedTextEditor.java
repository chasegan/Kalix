package com.kalix.ide.editor;

import com.kalix.ide.constants.AppShortcut;
import com.kalix.ide.editor.commands.CommandExecutor;
import com.kalix.ide.linter.parsing.INIModelParser.ParsedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.icons.MenuIcons;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.Icon;
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
    // Snapshot of the content last considered "clean" (loaded or saved). Edits —
    // including undo/redo back to this exact content — are compared against it so the
    // dirty flag clears when the buffer matches what's on disk.
    private String cleanText = "";
    private DirtyStateListener dirtyStateListener;
    private FileDropManager.FileDropHandler fileDropHandler;
    private java.util.function.Supplier<java.io.File> modelFileSupplier; // this document's file, for relative drop paths
    private boolean programmaticUpdate = false; // Flag to prevent dirty marking during programmatic text changes
    
    // External document listeners
    private final java.util.List<DocumentListener> externalDocumentListeners = new java.util.ArrayList<>();
    
    // Manager instances
    private TextNavigationManager navigationManager;
    private TextSearchManager searchManager;
    private FileDropManager dropManager;
    private LinterManager linterManager;
    private AutoCompleteManager autoCompleteManager;
    private com.kalix.ide.linter.ui.PropertyHoverTooltipManager propertyHoverTooltipManager;
    private com.kalix.ide.editor.commands.ContextCommandManager contextCommandManager;
    private NavigationHistory navigationHistory;

    // Context command dependencies (stored for programmatic rename access)
    private JFrame commandParentFrame;
    private java.util.function.Supplier<ParsedModel> commandModelSupplier;

    // Map panel reference for "Show on Map" context menu action
    private com.kalix.ide.MapPanel mapPanel;

    // Track line before mouse click for navigation history
    private int lineBeforeMouseClick = -1;

    // Global (Toolkit-wide) mouse-press capture used for navigation history.
    // Held so dispose() can remove it: a global listener would otherwise pin
    // this editor's whole object graph after the document is closed.
    private java.awt.event.AWTEventListener navigationMouseCaptureListener;

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
        navigationHistory = new NavigationHistory();
        navigationHistory.setDocument(textArea.getDocument());
        dropManager = new FileDropManager(file -> {
            if (fileDropHandler != null) {
                fileDropHandler.onFileDropped(file);
            }
        });
        // A drag from the project tree inserts the dropped files' relative paths at the drop point.
        dropManager.setPathDropHandler(this::insertDroppedPaths);
        setupNavigationMouseListener();
    }

    /**
     * Sets up a mouse listener to track navigation jumps from mouse clicks.
     * Records navigation history when a click causes a line change.
     *
     * Uses AWTEventListener to capture caret position BEFORE the click moves it,
     * since regular MouseListener is called after the text component processes the event.
     */
    private void setupNavigationMouseListener() {
        // Use AWTEventListener to capture mouse events BEFORE they're processed.
        // The listener is stored so dispose() can remove it from the Toolkit.
        navigationMouseCaptureListener = event -> {
            if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                if (me.getSource() == textArea) {
                    // Capture current caret position before the click moves it
                    lineBeforeMouseClick = getLineNumberForOffset(textArea.getCaretPosition());
                }
            }
        };
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(
            navigationMouseCaptureListener, java.awt.AWTEvent.MOUSE_EVENT_MASK);

        // Regular mouse listener to check after the click is processed
        textArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                // Check if line changed after the click
                if (lineBeforeMouseClick >= 0) {
                    int lineAfterClick = getLineNumberForOffset(textArea.getCaretPosition());
                    if (lineAfterClick != lineBeforeMouseClick) {
                        // Line changed, record in navigation history
                        NavigationHistory.Position beforePos = new NavigationHistory.Position(
                            0, lineBeforeMouseClick); // offset not critical, line matters
                        NavigationHistory.Position afterPos = new NavigationHistory.Position(
                            textArea.getCaretPosition(), lineAfterClick);
                        navigationHistory.recordJump(beforePos, afterPos);
                    }
                }
                lineBeforeMouseClick = -1;
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
                                       java.util.function.Supplier<ParsedModel> modelSupplier,
                                       java.util.function.Supplier<java.io.File> baseDirectorySupplier) {
        if (autoCompleteManager != null) {
            autoCompleteManager.dispose();
        }
        autoCompleteManager = new AutoCompleteManager(textArea, schemaManager, modelSupplier, baseDirectorySupplier);
        autoCompleteManager.install();
    }

    /**
     * Initialize the property hover tooltip system.
     * Shows helpful tooltips when hovering over property keys.
     *
     * @param schemaManager Schema manager for parameter definitions
     * @param modelSupplier Supplier for the current parsed model
     */
    public void initializePropertyTooltips(SchemaManager schemaManager,
                                          java.util.function.Supplier<ParsedModel> modelSupplier) {
        if (propertyHoverTooltipManager != null) {
            propertyHoverTooltipManager.dispose();
        }
        if (linterManager != null) {
            propertyHoverTooltipManager = new com.kalix.ide.linter.ui.PropertyHoverTooltipManager(
                textArea, schemaManager, modelSupplier,
                line -> !linterManager.getIssuesForLine(line).isEmpty());
        }
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
     * Locates the section via the shared INI-section grammar, then positions
     * the node at 1/4 from the top of the viewport for good context.
     *
     * @param nodeName The name of the node to scroll to
     * @return true if the node was found and scrolled to, false otherwise
     */
    public boolean scrollToNode(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty()) {
            return false;
        }

        try {
            com.kalix.ide.model.NodeSectionLocator.NodeSection section =
                com.kalix.ide.model.NodeSectionLocator.find(textArea.getText(), nodeName);
            if (section == null) {
                return false;
            }

            int offset = section.start();
            int targetLine = getLineNumberForOffset(offset);

            // Record jump in navigation history
            NavigationHistory.Position currentPos = getCurrentPosition();
            NavigationHistory.Position newPos = new NavigationHistory.Position(offset, targetLine);
            navigationHistory.recordJump(currentPos, newPos);

            // Set caret position to the start of the node section
            textArea.setCaretPosition(offset);

            // Smart scroll: position the node at 1/4 from the top of the viewport
            if (textArea.getParent() instanceof javax.swing.JViewport viewport) {
                java.awt.Rectangle viewRect = viewport.getViewRect();
                java.awt.geom.Rectangle2D caretRect = textArea.modelToView2D(offset);

                if (caretRect != null) {
                    int desiredY = (int) caretRect.getY() - (viewRect.height / 4);
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
     * Gets the current caret position as a NavigationHistory.Position.
     */
    public NavigationHistory.Position getCurrentPosition() {
        int offset = textArea.getCaretPosition();
        int line = getLineNumberForOffset(offset);
        return new NavigationHistory.Position(offset, line);
    }

    /**
     * Gets the 0-based line number for a given offset.
     */
    private int getLineNumberForOffset(int offset) {
        try {
            return textArea.getLineOfOffset(offset);
        } catch (javax.swing.text.BadLocationException e) {
            return 0;
        }
    }

    /**
     * Gets the navigation history for this editor.
     */
    public NavigationHistory getNavigationHistory() {
        return navigationHistory;
    }

    /**
     * Navigates to a position, scrolling it into view.
     */
    public void navigateToPosition(NavigationHistory.Position position) {
        if (position == null) return;

        try {
            textArea.setCaretPosition(position.offset());

            // Smart scroll: position at 1/4 from the top of the viewport
            if (textArea.getParent() instanceof javax.swing.JViewport viewport) {
                java.awt.Rectangle viewRect = viewport.getViewRect();
                java.awt.geom.Rectangle2D caretRect = textArea.modelToView2D(position.offset());

                if (caretRect != null) {
                    int desiredY = (int) caretRect.getY() - (viewRect.height / 4);
                    desiredY = Math.max(0, desiredY);
                    viewport.setViewPosition(new java.awt.Point(viewRect.x, desiredY));
                }
            }
        } catch (javax.swing.text.BadLocationException e) {
            logger.error("Error navigating to position: {}", e.getMessage());
        }
    }

    /**
     * Goes back in navigation history.
     */
    public void navigateBack() {
        NavigationHistory.Position pos = navigationHistory.goBack();
        if (pos != null) {
            navigateToPosition(pos);
        }
    }

    /**
     * Goes forward in navigation history.
     */
    public void navigateForward() {
        NavigationHistory.Position pos = navigationHistory.goForward();
        if (pos != null) {
            navigateToPosition(pos);
        }
    }

    /**
     * Records a jump navigation from current position to a target line.
     * Should be called before actually moving the caret.
     *
     * @param targetOffset The offset being jumped to
     */
    public void recordNavigationJump(int targetOffset) {
        NavigationHistory.Position currentPos = getCurrentPosition();
        int targetLine = getLineNumberForOffset(targetOffset);
        NavigationHistory.Position newPos = new NavigationHistory.Position(targetOffset, targetLine);
        navigationHistory.recordJump(currentPos, newPos);
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
                                          java.util.function.Supplier<ParsedModel> modelSupplier,
                                          java.util.function.Supplier<java.io.File> modelFileSupplier) {
        // Store for programmatic access (e.g., rename from map context menu)
        this.commandParentFrame = parentFrame;
        this.commandModelSupplier = modelSupplier;
        this.modelFileSupplier = modelFileSupplier;

        contextCommandManager = new com.kalix.ide.editor.commands.ContextCommandManager(
            textArea, parentFrame, modelSupplier, modelFileSupplier, () -> this.mapPanel, this::applyAtomicReplacements);
        contextCommandManager.initialize();

        // Install key bindings for every command whose metadata declares a
        // keyboard shortcut. The metadata is the single source of truth: the
        // shortcut hint shown in the context menu and the actual binding both
        // derive from the same KeyStroke, so they cannot drift apart.
        contextCommandManager.installCommandShortcuts(textArea.getInputMap(), textArea.getActionMap());

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
                menu.add(createMenuItem("Cut", textArea.getAction(org.fife.ui.rtextarea.RTextArea.CUT_ACTION), MenuIcons.cut()));
                menu.add(createMenuItem("Copy", textArea.getAction(org.fife.ui.rtextarea.RTextArea.COPY_ACTION), MenuIcons.copy()));
                menu.add(createMenuItem("Paste", textArea.getAction(org.fife.ui.rtextarea.RTextArea.PASTE_ACTION), MenuIcons.paste()));
                menu.add(createMenuItem("Delete", textArea.getAction(org.fife.ui.rtextarea.RTextArea.DELETE_ACTION), MenuIcons.delete()));
                menu.addSeparator();
                menu.add(createMenuItem("Select all", textArea.getAction(org.fife.ui.rtextarea.RTextArea.SELECT_ALL_ACTION)));

                // Show Suggestions (auto-complete)
                // Tried event-based trigger, but couldn't get stable behaviour.
                // Resorted to using a timer delay to allow UI to reestablish focus
                // before launching autocomplete.
                if (autoCompleteManager != null) {
                    menu.addSeparator();
                    JMenuItem suggestionsItem = new JMenuItem("Show suggestions");
                    // Shortcut hint belongs in the accelerator slot, not the label (manifesto §2.7).
                    // Ctrl+Space on all platforms (Cmd+Space is Spotlight on macOS).
                    suggestionsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
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
                            JMenuItem goToNodeItem = new JMenuItem("Go to node definition");
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
                        JMenuItem showOnMapItem = new JMenuItem("Show on map");
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
                                    JMenuItem item = new JMenuItem(buildMenuLabel(command, null));
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
                                            displayName = "Rename \"" + context.getNodeName().get() + "\"";
                                        }
                                    }

                                    JMenuItem item = new JMenuItem(buildMenuLabel(command, displayName));
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

            /** As {@link #createMenuItem(String, Action)} but with a sparse landmark icon (manifesto §3). */
            private JMenuItem createMenuItem(String name, Action action, Icon icon) {
                JMenuItem item = createMenuItem(name, action);
                item.setIcon(icon);
                return item;
            }
        });
    }

    /**
     * Builds the label shown in the context menu for a command, appending the
     * platform-appropriate shortcut hint (e.g. "Table View (⌘T)") when the
     * command's {@link com.kalix.ide.editor.commands.CommandMetadata} carries
     * a keyboard shortcut.
     *
     * @param command            the command being rendered
     * @param displayNameOverride a per-instance display name (e.g. "Rename Foo"
     *                            for the rename command), or null to use the
     *                            command's static display name
     */
    private static String buildMenuLabel(com.kalix.ide.editor.commands.EditorCommand command,
                                         String displayNameOverride) {
        String displayName = displayNameOverride != null
                ? displayNameOverride
                : command.getMetadata().getDisplayName();
        return command.getMetadata().getShortcutHint()
                .map(hint -> displayName + " (" + hint + ")")
                .orElse(displayName);
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
        ParsedModel parsedModel = commandModelSupplier.get();
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

        // Create executor and perform rename. Success is silent: the rename is visible in
        // the editor and on the map, so a confirmation dialog would only be in the way.
        CommandExecutor executor =
            new CommandExecutor(textArea, commandParentFrame, this::applyAtomicReplacements);

        return executor.renameNode(nodeName, trimmedNewName, parsedModel);
    }

    /**
     * Inserts a node template programmatically (e.g., from the map context menu). The
     * new node's {@code loc} is the given world location; its section goes below the
     * last selected node, or at the bottom when nothing is selected.
     *
     * <p>Auto-linking: with exactly one node selected, the new node is linked from it
     * (first free {@code ds_N} in the upstream section). With a link selected
     * ({@code spliceLink}), the new node is inserted <em>into</em> that link — the
     * upstream's {@code ds_N} re-points at the new node, which gains its own
     * {@code ds_1} to the old downstream. Both are single atomic edits.</p>
     *
     * @param nodeType          The template key (e.g. "gr4j", "storage")
     * @param worldX            The map x-coordinate for the new node's {@code loc}
     * @param worldY            The map y-coordinate for the new node's {@code loc}
     * @param selectedNodeNames The currently selected nodes (may be empty or null)
     * @param spliceLink        The selected link to insert the node into, or null
     * @return the new node's name, or null on failure
     */
    public String insertNodeTemplate(String nodeType, double worldX, double worldY,
                                     java.util.Collection<String> selectedNodeNames,
                                     com.kalix.ide.model.ModelLink spliceLink) {
        if (commandParentFrame == null) {
            logger.warn("Context commands not initialized - cannot insert node template");
            return null;
        }

        CommandExecutor executor = new CommandExecutor(textArea, commandParentFrame, this::applyAtomicReplacements);

        return executor.insertNodeTemplateAtLocation(nodeType, worldX, worldY, selectedNodeNames, spliceLink);
    }

    private void setupKeyBindings() {
        InputMap inputMap = textArea.getInputMap();
        ActionMap actionMap = textArea.getActionMap();

        // Every stroke here derives from AppShortcut — the same declarations behind the
        // menu accelerators and toolbar tooltips — so binding, accelerator, and hint can
        // never disagree. bind() registers both the Cmd and Ctrl variants (see its javadoc).
        bind(inputMap, AppShortcut.UNDO, "undo");
        bind(inputMap, AppShortcut.REDO, "redo");
        // macOS convention: Cmd+Shift+Z also redoes (an alias beyond the canonical stroke).
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo");
        
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
        bind(inputMap, AppShortcut.GO_TO_LINE, "goToLine");

        actionMap.put("goToLine", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigationManager.showGoToLineDialog();
            }
        });
        
        // Find
        bind(inputMap, AppShortcut.FIND, "find");

        actionMap.put("find", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.showFindDialog();
            }
        });
        
        // Find and Replace
        bind(inputMap, AppShortcut.FIND_AND_REPLACE, "replace");

        actionMap.put("replace", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.showFindReplaceDialog();
            }
        });

        // Repeat the last search, with no dialog needed. Bound twice on purpose:
        // AppShortcut supplies the ⌘G/⇧⌘G form (the macOS system convention), and the
        // bare F3/Shift+F3 form below is the Windows/Linux one — which AppShortcut
        // cannot express, since its strokes always carry the menu modifier.
        bind(inputMap, AppShortcut.FIND_NEXT, "findNext");
        bind(inputMap, AppShortcut.FIND_PREVIOUS, "findPrevious");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "findNext");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "findPrevious");

        actionMap.put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.findAgain(true);
            }
        });

        actionMap.put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchManager.findAgain(false);
            }
        });

        // Toggle Comment
        bind(inputMap, AppShortcut.TOGGLE_COMMENT, "toggleComment");

        actionMap.put("toggleComment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleComment();
            }
        });

        // Navigate Back
        bind(inputMap, AppShortcut.NAVIGATE_BACK, "navigateBack");

        actionMap.put("navigateBack", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateBack();
            }
        });

        // Navigate Forward
        bind(inputMap, AppShortcut.NAVIGATE_FORWARD, "navigateForward");

        actionMap.put("navigateForward", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateForward();
            }
        });

        // Command-driven shortcuts (e.g. Cmd/Ctrl+T for Table View) are installed
        // separately by ContextCommandManager.installCommandShortcuts, after the
        // commands have been registered. Each command's metadata declares its own
        // KeyStroke once, so the binding and the menu hint cannot disagree.
    }

    /**
     * Registers an editor binding for an {@link AppShortcut} under both the Cmd (META)
     * and Ctrl variants of its stroke — deliberate belt-and-braces so cross-platform
     * muscle memory works (e.g. Ctrl+Z still undoes on macOS). The key itself is declared
     * once in {@link AppShortcut}, shared with the menu accelerators and toolbar
     * tooltips, so binding and hint cannot drift apart.
     */
    private static void bind(InputMap inputMap, AppShortcut shortcut, String actionKey) {
        inputMap.put(shortcut.keyStrokeWith(InputEvent.META_DOWN_MASK), actionKey);
        inputMap.put(shortcut.keyStrokeWith(InputEvent.CTRL_DOWN_MASK), actionKey);
    }
    
    private void setupDocumentListener() {
        // Set up document change listener for dirty tracking and external listeners
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    updateDirtyFromContent();
                    notifyExternalListeners((listener, event) -> listener.insertUpdate(event), e);
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    updateDirtyFromContent();
                    notifyExternalListeners((listener, event) -> listener.removeUpdate(event), e);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!programmaticUpdate) {
                    updateDirtyFromContent();
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

    /**
     * Inserts the relative paths of files dragged from the project tree at the drop point, one per
     * line. Paths are relative to this document's directory; if that is unavailable (untitled
     * document) or on a different filesystem root, the absolute path is used instead.
     */
    private void insertDroppedPaths(java.util.List<java.io.File> files, java.awt.Component target,
                                    java.awt.Point location) {
        if (files == null || files.isEmpty()) {
            return;
        }
        java.io.File baseFile = (modelFileSupplier != null) ? modelFileSupplier.get() : null;
        java.io.File baseDir = (baseFile != null) ? baseFile.getParentFile() : null;
        String text = files.stream()
            .map(f -> droppedPathFor(baseDir, f))
            .collect(java.util.stream.Collectors.joining("\n"));

        // Insert at the caret position nearest the drop point (translated into the text area).
        java.awt.Point p = javax.swing.SwingUtilities.convertPoint(target, location, textArea);
        int offset = textArea.viewToModel2D(p);
        if (offset < 0) {
            offset = textArea.getCaretPosition();
        }
        try {
            textArea.getDocument().insertString(offset, text, null);
            textArea.setCaretPosition(offset + text.length());
            textArea.requestFocusInWindow();
        } catch (javax.swing.text.BadLocationException ex) {
            // Offset became invalid between hit-testing and insertion; nothing to insert.
        }
    }

    private static String droppedPathFor(java.io.File baseDir, java.io.File target) {
        if (baseDir != null) {
            try {
                return com.kalix.ide.io.KalixPath.relativize(baseDir.toPath(), target.toPath());
            } catch (IllegalArgumentException ex) {
                // Different filesystem roots — fall back to the absolute path below.
            }
        }
        return target.getAbsolutePath().replace(java.io.File.separator, "/");
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
     * Uses "#" as the comment character. If every non-blank line starts with "#"
     * (after whitespace), comments are removed; otherwise they are added.
     *
     * <p>All line coordinates are snapshotted from the document <em>before</em> any
     * edit and the edits are applied bottom-up, so the selection-restore arithmetic
     * works in one consistent coordinate space regardless of how many lines are
     * selected (the old per-line re-read drifted for selections spanning 3+ lines).
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

            // Snapshot pre-edit line starts and contents (without trailing newline)
            int lineCount = endLine - startLine + 1;
            int[] lineStarts = new int[lineCount];
            String[] contents = new String[lineCount];
            for (int i = 0; i < lineCount; i++) {
                int line = startLine + i;
                int lineStart = textArea.getLineStartOffset(line);
                int lineEnd = textArea.getLineEndOffset(line);
                String lineText = textArea.getText(lineStart, lineEnd - lineStart);
                if (lineText.endsWith("\n")) {
                    lineText = lineText.substring(0, lineText.length() - 1);
                }
                lineStarts[i] = lineStart;
                contents[i] = lineText;
            }

            // Check if all lines are commented (to decide whether to comment or uncomment)
            boolean allCommented = areAllCommented(contents);

            // Compute the toggled lines purely, then apply
            String[] newContents = new String[lineCount];
            int[] deltas = new int[lineCount];
            for (int i = 0; i < lineCount; i++) {
                newContents[i] = toggleLineComment(contents[i], allCommented);
                deltas[i] = newContents[i].length() - contents[i].length();
            }

            // Group all line edits into a single undo step so one Cmd/Ctrl+Z
            // (un)comments the whole selection rather than one line at a time.
            // Bottom-up, so the pre-edit offsets stay valid for the pending edits.
            textArea.beginAtomicEdit();
            try {
                for (int i = lineCount - 1; i >= 0; i--) {
                    if (!newContents[i].equals(contents[i])) {
                        textArea.replaceRange(newContents[i], lineStarts[i], lineStarts[i] + contents[i].length());
                    }
                }
            } finally {
                textArea.endAtomicEdit();
            }

            // Restore selection, shifted by the per-line length changes
            int[] newSelection = shiftSelectionForLineDeltas(selectionStart, selectionEnd, lineStarts, deltas);
            int documentLength = textArea.getDocument().getLength();
            textArea.setSelectionStart(Math.min(newSelection[0], documentLength));
            textArea.setSelectionEnd(Math.min(newSelection[1], documentLength));

        } catch (Exception ex) {
            logger.error("Error toggling comments", ex);
        }
    }

    /**
     * @return true if every non-blank line starts with '#' after leading whitespace
     *         (blank lines are ignored)
     */
    static boolean areAllCommented(String[] lineContents) {
        for (String content : lineContents) {
            String trimmed = content.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Toggles the comment marker on one line's content (no trailing newline).
     *
     * @param content   the line content
     * @param uncomment true to remove "#" (and one following space) after leading
     *                  whitespace; false to insert "# " after leading whitespace
     * @return the toggled line content (unchanged when uncommenting a line with no '#')
     */
    static String toggleLineComment(String content, boolean uncomment) {
        int firstNonWhitespace = 0;
        while (firstNonWhitespace < content.length() && Character.isWhitespace(content.charAt(firstNonWhitespace))) {
            firstNonWhitespace++;
        }
        String before = content.substring(0, firstNonWhitespace);
        String after = content.substring(firstNonWhitespace);

        if (uncomment) {
            if (!after.startsWith("#")) {
                return content;
            }
            after = after.substring(1);
            // Also remove the space after # if present
            if (after.startsWith(" ")) {
                after = after.substring(1);
            }
            return before + after;
        }
        return before + "# " + after;
    }

    /**
     * Shifts a selection for per-line length changes, all expressed in pre-edit
     * document coordinates: the start moves with the delta of its own line, the end
     * with the deltas of every edited line that begins before it. The start is
     * clamped to its line start (relevant when uncommenting with the caret inside
     * the removed marker) and the end never precedes the start.
     *
     * @param selectionStart pre-edit selection start
     * @param selectionEnd   pre-edit selection end
     * @param lineStarts     pre-edit start offsets of the edited lines (ascending)
     * @param deltas         length change of each edited line
     * @return {newSelectionStart, newSelectionEnd}
     */
    static int[] shiftSelectionForLineDeltas(int selectionStart, int selectionEnd, int[] lineStarts, int[] deltas) {
        int newStart = selectionStart;
        int newEnd = selectionEnd;
        for (int i = 0; i < lineStarts.length; i++) {
            if (lineStarts[i] <= selectionStart) {
                newStart += deltas[i];
            }
            if (lineStarts[i] < selectionEnd) {
                newEnd += deltas[i];
            }
        }
        newStart = Math.max(newStart, lineStarts.length > 0 ? lineStarts[0] : 0);
        newEnd = Math.max(newEnd, newStart);
        return new int[] {newStart, newEnd};
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
     * Each replacement names a line, an exact column range on that line
     * (via {@link LineReplacement#startColumn}), the text expected there, and
     * the replacement text. Ranges are exact: only the addressed occurrence is
     * touched, so renaming node {@code s} on {@code ds_1 = s  # s} cannot
     * rewrite the key or the comment.
     *
     * @param replacements List of range-anchored line replacements
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
                // Apply replacements bottom-up, right-to-left, so earlier edits
                // cannot shift the positions of the ones still to apply.
                java.util.List<LineReplacement> sortedReplacements = new java.util.ArrayList<>(replacements);
                sortedReplacements.sort(
                    java.util.Comparator.comparingInt((LineReplacement r) -> r.lineNumber)
                        .thenComparingInt(r -> r.startColumn)
                        .reversed());

                for (LineReplacement replacement : sortedReplacements) {
                    int lineIndex = replacement.lineNumber - 1; // Convert 1-based to 0-based

                    if (lineIndex >= 0 && lineIndex < lines.length) {
                        String originalLine = lines[lineIndex];
                        String newLine = spliceLine(originalLine, replacement);
                        if (newLine == null) {
                            // The addressed range no longer holds the expected text
                            // (stale detection, or a duplicate replacement already
                            // applied at this range). Skip rather than corrupt.
                            logger.warn("Skipping stale replacement at line {} col {}: expected '{}'",
                                replacement.lineNumber, replacement.startColumn, replacement.oldText);
                            continue;
                        }

                        // Find the start position of this line in the document
                        int startPos = 0;
                        for (int i = 0; i < lineIndex; i++) {
                            startPos += lines[i].length() + 1; // +1 for newline
                        }

                        // Replace only the addressed range in the document
                        doc.remove(startPos + replacement.startColumn, replacement.oldText.length());
                        doc.insertString(startPos + replacement.startColumn, replacement.newText, null);

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
     * Applies one range-anchored replacement to a line's text, verifying that the
     * addressed range actually holds the expected old text.
     *
     * @return the new line text, or {@code null} if the range is out of bounds or
     *         does not match {@code replacement.oldText}
     */
    static String spliceLine(String line, LineReplacement replacement) {
        int start = replacement.startColumn;
        if (start < 0 || start + replacement.oldText.length() > line.length()
                || !line.regionMatches(start, replacement.oldText, 0, replacement.oldText.length())) {
            return null;
        }
        return line.substring(0, start) + replacement.newText
            + line.substring(start + replacement.oldText.length());
    }

    /**
     * Represents a range-anchored text replacement: on line {@code lineNumber},
     * the text {@code oldText} beginning at column {@code startColumn} is
     * replaced by {@code newText}.
     */
    public static class LineReplacement {
        public final int lineNumber; // 1-based
        /** 0-based column of {@code oldText} within the line. */
        public final int startColumn;
        public final String oldText;
        public final String newText;

        public LineReplacement(int lineNumber, int startColumn, String oldText, String newText) {
            this.lineNumber = lineNumber;
            this.startColumn = startColumn;
            this.oldText = oldText;
            this.newText = newText;
        }
    }

    public String getText() {
        return textArea.getText();
    }

    // Dirty state management
    public boolean isDirty() {
        return isDirty;
    }
    
    public void setDirty(boolean dirty) {
        if (!dirty) {
            // Becoming clean (file load or save): snapshot the current content as the
            // baseline so subsequent edits — and undo/redo back to here — can detect
            // when the buffer once again matches what's on disk.
            cleanText = textArea.getText();
        }
        if (this.isDirty != dirty) {
            this.isDirty = dirty;
            if (dirtyStateListener != null) {
                dirtyStateListener.onDirtyStateChanged(dirty);
            }
        }
    }

    /**
     * Recomputes the dirty flag by comparing the current content against the clean
     * baseline. This clears the dirty flag when the user edits (or undoes) the buffer
     * back to its last-saved content.
     *
     * <p>The document length is compared first: this runs per keystroke, and
     * {@code getText()} materialises a copy of the whole buffer, so the copy (and
     * the character comparison) is only paid when the lengths happen to match.</p>
     */
    private void updateDirtyFromContent() {
        if (textArea.getDocument().getLength() != cleanText.length()) {
            setDirty(true);
            return;
        }
        setDirty(!textArea.getText().equals(cleanText));
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
     * Remove a previously added external document listener.
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
     * Dispose of resources when the editor is no longer needed (document close).
     * Detaches everything with a lifetime longer than this editor: the global
     * Toolkit mouse listener, the auto-complete installation and its background
     * reader executor (via {@link AutoCompleteManager#dispose}), and the linter
     * stack's listeners and executor (via {@code LinterManager.dispose}).
     * Idempotent.
     */
    public void dispose() {
        if (navigationMouseCaptureListener != null) {
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(navigationMouseCaptureListener);
            navigationMouseCaptureListener = null;
        }
        if (autoCompleteManager != null) {
            autoCompleteManager.dispose();
            autoCompleteManager = null;
        }
        if (linterManager != null) {
            linterManager.dispose();
            linterManager = null;
        }
        if (propertyHoverTooltipManager != null) {
            propertyHoverTooltipManager.dispose();
            propertyHoverTooltipManager = null;
        }
        if (searchManager != null) {
            searchManager.dispose();
            searchManager = null;
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