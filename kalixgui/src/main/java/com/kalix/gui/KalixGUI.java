package com.kalix.gui;

import com.kalix.gui.builders.MenuBarBuilder;
import com.kalix.gui.builders.ToolBarBuilder;
import com.kalix.gui.cli.InteractiveKalixProcess;
import com.kalix.gui.cli.ProcessExecutor;
import com.kalix.gui.cli.KalixCliLocator;
import com.kalix.gui.components.StatusProgressBar;
import com.kalix.gui.constants.AppConstants;
import com.kalix.gui.dialogs.PreferencesDialog;
import com.kalix.gui.editor.EnhancedTextEditor;
import com.kalix.gui.handlers.FileDropHandler;
import com.kalix.gui.managers.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private StatusProgressBar progressBar;
    private JSplitPane splitPane;
    
    // Manager classes for specialized functionality
    private ThemeManager themeManager;
    private RecentFilesManager recentFilesManager;
    private FileOperationsManager fileOperations;
    private FontDialogManager fontDialogManager;
    private FileDropHandler fileDropHandler;
    private ModelRunner modelRunner;
    private VersionChecker versionChecker;
    private TitleBarManager titleBarManager;
    private ProcessExecutor processExecutor;
    
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
        setupWindowListeners();
        
        // Load saved preferences
        fontDialogManager.loadFontPreferences();
        loadLineWrapPreference();
        loadEditorThemePreference();
        loadSplitPaneDividerPosition();
        
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
        
        // Title bar manager
        titleBarManager = new TitleBarManager(this);
        
        // Process executor for CLI operations
        processExecutor = new ProcessExecutor();
        
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
        
        progressBar = new StatusProgressBar();
        
        // Complete manager initialization now that components exist
        fileOperations = new FileOperationsManager(
            this, textEditor, mapPanel,
            this::updateStatus,
            recentFilesManager::addRecentFile,
            () -> titleBarManager.updateTitle(textEditor.isDirty(), fileOperations::getCurrentFile) // File change callback for title bar updates
        );
        
        fontDialogManager = new FontDialogManager(this, textEditor, prefs);
        fileDropHandler = new FileDropHandler(fileOperations, this::updateStatus);
        modelRunner = new ModelRunner(this, this::updateStatus);
        versionChecker = new VersionChecker(this::updateStatus);
        
        // Set up component listeners
        textEditor.setDirtyStateListener(isDirty -> titleBarManager.updateTitle(isDirty, fileOperations::getCurrentFile));
        textEditor.setFileDropHandler(fileOperations::loadModelFile);
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
        splitPane = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(mapPanel),
            textEditor  // Enhanced text editor already includes scroll pane
        );
        splitPane.setDividerLocation(AppConstants.DEFAULT_SPLIT_PANE_DIVIDER_LOCATION);
        splitPane.setResizeWeight(AppConstants.DEFAULT_SPLIT_PANE_RESIZE_WEIGHT);
        
        // Add property change listener to save divider position when it changes
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            saveSplitPaneDividerPosition();
        });
        
        add(splitPane, BorderLayout.CENTER);
        
        // Add status bar at bottom
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.EAST);
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
     * Sets up window event listeners including resize listener for dynamic title bar updates.
     */
    private void setupWindowListeners() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                // Update title bar when window is resized to ensure path still fits
                titleBarManager.updateTitle(textEditor.isDirty(), fileOperations::getCurrentFile);
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
     * Convenience method for loading model files from file paths.
     * Used by recent files manager callback.
     * 
     * @param filePath The absolute path to the file to load
     */
    private void loadModelFile(String filePath) {
        fileOperations.loadModelFile(filePath);
    }
    
    /**
     * Loads the line wrap preference and applies it to the text editor.
     */
    private void loadLineWrapPreference() {
        boolean savedLineWrap = prefs.getBoolean(AppConstants.PREF_LINE_WRAP, true);
        textEditor.setLineWrap(savedLineWrap);
    }
    
    /**
     * Loads the editor theme preference and applies it to the text editor.
     */
    private void loadEditorThemePreference() {
        String savedTheme = prefs.get(AppConstants.PREF_EDITOR_THEME, "GitHub Light Colorblind");
        textEditor.setEditorTheme(savedTheme);
    }
    
    /**
     * Loads the saved split pane divider position and applies it to the split pane.
     * Uses the default location if no saved preference exists.
     */
    private void loadSplitPaneDividerPosition() {
        int savedPosition = prefs.getInt(AppConstants.PREF_SPLIT_PANE_DIVIDER, AppConstants.DEFAULT_SPLIT_PANE_DIVIDER_LOCATION);
        // Set the position after the window is visible to ensure proper calculation
        SwingUtilities.invokeLater(() -> {
            splitPane.setDividerLocation(savedPosition);
        });
    }
    
    /**
     * Saves the current split pane divider position to preferences.
     */
    private void saveSplitPaneDividerPosition() {
        if (splitPane != null) {
            int currentPosition = splitPane.getDividerLocation();
            prefs.putInt(AppConstants.PREF_SPLIT_PANE_DIVIDER, currentPosition);
        }
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
    public void showPreferences() {
        PreferencesDialog preferencesDialog = new PreferencesDialog(this, themeManager, fontDialogManager, textEditor);
        boolean preferencesChanged = preferencesDialog.showDialog();
        
        if (preferencesChanged) {
            updateStatus("Preferences updated");
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
    
    @Override
    public void runTestSimulation() {
        runTestSimulationAsync();
    }
    
    /**
     * Runs a test simulation using kalixcli with progress monitoring.
     * This demonstrates the interactive CLI integration with real-time progress updates.
     */
    private void runTestSimulationAsync() {
        // Pattern to match progress output: "Progress: 45%"
        Pattern progressPattern = Pattern.compile("Progress:\\s*(\\d+(?:\\.\\d+)?)%");
        
        CompletableFuture.runAsync(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCli();
                
                if (!cliLocation.isPresent()) {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus("Error: kalixcli not found");
                        JOptionPane.showMessageDialog(this,
                            "kalixcli executable not found. Please ensure it's installed and in your PATH.",
                            "CLI Not Found", JOptionPane.ERROR_MESSAGE);
                    });
                    return;
                }
                
                // Update UI to show simulation starting
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Starting test simulation...");
                    progressBar.showProgress(0.0, "0%");
                });
                
                // Start interactive process
                InteractiveKalixProcess process = InteractiveKalixProcess.start(
                    cliLocation.get().getPath(), processExecutor, "test", "--sim-duration-seconds=8");
                
                try {
                    // Monitor output for progress updates
                    String line;
                    boolean completed = false;
                    
                    while (process.isRunning() && !completed) {
                        Optional<String> outputLine = process.readOutputLine();
                        if (outputLine.isPresent()) {
                            line = outputLine.get();
                            System.out.println("CLI Output: " + line); // Debug output
                            
                            // Check if this is a progress line
                            Matcher matcher = progressPattern.matcher(line);
                            if (matcher.find()) {
                                double percentage = Double.parseDouble(matcher.group(1));
                                
                                SwingUtilities.invokeLater(() -> {
                                    progressBar.setProgressPercentage(percentage);
                                    progressBar.setProgressText(String.format("%.0f%%", percentage));
                                    updateStatus("Test simulation: " + String.format("%.0f%%", percentage));
                                });
                            }
                            
                            // Check for completion message (separate check, not else-if)
                            if (line.contains("Simulation completed!")) {
                                completed = true;
                                SwingUtilities.invokeLater(() -> {
                                    progressBar.setProgressPercentage(100);
                                    progressBar.setProgressText("Complete");
                                    updateStatus("Test simulation completed successfully!");
                                });
                                
                                // Hide progress bar after a short delay
                                Timer hideTimer = new Timer(2000, e -> {
                                    progressBar.hideProgress();
                                    updateStatus(AppConstants.STATUS_READY);
                                });
                                hideTimer.setRepeats(false);
                                SwingUtilities.invokeLater(hideTimer::start);
                                
                                break;
                            }
                        } else if (!process.isRunning()) {
                            // Process ended but no completion message - check if we need to force completion
                            SwingUtilities.invokeLater(() -> {
                                if (progressBar.isProgressVisible()) {
                                    progressBar.setProgressPercentage(100);
                                    progressBar.setProgressText("Complete");
                                    updateStatus("Test simulation completed!");
                                    
                                    Timer hideTimer = new Timer(2000, e -> {
                                        progressBar.hideProgress();
                                        updateStatus(AppConstants.STATUS_READY);
                                    });
                                    hideTimer.setRepeats(false);
                                    hideTimer.start();
                                }
                            });
                            break;
                        }
                        
                        // Small delay to avoid busy waiting
                        Thread.sleep(50);
                    }
                    
                    // Final check - read any remaining output after process ends
                    if (!completed) {
                        while (true) {
                            Optional<String> outputLine = process.readOutputLine();
                            if (outputLine.isPresent()) {
                                line = outputLine.get();
                                System.out.println("Final CLI Output: " + line);
                                
                                if (line.contains("Simulation completed!")) {
                                    SwingUtilities.invokeLater(() -> {
                                        progressBar.setProgressPercentage(100);
                                        progressBar.setProgressText("Complete");
                                        updateStatus("Test simulation completed successfully!");
                                        
                                        Timer hideTimer = new Timer(2000, e -> {
                                            progressBar.hideProgress();
                                            updateStatus(AppConstants.STATUS_READY);
                                        });
                                        hideTimer.setRepeats(false);
                                        hideTimer.start();
                                    });
                                    break;
                                }
                            } else {
                                break; // No more output
                            }
                        }
                    }
                    
                } finally {
                    process.close();
                }
                
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.hideProgress();
                    updateStatus("Error: Failed to start test simulation");
                    JOptionPane.showMessageDialog(this,
                        "Failed to start test simulation: " + e.getMessage(),
                        "Simulation Error", JOptionPane.ERROR_MESSAGE);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SwingUtilities.invokeLater(() -> {
                    progressBar.hideProgress();
                    updateStatus("Test simulation interrupted");
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.hideProgress();
                    updateStatus("Error: Test simulation failed");
                    JOptionPane.showMessageDialog(this,
                        "Test simulation failed: " + e.getMessage(),
                        "Simulation Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
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