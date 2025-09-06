package com.kalix.gui;

import com.kalix.gui.builders.MenuBarBuilder;
import com.kalix.gui.builders.ToolBarBuilder;
import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.dialogs.SettingsDialog;
import com.kalix.gui.editor.EnhancedTextEditor;
import com.kalix.gui.handlers.FileDropHandler;
import com.kalix.gui.managers.*;

import javax.swing.*;
import java.awt.*;
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
public class KalixGUI extends JFrame implements MenuBarBuilder.MenuBarCallbacks {
    // Core UI components
    private MapPanel mapPanel;
    private EnhancedTextEditor textEditor;
    private JLabel statusLabel;
    
    // Manager classes for specialized functionality
    private ThemeManager themeManager;
    private RecentFilesManager recentFilesManager;
    private FileOperationsManager fileOperations;
    private FontDialogManager fontDialogManager;
    private FileDropHandler fileDropHandler;
    private ModelRunner modelRunner;
    private VersionChecker versionChecker;
    
    // Application state
    private Preferences prefs;

    /**
     * Creates a new KalixGUI instance.
     * Initializes all components, managers, and sets up the user interface.
     */
    public KalixGUI() {
        initializeApplication();
    }
    
    /**
     * Initializes the application by setting up all managers and components.
     */
    private void initializeApplication() {
        // Initialize preferences
        prefs = Preferences.userNodeForPackage(KalixGUI.class);
        
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
        
        // Load saved preferences
        fontDialogManager.loadFontPreferences();
        
        setVisible(true);
    }
    
    /**
     * Sets up basic window properties.
     */
    private void setupWindow() {
        setTitle(AppConstants.APP_NAME);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(AppConstants.DEFAULT_WINDOW_WIDTH, AppConstants.DEFAULT_WINDOW_HEIGHT);
        setLocationRelativeTo(null);
    }
    
    /**
     * Initializes all manager classes.
     */
    private void initializeManagers() {
        // Theme manager
        themeManager = new ThemeManager(prefs, this);
        
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
        // Initialize core components
        mapPanel = new MapPanel();
        textEditor = new EnhancedTextEditor();
        textEditor.setText(AppConstants.DEFAULT_MODEL_TEXT);
        
        statusLabel = new JLabel(AppConstants.STATUS_READY);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H,
            AppConstants.STATUS_LABEL_BORDER_V, AppConstants.STATUS_LABEL_BORDER_H
        ));
        
        // Complete manager initialization now that components exist
        fileOperations = new FileOperationsManager(
            this, textEditor, mapPanel,
            this::updateStatus,
            recentFilesManager::addRecentFile
        );
        
        fontDialogManager = new FontDialogManager(this, textEditor, prefs);
        fileDropHandler = new FileDropHandler(fileOperations, this::updateStatus);
        modelRunner = new ModelRunner(this, this::updateStatus);
        versionChecker = new VersionChecker(this::updateStatus);
        
        // Set up component listeners
        textEditor.setDirtyStateListener(this::updateWindowTitle);
        textEditor.setFileDropHandler(fileOperations::loadModelFile);
    }
    
    /**
     * Updates the window title to reflect dirty file state.
     * 
     * @param isDirty true if the file has unsaved changes
     */
    private void updateWindowTitle(boolean isDirty) {
        String title = AppConstants.APP_NAME;
        if (isDirty) {
            title = "*" + title;
        }
        setTitle(title);
    }

    /**
     * Sets up the main application layout.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Add toolbar at top
        ToolBarBuilder toolBarBuilder = new ToolBarBuilder(this);
        JToolBar toolBar = toolBarBuilder.buildToolBar();
        add(toolBar, BorderLayout.NORTH);
        
        // Create split pane with map panel on left, text editor on right
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(mapPanel),
            textEditor  // Enhanced text editor already includes scroll pane
        );
        splitPane.setDividerLocation(AppConstants.DEFAULT_SPLIT_PANE_DIVIDER_LOCATION);
        splitPane.setResizeWeight(AppConstants.DEFAULT_SPLIT_PANE_RESIZE_WEIGHT);
        
        add(splitPane, BorderLayout.CENTER);
        
        // Add status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        add(statusPanel, BorderLayout.SOUTH);
    }

    /**
     * Sets up the application menu bar using MenuBarBuilder.
     */
    private void setupMenuBar() {
        MenuBarBuilder menuBuilder = new MenuBarBuilder(this, textEditor);
        JMenuBar menuBar = menuBuilder.buildMenuBar(themeManager.getCurrentTheme());
        setJMenuBar(menuBar);
        
        // Update recent files menu
        recentFilesManager.updateRecentFilesMenu(menuBuilder.getRecentFilesMenu());
    }
    
    /**
     * Sets up drag and drop functionality using FileDropHandler.
     */
    private void setupDragAndDrop() {
        fileDropHandler.setupDragAndDrop(this);
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
     * Convenience method for loading model files from file paths.
     * Used by recent files manager callback.
     * 
     * @param filePath The absolute path to the file to load
     */
    private void loadModelFile(String filePath) {
        fileOperations.loadModelFile(filePath);
    }

    //
    // MenuBarCallbacks interface implementation
    //
    
    @Override
    public void newModel() {
        fileOperations.newModel();
    }

    @Override
    public void openModel() {
        fileOperations.openModel();
    }
    
    @Override
    public void saveModel() {
        fileOperations.saveModel();
    }

    @Override
    public void saveAsModel() {
        fileOperations.saveAsModel();
    }

    @Override
    public void exitApplication() {
        System.exit(0);
    }

    @Override
    public void undoAction() {
        if (textEditor.canUndo()) {
            textEditor.undo();
            updateStatus(AppConstants.STATUS_UNDO);
        } else {
            updateStatus(AppConstants.STATUS_NOTHING_TO_UNDO);
        }
    }

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
    public void cutAction() {
        textEditor.cut();
        updateStatus(AppConstants.STATUS_CUT);
    }

    @Override
    public void copyAction() {
        textEditor.copy();
        updateStatus(AppConstants.STATUS_COPY);
    }

    @Override
    public void pasteAction() {
        textEditor.paste();
        updateStatus(AppConstants.STATUS_PASTE);
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
    public void flowViz() {
        com.kalix.gui.flowviz.FlowVizWindow.createNewWindow();
        updateStatus(AppConstants.STATUS_FLOWVIZ_OPENED);
    }

    @Override
    public void showSplashScreen() {
        SplashScreen.showSplashScreen();
        updateStatus(AppConstants.STATUS_SPLASH_DISPLAYED);
    }

    @Override
    public void showAbout() {
        JOptionPane.showMessageDialog(this,
            AppConstants.APP_NAME + "\nVersion " + AppConstants.APP_VERSION + "\n\n" + AppConstants.APP_DESCRIPTION,
            "About Kalix GUI",
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    @Override
    public void showFontDialog() {
        fontDialogManager.showFontDialog();
    }
    
    @Override
    public void showSettings() {
        SettingsDialog settingsDialog = new SettingsDialog(this, themeManager, fontDialogManager);
        boolean settingsChanged = settingsDialog.showDialog();
        
        if (settingsChanged) {
            updateStatus("Settings updated");
        }
    }
    
    @Override
    public String switchTheme(String theme) {
        return themeManager.switchTheme(theme);
    }
    
    @Override
    public void runModel() {
        // Check if there's a current file loaded
        if (!fileOperations.hasCurrentFile()) {
            updateStatus("No model file is loaded. Please open a model file first.");
            return;
        }
        
        // Check if the file has unsaved changes
        if (textEditor.isDirty()) {
            int result = JOptionPane.showConfirmDialog(
                this,
                "The model has unsaved changes. Save before running?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );
            
            if (result == JOptionPane.YES_OPTION) {
                // Save the file first
                fileOperations.saveModel();
                // Check if save was successful (file might still be dirty if save failed)
                if (textEditor.isDirty()) {
                    updateStatus("Cannot run model: failed to save file");
                    return;
                }
            } else if (result == JOptionPane.CANCEL_OPTION) {
                // User cancelled
                return;
            }
            // If NO_OPTION, proceed with the existing file on disk
        }
        
        // Run the model simulation
        modelRunner.runModelWithDialog(fileOperations.getCurrentFile());
    }
    
    @Override
    public void searchModel() {
        // Placeholder implementation for search functionality
        updateStatus(AppConstants.STATUS_SEARCH_NOT_IMPLEMENTED);
        // TODO: Implement search dialog
        // This could involve:
        // 1. Creating a search dialog with find/replace functionality
        // 2. Highlighting search results in the text editor
        // 3. Navigation between search results
        // 4. Regular expression support
    }
    
    @Override
    public void getCliVersion() {
        versionChecker.checkVersionWithStatusUpdate();
    }

    /**
     * Main entry point for the Kalix GUI application.
     * 
     * @param args command line arguments (not used)
     */
    public static void main(String[] args) {
        // Configure system properties for better macOS integration
        ThemeManager.configureSystemProperties();
        
        // Show splash screen first
        SplashScreen.showSplashScreen();
        
        SwingUtilities.invokeLater(() -> {
            try {
                // Initialize theme from preferences
                Preferences prefs = Preferences.userNodeForPackage(KalixGUI.class);
                ThemeManager tempThemeManager = new ThemeManager(prefs, null);
                tempThemeManager.initializeLookAndFeel();
                
            } catch (UnsupportedLookAndFeelException e) {
                System.err.println(AppConstants.ERROR_FAILED_FLATLAF_INIT + e.getMessage());
                e.printStackTrace();
            }
            
            // Create the main application window
            new KalixGUI();
        });
    }
}