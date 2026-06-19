package com.kalix.ide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kalix.ide.builders.MenuBarBuilder;
import com.kalix.ide.builders.ToolBarBuilder;
import com.kalix.ide.cli.ProcessExecutor;
import com.kalix.ide.components.AutoHidingProgressBar;
import com.kalix.ide.constants.AppConstants;
import com.kalix.ide.dialogs.PreferencesDialog;
import com.kalix.ide.linter.SchemaManager;
import com.kalix.ide.document.DocumentManager;
import com.kalix.ide.document.KalixDocument;
import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.handlers.FileDropHandler;
import com.kalix.ide.managers.ThemeManager;
import com.kalix.ide.managers.RecentFilesManager;
import com.kalix.ide.managers.FileOperationsManager;
import com.kalix.ide.managers.VersionChecker;
import com.kalix.ide.managers.TitleBarManager;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.FileWatcherManager;
import com.kalix.ide.managers.IconManager;
import com.kalix.ide.managers.FontManager;
import com.kalix.ide.model.HydrologicalModel;
import com.kalix.ide.model.ModelChangeEvent;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.utils.TerminalActions;
import com.kalix.ide.utils.WindowsIntegration;
import com.kalix.ide.workspace.ProjectTreePanel;
import com.kalix.ide.workspace.WorkspacePanel;
import com.kalix.ide.workspace.tree.TreeHost;
import com.kalix.ide.windows.RunManager;
import com.kalix.ide.windows.OptimisationWindow;
import com.kalix.ide.windows.SessionManagerWindow;
import com.kalix.ide.windows.MinimalEditorWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import java.io.File;
import java.util.prefs.Preferences;

/**
 * Main GUI class for the Kalix Hydrologic Modeling application.
 * 
 * This class serves as the primary window and coordinator for the application,
 * managing the overall layout and coordinating between different components
 * such as the map panel and text editor. It delegates specific functionality
 * to specialized manager classes for better organization and maintainability.
 * 
 * Key features:
 * - Split-pane layout with map visualization and text editing
 * - Comprehensive menu system with theme switching
 * - Recent files management
 * - Drag and drop file support
 * - Font customization
 * - Multiple UI themes
 * 
 * @author Kalix Development Team
 * @version 1.0
 */
public class KalixIDE extends JFrame implements MenuBarBuilder.MenuBarCallbacks {
    private static final Logger logger = LoggerFactory.getLogger(KalixIDE.class);

    // Document management (owns open documents and the active-document concept)
    private DocumentManager documentManager;

    // Cached views of the active document, refreshed when the active document changes.
    // In Phase 1 there is exactly one document, so these are set once at startup.
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;
    private java.util.function.Supplier<com.kalix.ide.linter.parsing.INIModelParser.ParsedModel> modelSupplier;
    private com.kalix.ide.parametersheet.ParameterSheetWindow parameterSheetWindow;
    private WorkspacePanel workspacePanel;
    private ProjectTreePanel projectTreePanel;
    private com.kalix.ide.workspace.DocumentTabPane documentTabPane;
    private com.kalix.ide.workspace.ContextViewPanel contextViewPanel;
    private JLabel statusLabel;
    private AutoHidingProgressBar progressBar;
    private JToolBar toolBar;

    // Toolbar toggle buttons (stored for state synchronization)
    private JToggleButton fileTreeToggleButton;
    private JToggleButton lintingToggleButton;
    private JToggleButton autoReloadToggleButton;
    private JToggleButton gridlinesToggleButton;

    // Navigation buttons (stored for state synchronization)
    private JButton backButton;
    private JButton forwardButton;
    
    // Manager classes for specialized functionality
    private ThemeManager themeManager;
    private RecentFilesManager recentFilesManager;
    private FileOperationsManager fileOperations;
    private FileDropHandler fileDropHandler;
    private VersionChecker versionChecker;
    private TitleBarManager titleBarManager;
    private ProcessExecutor processExecutor;
    private StdioTaskManager stdioTaskManager;
    private FileWatcherManager fileWatcherManager;
    private SchemaManager schemaManager;

    // Data model
    private HydrologicalModel hydrologicalModel;
    
    // Application state
    private Preferences prefs;
    private String initialFilePath;

    /** Suppresses session persistence while a session is being restored at startup. */
    private boolean restoringSession = false;

    /**
     * Creates a new KalixIDE instance.
     * Initializes all components, managers, and sets up the user interface.
     *
     * @param initialFilePath optional file path to open on startup (may be null)
     */
    public KalixIDE(String initialFilePath) {
        this.initialFilePath = initialFilePath;
        initializeApplication();
    }
    
    /**
     * Initializes the application by setting up all managers and components.
     */
    private void initializeApplication() {
        // Initialize preferences
        prefs = Preferences.userNodeForPackage(KalixIDE.class);
        
        // Set up basic window properties
        setupWindow();
        
        // Initialize all managers
        initializeManagers();
        
        // Initialize UI components
        initializeComponents();
        
        // Set up layout and interactions
        setupLayout();
        setupMenuBar();
        setupDragAndDrop();
        setupWindowListeners();
        setupGlobalKeyBindings();

        finaliseComponents();
        
        // Note: Split pane divider position loading removed - using simple grid layout now

        setVisible(true);

        // Load file: command-line argument takes priority; otherwise restore the previous
        // session of open tabs, falling back to the last opened file.
        if (initialFilePath != null) {
            fileOperations.loadModelFile(new File(initialFilePath));
        } else if (!restoreSession()) {
            loadLastOpenedFile();
        }

        // Ensure at least one document is always open.
        if (documentManager.getActiveDocument() == null) {
            fileOperations.newModel();
        }

        // Restore the last opened project folder, if any.
        String savedFolder = PreferenceManager.getOsString(PreferenceKeys.UI_WORKSPACE_FOLDER, "");
        if (!savedFolder.isEmpty()) {
            File folder = new File(savedFolder);
            if (folder.isDirectory()) {
                projectTreePanel.openFolder(folder);
                // Reveal the active document in the just-loaded tree.
                revealActiveFileInTree();
            }
        }
    }
    
    /**
     * Sets up basic window properties.
     */
    private void setupWindow() {
        IconManager.SetIcon(this);
        setTitle(AppConstants.APP_TITLE);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }
    
    /**
     * Initializes all manager classes.
     */
    private void initializeManagers() {
        // Theme manager
        themeManager = new ThemeManager(prefs, this);
        
        // Title bar manager
        titleBarManager = new TitleBarManager(this);
        
        // Process executor for CLI operations
        processExecutor = new ProcessExecutor();

        // File watcher manager
        fileWatcherManager = new FileWatcherManager(this::handleFileReload);

        // Schema manager for linting
        schemaManager = new SchemaManager();
        schemaManager.initialize();

        // Data model and document are created in initializeComponents, alongside the
        // editor and map views the document bundles together.

        // File operations manager (initialized after components)

        // Recent files manager
        recentFilesManager = new RecentFilesManager(
            prefs,
            this::loadModelFile,
            () -> updateStatus(AppConstants.STATUS_RECENT_FILES_CLEARED)
        );
    }

    /**
     * Initializes all UI components.
     */
    private void initializeComponents() {
        // The document set. Documents are created lazily (on open/new) via createDocument()
        // so the tab strip and contextual view — built in setupLayout() — are subscribed
        // before the first document appears.
        documentManager = new DocumentManager();

        statusLabel = new JLabel(AppConstants.STATUS_READY);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H,
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H
        ));

        progressBar = new AutoHidingProgressBar();

        // Model supplier reflects whichever document is currently active.
        modelSupplier = () -> {
            KalixDocument doc = documentManager.getActiveDocument();
            return doc != null ? doc.getModelSupplier().get() : null;
        };

        // File operations create/focus documents via the factory and act on the active one.
        fileOperations = new FileOperationsManager(
            this, documentManager, this::createDocument,
            this::updateStatus,
            recentFilesManager::addRecentFile,
            this::onActiveDocumentFileChanged,
            fileWatcherManager
        );
        // NOTE: projectDirectorySupplier is registered in the finaliseComponents step

        fileDropHandler = new FileDropHandler(fileOperations, this::updateStatus);
        versionChecker = new VersionChecker(this::updateStatus);

        // Initialize STDIO task manager
        stdioTaskManager = new StdioTaskManager(
            processExecutor,
            this::updateStatus,
            progressBar,
            this,
            fileOperations::getCurrentWorkingDirectory,
            fileOperations::getCurrentProjectDirectory
        );

        // Suppliers for auxiliary windows always reflect the active document.
        RunManager.setBaseDirectorySupplier(fileOperations::getCurrentWorkingDirectory);
        RunManager.setEditorTextSupplier(() -> {
            KalixDocument doc = documentManager.getActiveDocument();
            return doc != null ? doc.getText() : null;
        });
        MinimalEditorWindow.setBaseDirectorySupplier(fileOperations::getCurrentWorkingDirectory);

        // React to active-document changes: re-point cached views, title, watcher, status.
        documentManager.addActiveDocumentChangeListener(this::onActiveDocumentChanged);

        // Persist the open-tabs session whenever the document set or active tab changes.
        documentManager.addDocumentOpenedListener(doc -> saveSession());
        documentManager.addDocumentClosedListener(doc -> saveSession());
        documentManager.addActiveDocumentChangeListener(doc -> saveSession());

        // Keep the file watcher tracking every open document's file (all tabs auto-reload).
        documentManager.addDocumentOpenedListener(doc -> refreshWatchedFiles());
        documentManager.addDocumentClosedListener(doc -> refreshWatchedFiles());
    }

    private void finaliseComponents() {
        fileOperations.registerProjectDirectorySupplier(projectTreePanel::getRootFile);
    }

    /**
     * Persists the open-tab session to OS preferences: one entry per document as
     * {@code caret<TAB>absolutePath}, entries separated by newlines, plus the active path.
     * Each entry is self-describing (no parallel arrays to fall out of alignment), and a
     * tab in a path — unlike a newline — is rare and degrades to skipping just that entry.
     * Untitled documents are skipped. No-op while restoring.
     */
    private void saveSession() {
        if (restoringSession) {
            return;
        }
        StringBuilder entries = new StringBuilder();
        for (KalixDocument doc : documentManager.getDocuments()) {
            File file = doc.getFile();
            if (file == null) {
                continue; // untitled documents cannot be restored
            }
            if (entries.length() > 0) {
                entries.append('\n');
            }
            entries.append(doc.getCaretPosition()).append('\t').append(file.getAbsolutePath());
        }
        KalixDocument active = documentManager.getActiveDocument();
        String activePath = (active != null && active.getFile() != null)
            ? active.getFile().getAbsolutePath() : "";

        PreferenceManager.setOsString(PreferenceKeys.UI_OPEN_DOCUMENTS, entries.toString());
        PreferenceManager.setOsString(PreferenceKeys.UI_ACTIVE_DOCUMENT, activePath);
    }

    /**
     * Restores the open-tab session saved by {@link #saveSession()}: reopens each still-existing
     * file in order, restores its caret, and activates the saved active tab.
     *
     * @return true if at least one document was restored
     */
    private boolean restoreSession() {
        String entriesStr = PreferenceManager.getOsString(PreferenceKeys.UI_OPEN_DOCUMENTS, "");
        if (entriesStr.isEmpty()) {
            return false;
        }
        String activePath = PreferenceManager.getOsString(PreferenceKeys.UI_ACTIVE_DOCUMENT, "");

        boolean restoredAny = false;
        restoringSession = true;
        try {
            for (String entry : entriesStr.split("\n", -1)) {
                int tab = entry.indexOf('\t');
                if (tab < 0) {
                    continue; // malformed entry; skip just this one
                }
                String path = entry.substring(tab + 1);
                File file = new File(path);
                if (path.isEmpty() || !file.isFile()) {
                    continue; // file removed since last session
                }
                fileOperations.loadModelFile(file);
                KalixDocument doc = documentManager.findByFile(file);
                if (doc != null) {
                    restoredAny = true;
                    try {
                        doc.setCaretPosition(Integer.parseInt(entry.substring(0, tab).trim()));
                    } catch (NumberFormatException ignored) {
                        // best-effort caret restore
                    }
                }
            }
            if (!activePath.isEmpty()) {
                KalixDocument activeDoc = documentManager.findByFile(new File(activePath));
                if (activeDoc != null) {
                    documentManager.setActiveDocument(activeDoc);
                }
            }
        } finally {
            restoringSession = false;
        }

        if (restoredAny) {
            saveSession(); // normalise persisted state (drops files that no longer exist)
        }
        return restoredAny;
    }

    /**
     * Creates a fresh document, wires its per-document features against shared services,
     * and registers it with the document manager (which adds its tab). The caller sets the
     * document's content/file and makes it active.
     *
     * @return the newly created, configured, registered document
     */
    private KalixDocument createDocument() {
        KalixDocument document = new KalixDocument();
        configureDocument(document);
        documentManager.addDocument(document);
        return document;
    }

    /**
     * Wires a document's editor and map to shared application services, binding each
     * feature to <em>this</em> document (not the active one) so background documents stay
     * correct.
     */
    private void configureDocument(KalixDocument document) {
        EnhancedTextEditor editor = document.getEditor();
        MapPanel map = document.getMapPanel();

        // Map appearance from saved preferences.
        map.setNodeTheme(NodeTheme.themeFromString(
            PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME)));
        map.setShowGridlines(PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true));

        // Editor features, each bound to this document's own model and working directory.
        editor.initializeLinter(schemaManager);
        editor.initializeContextCommands(this, document.getModelSupplier(), document::getFile);
        editor.initializeAutoComplete(schemaManager, document.getModelSupplier(), document::getWorkingDirectory);
        editor.initializePropertyTooltips(schemaManager, document.getModelSupplier());
        editor.setLinterBaseDirectorySupplier(document::getWorkingDirectory);

        // Status bar reflects model changes — but only for the active document (the
        // listener is bound to this document so it can tell whether it is the active one).
        document.getModel().addChangeListener(event -> onModelChanged(document, event));

        // Dirty state updates the tab marker and (when active) the window title.
        editor.setDirtyStateListener(isDirty -> {
            documentTabPane.refreshTab(document);
            if (document == documentManager.getActiveDocument()) {
                titleBarManager.updateTitle(isDirty, document::getFile);
            }
        });

        // Dropping a file onto this editor opens it in a tab.
        editor.setFileDropHandler(fileOperations::loadModelFile);

        // Ensure this document's map reflects the current theme.
        SwingUtilities.invokeLater(map::updateThemeColors);
    }

    /**
     * Re-points cached views and application-level UI at the newly active document.
     * The argument may be {@code null} transiently when the last document is closed before
     * a replacement is opened.
     */
    private void onActiveDocumentChanged(KalixDocument document) {
        if (document == null) {
            return;
        }
        textEditor = document.getEditor();
        mapPanel = document.getMapPanel();
        hydrologicalModel = document.getModel();

        // Theme operations target the active map; refresh it in case the theme changed
        // while this document was in the background.
        themeManager.registerThemeAwareComponents(mapPanel, textEditor);
        SwingUtilities.invokeLater(mapPanel::updateThemeColors);

        titleBarManager.updateTitle(document.isDirty(), document::getFile);
        documentTabPane.refreshTab(document);
        setupNavigationStateListener();
        refreshModelStatus();
        revealActiveFileInTree();
    }

    /**
     * Selects the active document's file in the project tree, if it lies within the open
     * folder. A no-op (clears the tree selection) for files opened from outside the folder or
     * when no folder is open.
     */
    private void revealActiveFileInTree() {
        if (projectTreePanel == null) {
            return;
        }
        KalixDocument document = documentManager.getActiveDocument();
        projectTreePanel.selectFile(document != null ? document.getFile() : null);
    }

    /**
     * Opens a diff comparing {@code file} (left, on disk) against the active editor's current
     * text (right, possibly unsaved). Invoked from the project tree's "Compare with active
     * editor" menu item. The active editor's live buffer is used, so the diff reflects unsaved
     * edits.
     */
    private void compareWithActiveEditor(File file) {
        KalixDocument active = documentManager.getActiveDocument();
        if (active == null) {
            JOptionPane.showMessageDialog(this,
                "There is no active editor to compare with.",
                "Compare", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String fileText = readTextOrWarn(file);
        if (fileText == null) {
            return;
        }
        File activeFile = active.getFile();
        String activeName = activeFile != null ? activeFile.getName() : "Active editor";
        // referenceModel = left, thisModel = right (see DiffWindow): selected file on the left,
        // the editor's current text on the right.
        new com.kalix.ide.diff.DiffWindow(
            active.getText(), fileText,
            "Compare: " + file.getName() + " vs active editor",
            file.getName(), activeName);
    }

    /**
     * Opens a diff comparing two files against each other, {@code left} on the left and
     * {@code right} on the right. Invoked from the project tree's "Compare files" menu item
     * when exactly two files are selected (the selection's row order decides left vs right).
     */
    private void compareFiles(File left, File right) {
        String leftText = readTextOrWarn(left);
        if (leftText == null) {
            return;
        }
        String rightText = readTextOrWarn(right);
        if (rightText == null) {
            return;
        }
        // referenceModel = left, thisModel = right (see DiffWindow).
        new com.kalix.ide.diff.DiffWindow(
            rightText, leftText,
            "Compare: " + left.getName() + " vs " + right.getName(),
            left.getName(), right.getName());
    }

    /** Reads a file's text, or shows an error dialog and returns null if it cannot be read. */
    private String readTextOrWarn(File file) {
        try {
            return java.nio.file.Files.readString(file.toPath());
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Could not read \"" + file.getName() + "\": " + ex.getMessage(),
                "Compare", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /**
     * Invoked when the active document's backing file changes without a document switch
     * (Save As). Refreshes the file-derived UI: title, tab, and the set of watched files.
     */
    private void onActiveDocumentFileChanged() {
        KalixDocument document = documentManager.getActiveDocument();
        if (document == null) {
            return;
        }
        titleBarManager.updateTitle(document.isDirty(), document::getFile);
        documentTabPane.refreshTab(document);
        refreshWatchedFiles();
    }

    /**
     * Updates the file watcher with the files of all open documents, so every tab — not just
     * the active one — is auto-reloaded on external changes.
     */
    private void refreshWatchedFiles() {
        java.util.List<File> files = new java.util.ArrayList<>();
        for (KalixDocument document : documentManager.getDocuments()) {
            if (document.getFile() != null) {
                files.add(document.getFile());
            }
        }
        fileWatcherManager.setWatchedFiles(files);
    }

    /**
     * Sets up the main application layout: toolbar (north), the three-region work
     * area (centre), and the status bar (south).
     */
    private void setupLayout() {
        setLayout(new BorderLayout());

        // Add toolbar at top
        ToolBarBuilder toolBarBuilder = new ToolBarBuilder(this);
        ToolBarBuilder.ToolBarComponents components = toolBarBuilder.buildToolBar();
        toolBar = components.toolBar;
        fileTreeToggleButton = components.fileTreeToggleButton;
        lintingToggleButton = components.lintingToggleButton;
        autoReloadToggleButton = components.autoReloadToggleButton;
        gridlinesToggleButton = components.gridlinesToggleButton;
        backButton = components.backButton;
        forwardButton = components.forwardButton;
        add(toolBar, BorderLayout.NORTH);

        // Set up navigation state listener after text editor is created
        // (deferred because textEditor is created later)
        SwingUtilities.invokeLater(this::setupNavigationStateListener);

        // Build the three-region work area: [ project tree | editor | map ].
        add(buildWorkspacePanel(), BorderLayout.CENTER);

        // Add status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.EAST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
    }

    /**
     * Builds the three-region work area: [ project tree | document tabs | contextual view ].
     * The tab strip and contextual view observe the document manager, so they must be
     * created (and subscribed) here, before the first document is opened. Restores persisted
     * region widths and collapsed states and persists any changes.
     */
    private WorkspacePanel buildWorkspacePanel() {
        projectTreePanel = new ProjectTreePanel(new TreeHost() {
            @Override
            public void openFile(File file) {
                fileOperations.loadModelFile(file);
            }

            @Override
            public File activeFile() {
                KalixDocument active = documentManager.getActiveDocument();
                return active != null ? active.getFile() : null;
            }

            @Override
            public void compareWithActiveEditor(File file) {
                KalixIDE.this.compareWithActiveEditor(file);
            }

            @Override
            public void compareFiles(File left, File right) {
                KalixIDE.this.compareFiles(left, right);
            }

            @Override
            public void setShowHiddenFiles(boolean show) {
                KalixIDE.this.toggleShowHiddenFiles(show);
            }

            @Override
            public boolean isShowHiddenFiles() {
                return KalixIDE.this.isShowHiddenFiles();
            }
        });
        // Apply the persisted "show hidden files" choice before any folder is restored into the tree.
        projectTreePanel.setShowHidden(isShowHiddenFiles());
        documentTabPane = new com.kalix.ide.workspace.DocumentTabPane(documentManager, this::requestCloseDocument);
        contextViewPanel = new com.kalix.ide.workspace.ContextViewPanel(documentManager);

        int treeWidth = PreferenceManager.getOsInt(PreferenceKeys.UI_TREE_WIDTH, AppConstants.DEFAULT_TREE_WIDTH);
        int mapWidth = PreferenceManager.getOsInt(PreferenceKeys.UI_MAP_WIDTH, AppConstants.DEFAULT_MAP_WIDTH);
        boolean treeCollapsed = computeInitialTreeCollapsed();
        boolean mapCollapsed = PreferenceManager.getOsBoolean(PreferenceKeys.UI_MAP_COLLAPSED, false);

        workspacePanel = new WorkspacePanel(
            projectTreePanel, documentTabPane, contextViewPanel,
            treeWidth, mapWidth, treeCollapsed, mapCollapsed);

        workspacePanel.setLayoutChangeListener((tw, mw, tc, mc) -> {
            PreferenceManager.setOsInt(PreferenceKeys.UI_TREE_WIDTH, tw);
            PreferenceManager.setOsInt(PreferenceKeys.UI_MAP_WIDTH, mw);
            PreferenceManager.setOsBoolean(PreferenceKeys.UI_TREE_COLLAPSED, tc);
            PreferenceManager.setOsBoolean(PreferenceKeys.UI_MAP_COLLAPSED, mc);
        });

        return workspacePanel;
    }

    /**
     * The tree region's initial collapsed state: collapsed whenever there is no valid saved
     * folder to restore (regardless of the saved collapse preference); otherwise the saved
     * preference. Passing this as the {@code WorkspacePanel} constructor's initial state means
     * a folderless launch does not overwrite the user's saved collapse preference.
     */
    private boolean computeInitialTreeCollapsed() {
        if (!savedFolderIsValid()) {
            return true;
        }
        return PreferenceManager.getOsBoolean(PreferenceKeys.UI_TREE_COLLAPSED, false);
    }

    /** @return true if the saved workspace folder preference points at an existing directory */
    private boolean savedFolderIsValid() {
        String folder = PreferenceManager.getOsString(PreferenceKeys.UI_WORKSPACE_FOLDER, "");
        return !folder.isEmpty() && new File(folder).isDirectory();
    }

    /**
     * Handles a tab close request: prompts to save if the document is dirty, closes it,
     * and ensures at least one document remains open.
     */
    private void requestCloseDocument(KalixDocument document) {
        if (!checkUnsavedChanges(document,
                "You have unsaved changes in \"%s\".\n\nDo you want to save your changes before closing?")) {
            return;
        }
        documentManager.closeDocument(document);
        if (documentManager.getDocuments().isEmpty()) {
            fileOperations.newModel();
        }
    }

    /**
     * Sets up the application menu bar using MenuBarBuilder.
     */
    private void setupMenuBar() {
        MenuBarBuilder menuBuilder = new MenuBarBuilder(this, textEditor);
        JMenuBar menuBar = menuBuilder.buildMenuBar();
        setJMenuBar(menuBar);

        // Connect recent files manager to menu builder
        recentFilesManager.setMenuBarBuilder(menuBuilder);
    }

    /**
     * Updates the menu bar to reflect current state changes.
     */
    private void updateMenuBar() {
        setupMenuBar();
    }

    /**
     * Synchronizes toolbar toggle button states with current preferences.
     * Called when preferences are changed outside of the toolbar (e.g., via Preferences dialog).
     */
    private void syncToggleButtonStates() {
        if (fileTreeToggleButton != null) {
            boolean treeVisible = isFileTreeVisible();
            fileTreeToggleButton.setSelected(treeVisible);
            fileTreeToggleButton.setToolTipText(treeVisible ? "Hide file tree" : "Show file tree");
        }

        if (lintingToggleButton != null) {
            boolean lintingEnabled = isLintingEnabled();
            lintingToggleButton.setSelected(lintingEnabled);
            lintingToggleButton.setToolTipText(lintingEnabled
                ? "Linting enabled - click to disable"
                : "Linting disabled - click to enable");
        }

        if (autoReloadToggleButton != null) {
            boolean autoReloadEnabled = isAutoReloadEnabled();
            autoReloadToggleButton.setSelected(autoReloadEnabled);
            autoReloadToggleButton.setToolTipText(autoReloadEnabled
                ? "Auto-reload enabled - click to disable"
                : "Auto-reload disabled - click to enable");
        }

        if (gridlinesToggleButton != null) {
            boolean gridlinesVisible = isGridlinesVisible();
            gridlinesToggleButton.setSelected(gridlinesVisible);
            gridlinesToggleButton.setToolTipText(gridlinesVisible
                ? "Gridlines visible - click to hide"
                : "Gridlines hidden - click to show");
        }
    }

    /**
     * Sets up the navigation history state change listener to update
     * back/forward button enabled states.
     */
    private void setupNavigationStateListener() {
        if (textEditor != null && textEditor.getNavigationHistory() != null) {
            textEditor.getNavigationHistory().setStateChangeListener(this::updateNavigationButtonStates);
            updateNavigationButtonStates();
        }
    }

    /**
     * Updates the enabled state of back/forward navigation buttons.
     */
    private void updateNavigationButtonStates() {
        if (backButton != null) {
            backButton.setEnabled(canNavigateBack());
        }
        if (forwardButton != null) {
            forwardButton.setEnabled(canNavigateForward());
        }
    }

    /**
     * Sets up drag and drop functionality using FileDropHandler.
     */
    private void setupDragAndDrop() {
        fileDropHandler.setupDragAndDrop(this);
    }
    
    /**
     * Sets up window event listeners including resize listener for dynamic title bar updates.
     */
    private void setupWindowListeners() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Update title bar when window is resized to ensure path still fits
                if (textEditor != null) {
                    titleBarManager.updateTitle(textEditor.isDirty(), fileOperations::getCurrentFile);
                }
            }
        });

        // Handle window closing to ensure proper cleanup
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exitApplication();
            }
        });
    }
    
    /**
     * Sets up global key bindings for undo/redo that work regardless of component focus.
     */
    private void setupGlobalKeyBindings() {
        JRootPane rootPane = getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();
        
        // Global Ctrl+Z for undo
        KeyStroke ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlZ, "global-undo");
        actionMap.put("global-undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canUndo()) {
                    textEditor.undo();
                    updateStatus(AppConstants.STATUS_UNDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_UNDO);
                }
            }
        });
        
        // Global Ctrl+Y for redo
        KeyStroke ctrlY = KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlY, "global-redo");
        actionMap.put("global-redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canRedo()) {
                    textEditor.redo();
                    updateStatus(AppConstants.STATUS_REDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
                }
            }
        });
        
        // Alternative Ctrl+Shift+Z for redo
        KeyStroke ctrlShiftZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        inputMap.put(ctrlShiftZ, "global-redo-alt");
        actionMap.put("global-redo-alt", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (textEditor.canRedo()) {
                    textEditor.redo();
                    updateStatus(AppConstants.STATUS_REDO);
                } else {
                    updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
                }
            }
        });

        // Global Ctrl+W/Cmd+W/Ctrl+F4/Cmd+F4 to close current tab
        KeyStroke ctrlW = KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK);
        KeyStroke cmdW = KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.META_DOWN_MASK);
        KeyStroke ctrlF4 = KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK);
        KeyStroke cmdF4 = KeyStroke.getKeyStroke(KeyEvent.VK_F4, InputEvent.CTRL_DOWN_MASK);
        inputMap.put(ctrlW, "global-close-current-tab");
        inputMap.put(cmdW, "global-close-current-tab");
        inputMap.put(ctrlF4, "global-close-current-tab");
        inputMap.put(cmdF4, "global-close-current-tab");
        actionMap.put("global-close-current-tab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                KalixDocument activeDocument = documentManager.getActiveDocument();
                if (activeDocument != null) {
                    requestCloseDocument(activeDocument);
                }
            }
        });
    }
    
    /**
     * Updates the status label with the given message.
     * 
     * @param message The status message to display
     */
    public void updateStatus(String message) {
        statusLabel.setText(message);
    }
    
    /**
     * Handles model change events and updates status bar.
     */
    private void onModelChanged(KalixDocument document, ModelChangeEvent event) {
        // Only the active document drives the status bar; a background document's model
        // change (e.g. during session restore) must not overwrite the active document's stats.
        if (document != documentManager.getActiveDocument()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            String message = modelStatusText(document);
            // Note: per-document auto-zoom on the 0 -> >0 transition is handled by KalixDocument.
            if (event.getAffectedNodeCount() > 0 || event.getAffectedLinkCount() > 0) {
                message += String.format(" (%d nodes, %d links modified)",
                    event.getAffectedNodeCount(), event.getAffectedLinkCount());
            }
            updateStatus(message);
        });
    }

    /**
     * Updates the status bar to reflect the active document's current model statistics.
     * Used when switching tabs (no model-change event fires on a mere switch).
     */
    private void refreshModelStatus() {
        KalixDocument document = documentManager.getActiveDocument();
        if (document != null) {
            updateStatus(modelStatusText(document));
        }
    }

    /** The "Model: N nodes, M links" summary for a document — defined in one place. */
    private static String modelStatusText(KalixDocument document) {
        var stats = document.getModel().getStatistics();
        return String.format("Model: %d nodes, %d links", stats.getNodeCount(), stats.getLinkCount());
    }
    
    /**
     * Loads a model file from the given path into a tab (focusing it if already open).
     * Used by the recent files manager callback.
     *
     * @param filePath The absolute path to the file to load
     */
    private void loadModelFile(String filePath) {
        fileOperations.loadModelFile(filePath);
    }



    //
    // MenuBarCallbacks interface implementation
    //

    /**
     * Creates a new untitled model in its own tab.
     */
    @Override
    public void newModel() {
        fileOperations.newModel();
    }

    /**
     * Opens a model file using a file chooser dialog, in its own tab.
     * Supported formats include .ini files.
     */
    @Override
    public void openModel() {
        fileOperations.openModel();
    }

    /**
     * Opens a project folder in the left-hand tree and remembers it for next launch.
     */
    @Override
    public void openFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Project Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File current = projectTreePanel.getRootFile();
        if (current != null && current.getParentFile() != null) {
            chooser.setCurrentDirectory(current.getParentFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            projectTreePanel.openFolder(folder);
            PreferenceManager.setOsString(PreferenceKeys.UI_WORKSPACE_FOLDER, folder.getAbsolutePath());
            // Always auto-expand the tree region on opening a folder, and sync the toolbar toggle.
            if (workspacePanel != null) {
                workspacePanel.setTreeCollapsed(false);
            }
            syncToggleButtonStates();
            revealActiveFileInTree();
            updateStatus("Opened folder: " + folder.getName());
        }
    }

    /**
     * Saves the current model to its existing file location.
     * If no file is associated, prompts for a save location.
     */
    @Override
    public void saveModel() {
        fileOperations.saveModel();
    }

    /**
     * Saves the current model to a new file location.
     * Always prompts the user to choose a save location.
     */
    @Override
    public void saveAsModel() {
        fileOperations.saveAsModel();
    }

    /**
     * Saves all open models to their existing file locations.
     * For unsaved models, prompts for a save location.
     */
    @Override
    public void saveAllModels() {
        fileOperations.saveAllModels();
    }

    /**
     * Shows a confirm dialog with custom key bindings (Y/N/Esc/C).
     *
     * @param message the message to display
     * @param title the dialog title
     * @return JOptionPane.YES_OPTION, JOptionPane.NO_OPTION, or JOptionPane.CANCEL_OPTION
     */
    private int showUnsavedChangesDialog(String message, String title) {
        JOptionPane pane = new JOptionPane(
            message,
            JOptionPane.WARNING_MESSAGE,
            JOptionPane.YES_NO_CANCEL_OPTION
        );

        JDialog dialog = pane.createDialog(this, title);

        // Add key bindings for Y, N, Esc, and C
        JRootPane rootPane = dialog.getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        // Y for Yes
        inputMap.put(KeyStroke.getKeyStroke('y'), "yes");
        inputMap.put(KeyStroke.getKeyStroke('Y'), "yes");
        actionMap.put("yes", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pane.setValue(JOptionPane.YES_OPTION);
                dialog.dispose();
            }
        });

        // N for No
        inputMap.put(KeyStroke.getKeyStroke('n'), "no");
        inputMap.put(KeyStroke.getKeyStroke('N'), "no");
        actionMap.put("no", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pane.setValue(JOptionPane.NO_OPTION);
                dialog.dispose();
            }
        });

        // Esc and C for Cancel
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        inputMap.put(KeyStroke.getKeyStroke('c'), "cancel");
        inputMap.put(KeyStroke.getKeyStroke('C'), "cancel");
        actionMap.put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pane.setValue(JOptionPane.CANCEL_OPTION);
                dialog.dispose();
            }
        });

        dialog.setVisible(true);

        Object value = pane.getValue();
        if (value == null || !(value instanceof Integer)) {
            return JOptionPane.CLOSED_OPTION;
        }
        return (Integer) value;
    }

    private boolean checkUnsavedChanges(KalixDocument document, String messageFormat) {
        boolean promptOnExit = PreferenceManager.getFileBoolean(PreferenceKeys.FILE_PROMPT_SAVE_ON_EXIT, true);

        if (!promptOnExit || !document.isDirty()) {
            return true; // No prompt needed or no unsaved changes
        }

        // Make the document active so Save/Save As target it and the user sees it.
        documentManager.setActiveDocument(document);

        String fileName = document.getFile() != null ? document.getFile().getName() : "Untitled";

        int choice = showUnsavedChangesDialog(
            String.format(messageFormat, fileName),
            "Unsaved Changes"
        );

        switch (choice) {
            case JOptionPane.YES_OPTION:
                try {
                    if (document.getFile() != null) {
                        fileOperations.saveModel();
                    } else {
                        fileOperations.saveAsModel();
                        // If still no file, the user cancelled Save As.
                        if (document.getFile() == null) {
                            return false;
                        }
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to save file: " + e.getMessage(),
                        "Save Error",
                        JOptionPane.ERROR_MESSAGE
                    );
                    return false;
                }
                return true; // Saved successfully, proceed
            case JOptionPane.NO_OPTION:
                return true; // Don't save, but proceed
            case JOptionPane.CANCEL_OPTION:
            default:
                return false; // Cancel the operation
        }
    }

    /**
     * Exits the application gracefully.
     * This will terminate the Java Virtual Machine.
     */
    @Override
    public void exitApplication() {
        // Check for unsaved changes in every open document before exiting.
        for (KalixDocument document : new java.util.ArrayList<>(documentManager.getDocuments())) {
            if (!checkUnsavedChanges(document,
                    "You have unsaved changes in \"%s\".\n\nDo you want to save your changes before closing?")) {
                return; // User cancelled the exit
            }
        }

        // Persist the final session (captures latest caret positions) before tearing down.
        saveSession();

        // Clean up resources before exiting
        // First shutdown CLI sessions to avoid ProcessExecutor waiting
        if (stdioTaskManager != null) {
            stdioTaskManager.getSessionManager().shutdown();
        }
        if (projectTreePanel != null) {
            projectTreePanel.dispose();
        }
        if (fileWatcherManager != null) {
            fileWatcherManager.shutdown();
        }
        if (processExecutor != null) {
            processExecutor.shutdown();
        }
        System.exit(0);
    }

    /**
     * Checks if the main editor has unsaved changes.
     * @return true if there are unsaved changes
     */
    public boolean hasUnsavedChanges() {
        return textEditor != null && textEditor.isDirty();
    }

    /**
     * Gets the current model text from the main editor.
     * @return the model text
     */
    public String getModelText() {
        return textEditor != null ? textEditor.getText() : "";
    }

    /**
     * Sets the model text in the main editor.
     * @param text the text to set
     */
    public void setModelText(String text) {
        if (textEditor != null) {
            textEditor.setText(text);
        }
    }

    /**
     * Sets the model text in the main editor and marks it as dirty (unsaved).
     * Use this when programmatically inserting content that should be saved.
     * @param text the text to set
     */
    public void setModelTextAndMarkDirty(String text) {
        if (textEditor != null) {
            textEditor.setText(text);
            textEditor.setDirty(true);
        }
    }

    /**
     * Performs an undo operation in the text editor.
     * Updates the status bar with the result of the operation.
     */
    @Override
    public void undoAction() {
        if (textEditor.canUndo()) {
            textEditor.undo();
            updateStatus(AppConstants.STATUS_UNDO);
        } else {
            updateStatus(AppConstants.STATUS_NOTHING_TO_UNDO);
        }
    }

    /**
     * Performs a redo operation in the text editor.
     * Updates the status bar with the result of the operation.
     */
    @Override
    public void redoAction() {
        if (textEditor.canRedo()) {
            textEditor.redo();
            updateStatus(AppConstants.STATUS_REDO);
        } else {
            updateStatus(AppConstants.STATUS_NOTHING_TO_REDO);
        }
    }

    @Override
    public void toggleCommentAction() {
        textEditor.toggleComment();
    }

    @Override
    public void normalizeLineEndings() {
        textEditor.normalizeLineEndings();
        updateStatus("Line endings normalized to Unix format (LF)");
    }

    @Override
    public void zoomIn() {
        mapPanel.zoomIn();
        updateStatus(AppConstants.STATUS_ZOOMED_IN);
    }

    @Override
    public void zoomOut() {
        mapPanel.zoomOut();
        updateStatus(AppConstants.STATUS_ZOOMED_OUT);
    }

    @Override
    public void resetZoom() {
        mapPanel.resetZoom();
        updateStatus(AppConstants.STATUS_ZOOM_RESET);
    }
    
    @Override
    public void zoomToFit() {
        mapPanel.zoomToFit();
        updateStatus("Zoomed to fit all nodes");
    }

    @Override
    public void toggleFileTree() {
        if (workspacePanel == null) {
            return;
        }
        if (workspacePanel.isTreeCollapsed()) {
            // Showing the tree: if no folder is open, prompt for one (which expands on success);
            // otherwise just expand. So an empty tree is never shown.
            if (projectTreePanel.getRootFile() == null) {
                openFolder();
            } else {
                workspacePanel.setTreeCollapsed(false);
            }
        } else {
            workspacePanel.setTreeCollapsed(true);
        }
        syncToggleButtonStates();
        updateStatus(workspacePanel.isTreeCollapsed() ? "File tree hidden" : "File tree shown");
    }

    @Override
    public boolean isFileTreeVisible() {
        // Before the workspace panel exists (toolbar build), fall back to the effective initial
        // state so the toolbar button starts correct.
        return workspacePanel != null ? !workspacePanel.isTreeCollapsed() : !computeInitialTreeCollapsed();
    }

    @Override
    public void toggleMap() {
        if (workspacePanel != null) {
            workspacePanel.toggleMap();
            updateStatus(workspacePanel.isMapCollapsed() ? "Map hidden" : "Map shown");
        }
    }

    @Override
    public void flowViz() {
        com.kalix.ide.flowviz.FlowVizWindow.createNewWindow();
    }

    @Override
    public void showParameterSheet() {
        // Single-instance: if already open, bring to front
        if (parameterSheetWindow != null && parameterSheetWindow.isDisplayable()) {
            parameterSheetWindow.toFront();
            parameterSheetWindow.requestFocus();
            return;
        }
        // Pin the sheet to the document it was opened for: its model supplier and its
        // editor must refer to the SAME document, so editing the sheet after a tab switch
        // cannot read one document's model and write another's text.
        KalixDocument document = documentManager.getActiveDocument();
        if (document == null) {
            return;
        }
        parameterSheetWindow = new com.kalix.ide.parametersheet.ParameterSheetWindow(
            this, document.getModelSupplier(), document.getEditor());
        parameterSheetWindow.setVisible(true);
    }


    @Override
    public void showAbout() {
        // Create a JEditorPane for proper hyperlink support
        JEditorPane editorPane = new JEditorPane("text/html",
            "<html>" +
            "<body style='font-family: sans-serif;'>" +
            "<b>" + AppConstants.APP_NAME + " " + AppConstants.APP_VERSION + "</b><br>" +
            AppConstants.APP_DESCRIPTION + "<br>" +
            "<a href='" + AppConstants.APP_WEBSITE_URL + "'>" + AppConstants.APP_WEBSITE_URL + "</a>" +
            "</body></html>");

        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        // Add hyperlink click listener
        editorPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().browse(e.getURL().toURI());
                    }
                } catch (Exception ex) {
                    updateStatus("Error opening website: " + ex.getMessage());
                }
            }
        });

        JOptionPane.showMessageDialog(this, editorPane, "About Kalix IDE", JOptionPane.INFORMATION_MESSAGE);
    }
    
    @Override
    public void openWebsite() {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(AppConstants.APP_WEBSITE_URL));
                    updateStatus("Opening Kalix development website");
                } else {
                    updateStatus("Browser not supported - website: " + AppConstants.APP_WEBSITE_URL);
                }
            } else {
                updateStatus("Desktop not supported - website: " + AppConstants.APP_WEBSITE_URL);
            }
        } catch (Exception e) {
            updateStatus("Error opening website: " + e.getMessage());
        }
    }
    
    
    @Override
    public void showPreferences() {
        // Create callback to handle preference changes
        PreferencesDialog.PreferenceChangeCallback callback = new PreferencesDialog.PreferenceChangeCallback() {
            @Override
            public void onAutoReloadChanged(boolean enabled) {
                // Use the existing method to properly update file watching
                toggleAutoReload(enabled);

                // Update menu bar to reflect the change
                updateMenuBar();

                // Sync toolbar button state
                syncToggleButtonStates();
            }

            @Override
            public void onLintingChanged(boolean enabled) {
                // Sync toolbar button state
                syncToggleButtonStates();
            }

            @Override
            public void onGridlinesChanged(boolean visible) {
                // Sync toolbar button state
                syncToggleButtonStates();
            }

            @Override
            public void onFlowVizPreferencesChanged() {
                // Update all open FlowViz windows with new preferences
                for (com.kalix.ide.flowviz.FlowVizWindow window : com.kalix.ide.flowviz.FlowVizWindow.getOpenWindows()) {
                    window.reloadPreferences();
                }
            }

            @Override
            public void onMapPreferencesChanged() {
                // Update map display with new preferences
                boolean showGridlines = PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true);
                toggleGridlines(showGridlines);

                // Update node theme
                String nodeThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_NODE_THEME, AppConstants.DEFAULT_NODE_THEME);
                com.kalix.ide.themes.NodeTheme.Theme nodeTheme = com.kalix.ide.themes.NodeTheme.themeFromString(nodeThemeName);
                setNodeTheme(nodeTheme);

                // Sync toolbar button state
                syncToggleButtonStates();
            }

            @Override
            public void onSystemActionRequested(String action) {
                if ("clearAppData".equals(action)) {
                    clearAppData();
                }
            }

            @Override
            public void onFontSizeChanged(int fontSize) {
                // Use centralized ThemeManager to update all components
                ThemeManager.notifyFontSizeChanged(fontSize);
            }
        };

        PreferencesDialog preferencesDialog = new PreferencesDialog(this, themeManager, textEditor, schemaManager, callback);
        boolean preferencesChanged = preferencesDialog.showDialog();

        if (preferencesChanged) {
            updateStatus("Preferences updated");
        }
    }
    
    @Override
    public void setNodeTheme(NodeTheme.Theme theme) {
        // Apply to every open document's map so background tabs stay consistent.
        for (KalixDocument document : documentManager.getDocuments()) {
            document.getMapPanel().setNodeTheme(theme);
        }
        // Save the preference
        PreferenceManager.setFileString(PreferenceKeys.UI_NODE_THEME, NodeTheme.themeToString(theme));
    }
    
    public void clearAppData() {
        // Show confirmation dialog
        int result = JOptionPane.showConfirmDialog(
            this,
            "This will clear all Kalix IDE application data including:\n\n" +
            "• Theme preferences\n" +
            "• Node theme preferences\n" +
            "• Recent files list\n" +
            "• Window position and size settings\n" +
            "• Split pane divider positions\n" +
            "• All other saved preferences\n\n" +
            "Are you sure you want to continue?\n\n" +
            "Note: The application will restart after clearing data.",
            "Clear App Data",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            try {
                // Clear all preferences
                prefs.clear();
                
                // Clear recent files
                if (recentFilesManager != null) {
                    recentFilesManager.clearRecentFiles();
                }
                
                updateStatus("App data cleared. Application will restart...");
                
                // Schedule restart after a brief delay to show the status message
                SwingUtilities.invokeLater(() -> {
                    try {
                        Thread.sleep(1000); // Give user time to see the message
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // Exit the application - user will need to restart manually
                    // This is safer than trying to restart programmatically
                    JOptionPane.showMessageDialog(
                        this,
                        "App data has been cleared.\nPlease restart Kalix IDE to continue.",
                        "Restart Required",
                        JOptionPane.INFORMATION_MESSAGE
                    );
                    
                    System.exit(0);
                });
                
            } catch (Exception e) {
                updateStatus("Error clearing app data: " + e.getMessage());
                JOptionPane.showMessageDialog(
                    this,
                    "An error occurred while clearing app data:\n" + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    @Override
    public void searchModel() {
        textEditor.getSearchManager().showFindDialog();
    }
    
    @Override
    public void showFindReplaceDialog() {
        textEditor.getSearchManager().showFindReplaceDialog();
        updateStatus("Find and Replace dialog opened");
    }

    @Override
    public void findNodeOnMap() {
        mapPanel.showFindNodeDialog();
    }

    @Override
    public void showRunManager() {
        RunManager.showRunManager(this, stdioTaskManager, this::updateStatus);
    }

    @Override
    public void showOptimisation() {
        OptimisationWindow.showOptimisationWindow(this, stdioTaskManager, this::updateStatus,
            progressBar,
            () -> {
                // Working directory supplier
                File currentFile = fileOperations.getCurrentFile();
                return currentFile != null ? currentFile.getParentFile() : null;
            },
            () -> {
                // Model text supplier
                return textEditor.getText();
            });
    }

    @Override
    public void showSessionManager() {
        SessionManagerWindow.showSessionManagerWindow(this, stdioTaskManager, this::updateStatus);
    }

    @Override
    public void runModelFromMemory() {
        String modelText = textEditor.getText();
        if (modelText == null || modelText.trim().isEmpty()) {
            updateStatus("Error: No model content to run");
            return;
        }
        
        updateStatus("Starting model run...");
        
        // Run model from memory and handle the session
        stdioTaskManager.runModelFromMemory(modelText)
            .thenAccept(sessionKey -> {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Model session started: " + sessionKey);

                    // Automatically open Run Manager if not already open
                    if (!RunManager.isWindowOpen()) {
                        RunManager.showRunManager(this, stdioTaskManager, this::updateStatus);
                    }
                });
            })
            .exceptionally(throwable -> {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Error starting model session: " + throwable.getMessage());
                });
                return null;
            });
    }
    
    @Override
    public void toggleGridlines(boolean showGridlines) {
        // Apply to every open document's map so background tabs stay consistent.
        for (KalixDocument document : documentManager.getDocuments()) {
            document.getMapPanel().setShowGridlines(showGridlines);
        }
        // Save preference
        PreferenceManager.setFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, showGridlines);
    }
    
    @Override
    public void toggleShowHiddenFiles(boolean show) {
        // Single source of truth: persist to the shareable file-based prefs, then apply to the tree.
        // Both the View-menu checkbox and the tree's right-click checkbox route here.
        PreferenceManager.setFileBoolean(PreferenceKeys.TREE_SHOW_HIDDEN_FILES, show);
        if (projectTreePanel != null) {
            projectTreePanel.setShowHidden(show);
        }
    }

    @Override
    public boolean isShowHiddenFiles() {
        return PreferenceManager.getFileBoolean(PreferenceKeys.TREE_SHOW_HIDDEN_FILES, true);
    }

    @Override
    public boolean isGridlinesVisible() {
        // Falls back to the saved preference before any document/map exists (toolbar build).
        return mapPanel != null
            ? mapPanel.isShowGridlines()
            : PreferenceManager.getFileBoolean(PreferenceKeys.MAP_SHOW_GRIDLINES, true);
    }

    @Override
    public void toggleLinting() {
        boolean currentState = schemaManager.isLintingEnabled();
        boolean newState = !currentState;

        // Update schema manager preferences
        schemaManager.updatePreferences(
            newState,
            null, // Keep current schema path
            schemaManager.getDisabledRules()
        );

        updateStatus(newState ? "Linting enabled" : "Linting disabled");
    }

    @Override
    public boolean isLintingEnabled() {
        return schemaManager.isLintingEnabled();
    }

    @Override
    public void toggleAutoReload(boolean enabled) {
        fileWatcherManager.setAutoReloadEnabled(enabled);
    }

    @Override
    public boolean isAutoReloadEnabled() {
        return fileWatcherManager.isAutoReloadEnabled();
    }

    @Override
    public void copyModelPath() {
        File currentFile = fileOperations.getCurrentFile();
        if (currentFile != null) {
            String path = currentFile.getAbsolutePath();
            java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(path);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            updateStatus("Copied: " + path);
        } else {
            updateStatus("No model file is currently open");
        }
    }

    @Override
    public void openTerminalHere() {
        // Open a terminal at the current model file's folder (or the user's home if no
        // file is open). TerminalLauncher resolves the file → its folder, and
        // TerminalActions handles running off the EDT plus status/error reporting.
        File currentFile = fileOperations.getCurrentFile();
        TerminalActions.launchAsync(this, currentFile, this::updateStatus);
    }

    @Override
    public void openFileManager() {
        File targetDirectory = null;

        // Get the directory of the currently loaded file
        File currentFile = fileOperations.getCurrentFile();
        if (currentFile != null) {
            targetDirectory = currentFile.getParentFile();
        }

        try {
            com.kalix.ide.utils.FileManagerLauncher.openFileManagerAt(targetDirectory);

            // Show success message
            String dirPath = targetDirectory != null ? targetDirectory.getAbsolutePath() : System.getProperty("user.home");
            updateStatus("File manager opened at: " + dirPath);

        } catch (Exception e) {
            String message = "Failed to open file manager: " + e.getMessage();
            updateStatus(message);
            logger.error("Error opening file manager", e);

            // Show error dialog
            JOptionPane.showMessageDialog(
                this,
                message,
                "File Manager Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    @Override
    public void initClaudeMd() {
        initMarkdownFile("CLAUDE.md");
    }

    @Override
    public void initAgentsMd() {
        initMarkdownFile("AGENTS.md");
    }

    @Override
    public void navigateBack() {
        if (textEditor != null) {
            textEditor.navigateBack();
        }
    }

    @Override
    public void navigateForward() {
        if (textEditor != null) {
            textEditor.navigateForward();
        }
    }

    @Override
    public boolean canNavigateBack() {
        return textEditor != null
            && textEditor.getNavigationHistory() != null
            && textEditor.getNavigationHistory().canGoBack();
    }

    @Override
    public boolean canNavigateForward() {
        return textEditor != null
            && textEditor.getNavigationHistory() != null
            && textEditor.getNavigationHistory().canGoForward();
    }

    /**
     * Helper method to create a markdown file from the template in resources.
     * Both CLAUDE.md and AGENTS.md use the same template content.
     *
     * @param fileName The name of the file to create (e.g., "CLAUDE.md" or "AGENTS.md")
     */
    private void initMarkdownFile(String fileName) {
        File currentFile = fileOperations.getCurrentFile();

        if (currentFile == null) {
            updateStatus("No file currently open - please open a model file first");
            JOptionPane.showMessageDialog(
                this,
                "Please open a model file first.\n\n" +
                "The " + fileName + " file will be created in the same directory as your model.",
                "No Model Open",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        File targetDirectory = currentFile.getParentFile();
        File targetFile = new File(targetDirectory, fileName);

        // Check if file already exists
        if (targetFile.exists()) {
            int result = JOptionPane.showConfirmDialog(
                this,
                fileName + " already exists in this directory.\n\n" +
                "Do you want to overwrite it?",
                "File Exists",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            if (result != JOptionPane.YES_OPTION) {
                updateStatus(fileName + " creation cancelled");
                return;
            }
        }

        try {
            // Read template from resources
            String templateContent;
            try (java.io.InputStream is = getClass().getResourceAsStream("/CLAUDE.md")) {
                if (is == null) {
                    throw new RuntimeException("Template file not found in resources");
                }
                templateContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }

            // Write to target file
            java.nio.file.Files.writeString(targetFile.toPath(), templateContent, java.nio.charset.StandardCharsets.UTF_8);

            updateStatus(fileName + " created in: " + targetDirectory.getAbsolutePath());

            // Show success dialog
            JOptionPane.showMessageDialog(
                this,
                "Created '" + targetFile.getAbsolutePath() + "'",
                "Done",
                JOptionPane.INFORMATION_MESSAGE
            );

        } catch (Exception e) {
            String message = "Failed to create " + fileName + ": " + e.getMessage();
            updateStatus(message);
            logger.error("Error creating " + fileName, e);

            JOptionPane.showMessageDialog(
                this,
                message,
                "Error Creating File",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    @Override
    public void openExternalEditor() {
        File currentFile = fileOperations.getCurrentFile();

        if (currentFile == null) {
            updateStatus("No file currently open to edit externally");
            return;
        }

        // Get the external editor command from preferences
        String commandTemplate = PreferenceManager.getFileString(
            PreferenceKeys.FILE_EXTERNAL_EDITOR_COMMAND,
            "code <folder_path> <file_path>"
        );

        if (commandTemplate.trim().isEmpty()) {
            updateStatus("External editor command not configured in preferences");
            return;
        }

        try {
            // Replace placeholders with actual paths
            String folderPath = currentFile.getParentFile().getAbsolutePath();
            String filePath = currentFile.getAbsolutePath();

            String command = commandTemplate
                .replace("<folder_path>", folderPath)
                .replace("<file_path>", filePath);

            // Split command into parts for ProcessBuilder
            String[] commandParts = command.split("\\s+");

            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentFile.getParentFile());
            processBuilder.start();

            updateStatus("External editor launched: " + currentFile.getName());

        } catch (Exception e) {
            String message = "Failed to launch external editor: " + e.getMessage();
            updateStatus(message);
            logger.error("Error launching external editor", e);

            // Show error dialog with helpful information
            JOptionPane.showMessageDialog(
                this,
                message + "\n\n" +
                "Please check your external editor command in File → Preferences → File.\n" +
                "Current command: " + commandTemplate,
                "External Editor Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    /**
     * Handles file reload when external changes are detected.
     * Only reloads if the file is clean (no unsaved changes).
     *
     * @param file The file that has changed
     */
    private void handleFileReload(File file) {
        // Find the document backing this file (the watcher follows the active document).
        KalixDocument document = documentManager.findByFile(file);
        if (document == null) {
            document = documentManager.getActiveDocument();
        }

        // Only reload in place if the document is clean (no unsaved changes).
        if (document != null && !document.isDirty()) {
            fileOperations.reloadFile(file);
        } else {
            // File is dirty, don't auto-reload but notify user
            updateStatus("File changed externally, but has unsaved changes - not reloaded");
        }
    }

    /**
     * Updates the toolbar with theme-appropriate icon colors.
     * Called when the theme changes.
     */
    public void updateToolBar() {
        if (toolBar != null) {
            // Remove the old toolbar
            remove(toolBar);
            
            // Create a new toolbar with updated theme colors
            ToolBarBuilder toolBarBuilder = new ToolBarBuilder(this);
            ToolBarBuilder.ToolBarComponents components = toolBarBuilder.buildToolBar();
            toolBar = components.toolBar;
            lintingToggleButton = components.lintingToggleButton;
            autoReloadToggleButton = components.autoReloadToggleButton;
            gridlinesToggleButton = components.gridlinesToggleButton;
            backButton = components.backButton;
            forwardButton = components.forwardButton;
            setupNavigationStateListener();
            add(toolBar, BorderLayout.NORTH);
            
            // Revalidate the layout
            revalidate();
            repaint();
        }
    }

    /**
     * Attempts to load the last opened file from OS preferences.
     * If the file doesn't exist or no preference is set, falls back to default behavior.
     */
    private void loadLastOpenedFile() {
        String lastFilePath = PreferenceManager.getOsString(PreferenceKeys.LAST_OPENED_FILE, "");

        if (!lastFilePath.isEmpty()) {
            File lastFile = new File(lastFilePath);
            if (lastFile.exists() && lastFile.isFile() && fileOperations.isKalixModelFile(lastFile)) {
                // File exists and is valid, load it
                fileOperations.loadModelFile(lastFile);
            }
        }

        // No last file, file doesn't exist, or invalid file type
        // Fall back to default behavior (new model with default text is already set)
    }

    /**
     * Main entry point for the Kalix IDE application.
     * 
     * @param args command line arguments; optionally pass a file path as the first argument to open on startup
     */
    public static void main(String[] args) {
        String filePath = args.length > 0 ? args[0] : null;
        // Initialize Windows-specific integration (AppUserModelID for taskbar pinning)
        // Must be called early, before any UI is created
        WindowsIntegration.initialize();

        // Configure system properties for better macOS integration
        ThemeManager.configureSystemProperties();

        // Initialize embedded fonts early to ensure they're available
        FontManager.initialize();

        SwingUtilities.invokeLater(() -> {
            try {
                // Initialize theme from preferences
                Preferences prefs = Preferences.userNodeForPackage(KalixIDE.class);
                ThemeManager tempThemeManager = new ThemeManager(prefs, null);
                tempThemeManager.initializeLookAndFeel();

            } catch (UnsupportedLookAndFeelException e) {
                logger.error("Failed to initialize FlatLaf look and feel: {}", e.getMessage(), e);
            }

            // Configure global tooltip settings for more responsive tooltips
            ToolTipManager.sharedInstance().setEnabled(true);
            ToolTipManager.sharedInstance().setInitialDelay(200);   // 200ms before tooltip appears
            ToolTipManager.sharedInstance().setDismissDelay(4000);  // 4 seconds visible
            ToolTipManager.sharedInstance().setReshowDelay(100);    // 100ms for quick re-show

            // Create the main application window
            new KalixIDE(filePath);
        });
    }
}