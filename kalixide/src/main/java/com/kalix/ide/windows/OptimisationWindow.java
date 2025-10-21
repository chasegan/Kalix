package com.kalix.ide.windows;

import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.KalixCliLocator;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.TreeSelectionEvent;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manager-style window for configuring and running model optimisation tasks.
 * Parallels RunManager design with tree-based tracking of multiple optimisations.
 *
 * Features:
 * - Track multiple optimisations simultaneously
 * - Tree view with status visualization
 * - Configuration editor for new optimisations
 * - Results display for completed optimisations
 * - Context menu for management operations (rename, remove, etc.)
 */
public class OptimisationWindow extends JFrame {

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final Supplier<File> workingDirectorySupplier;
    private final Supplier<String> modelTextSupplier;

    // Tree components
    private JTree optTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode currentOptimisationsNode;

    // Main panel components
    private JPanel optimisationPanel;
    private JTabbedPane mainTabbedPane;

    // Tab components
    private OptimisationGuiBuilder guiBuilder;
    private RSyntaxTextArea configEditor;
    private JTextArea resultsDisplayArea;  // Results/progress display
    private JButton runButton;

    // Track currently displayed node to save config when switching
    private DefaultMutableTreeNode currentlyDisplayedNode = null;

    // Optimisation tracking
    private Map<String, String> sessionToOptName = new HashMap<>();
    private Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private Map<String, OptimisationStatus> lastKnownStatus = new HashMap<>();
    private Map<String, OptimisationResult> optimisationResults = new HashMap<>();
    private int optCounter = 1;

    // Flag to prevent selection listener from firing during programmatic updates
    private boolean isUpdatingSelection = false;

    private static OptimisationWindow instance;

    /**
     * Private constructor for singleton pattern.
     */
    private OptimisationWindow(JFrame parentFrame,
                               StdioTaskManager stdioTaskManager,
                               Consumer<String> statusUpdater,
                               StatusProgressBar progressBar,
                               Supplier<File> workingDirectorySupplier,
                               Supplier<String> modelTextSupplier) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.progressBar = progressBar;
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.modelTextSupplier = modelTextSupplier;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
    }

    /**
     * Shows the Optimisation window using singleton pattern.
     */
    public static void showOptimisationWindow(JFrame parentFrame,
                                              StdioTaskManager stdioTaskManager,
                                              Consumer<String> statusUpdater,
                                              StatusProgressBar progressBar,
                                              Supplier<File> workingDirectorySupplier,
                                              Supplier<String> modelTextSupplier) {
        if (instance == null) {
            instance = new OptimisationWindow(parentFrame, stdioTaskManager,
                statusUpdater, progressBar, workingDirectorySupplier, modelTextSupplier);
        }

        // Update simulated series options from current model
        instance.updateSimulatedSeriesOptionsFromModel();

        // Auto-generate parameter expressions to pre-populate the table
        instance.guiBuilder.autoGenerateParameterExpressions();

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Optimiser");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(900, 700);

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 30, parentLocation.y + 30);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // ===== Tree Structure =====
        rootNode = new DefaultMutableTreeNode("Optimisations");
        currentOptimisationsNode = new DefaultMutableTreeNode("Optimisation runs");

        rootNode.add(currentOptimisationsNode);

        treeModel = new DefaultTreeModel(rootNode);
        optTree = new JTree(treeModel);
        optTree.setRootVisible(false);
        optTree.setShowsRootHandles(true);
        optTree.setCellRenderer(new OptimisationTreeCellRenderer());
        optTree.addTreeSelectionListener(this::onOptTreeSelectionChanged);

        // Setup context menu
        setupContextMenu();

        // Expand "Optimisation runs" node by default
        optTree.expandRow(0);

        // ===== Single Optimisation Panel with Tabs =====
        optimisationPanel = new JPanel(new BorderLayout(10, 10));
        optimisationPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create main tabbed pane
        mainTabbedPane = new JTabbedPane();

        // Tab 1: Config (GUI Builder)
        guiBuilder = new OptimisationGuiBuilder(generatedConfig -> {
            // Set the generated config in the text editor
            configEditor.setText(generatedConfig);
            // Switch to the Config INI tab
            mainTabbedPane.setSelectedIndex(1);
        }, workingDirectorySupplier);
        mainTabbedPane.addTab("Config", guiBuilder);

        // Tab 2: Config INI (Text Editor)
        configEditor = new RSyntaxTextArea(20, 60);
        configEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        configEditor.setCodeFoldingEnabled(true);
        configEditor.setAntiAliasingEnabled(true);
        configEditor.setTabSize(4);
        configEditor.setTabsEmulated(true);

        // Set default template
        configEditor.setText("""
                [General]
                observed_data_by_name = ../data.csv.ObsFlow
                simulated_series = node.mygr4jnode.ds_1
                objective_function = NSE
                output_file = optimisation_results.txt

                [Algorithm]
                algorithm = DE
                population_size = 50
                termination_evaluations = 5000
                de_f = 0.8
                de_cr = 0.9
                n_threads = 4
                # random_seed = 42

                [Parameters]
                # GR4J parameter bounds (based on literature ranges)
                node.mygr4jnode.x1 = lin_range(g(1), 10, 2000)
                node.mygr4jnode.x2 = lin_range(g(2), -8, 6)
                node.mygr4jnode.x3 = lin_range(g(3), 10, 500)
                node.mygr4jnode.x4 = lin_range(g(4), 0.0001, 4.0)
                """);

        RTextScrollPane configScrollPane = new RTextScrollPane(configEditor);
        mainTabbedPane.addTab("Config INI", configScrollPane);

        // Tab 3: Results (initially hidden, shown after run starts)
        resultsDisplayArea = new JTextArea(20, 60);
        resultsDisplayArea.setEditable(false);
        resultsDisplayArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane resultsScrollPane = new JScrollPane(resultsDisplayArea);
        // Results tab will be added dynamically when optimization starts

        optimisationPanel.add(mainTabbedPane, BorderLayout.CENTER);

        // Buttons panel at bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        JButton generateConfigButton = new JButton("Generate Config INI");
        generateConfigButton.addActionListener(e -> guiBuilder.generateAndSwitchToTextEditor());
        buttonPanel.add(generateConfigButton);

        JButton loadConfigButton = new JButton("Load Config");
        loadConfigButton.addActionListener(e -> loadConfig());
        buttonPanel.add(loadConfigButton);

        JButton saveConfigButton = new JButton("Save Config");
        saveConfigButton.addActionListener(e -> saveConfig());
        buttonPanel.add(saveConfigButton);

        runButton = new JButton("Run Optimisation");
        runButton.addActionListener(e -> runOptimisation());
        buttonPanel.add(runButton);

        optimisationPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Initially hide the optimisation panel (will show when first node is created)
        optimisationPanel.setVisible(false);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Left panel: Tree + Button
        JPanel leftPanel = new JPanel(new BorderLayout(0, 5));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JScrollPane treeScrollPane = new JScrollPane(optTree);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Button panel at bottom of left side
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        JButton newOptButton = new JButton("New Optimisation");
        newOptButton.setIcon(FontIcon.of(FontAwesomeSolid.PLUS, 14, new Color(0, 120, 0)));
        newOptButton.addActionListener(e -> createNewOptimisation());
        buttonPanel.add(newOptButton);
        leftPanel.add(buttonPanel, BorderLayout.SOUTH);

        leftPanel.setPreferredSize(new Dimension(220, 0));

        // Create horizontal split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(optimisationPanel);
        splitPane.setDividerLocation(220);
        splitPane.setResizeWeight(0.0);  // Tree stays fixed width when resizing

        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * Creates a new optimisation node, starts a session, and selects it.
     * This is called when the user clicks the "+ New Optimisation" button.
     */
    private void createNewOptimisation() {
        // Get model from the main editor
        String modelText = modelTextSupplier != null ? modelTextSupplier.get() : null;
        if (modelText == null || modelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No model loaded in the editor.\nPlease load a model first.",
                "No Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String finalModelText = modelText;

        // Start new session
        SwingUtilities.invokeLater(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    handleError("kalixcli not found");
                    return;
                }

                // Configure session
                SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session");

                // Set working directory if available
                File workingDir = workingDirectorySupplier.get();
                if (workingDir != null) {
                    config.workingDirectory(workingDir.toPath());
                }

                // Start session
                stdioTaskManager.getSessionManager().startSession(cliLocation.get().getPath(), config)
                    .thenAccept(sessionKey -> {
                        // Create optimisation program
                        OptimisationProgram program = new OptimisationProgram(
                            sessionKey,
                            stdioTaskManager.getSessionManager(),
                            statusMsg -> handleStatusUpdate(sessionKey, statusMsg),
                            progressInfo -> handleOptimisationProgress(sessionKey, progressInfo),
                            result -> handleOptimisationResult(sessionKey, result)
                        );

                        // Register program with session
                        stdioTaskManager.getSessionManager().getSession(sessionKey).ifPresent(session -> {
                            session.setActiveProgram(program);

                            // Create optimisation info
                            String optName = "Optimisation_" + optCounter++;
                            OptimisationInfo optInfo = new OptimisationInfo(optName, session);
                            optInfo.configSnapshot = configEditor.getText(); // Use current default config
                            optInfo.result = new OptimisationResult();  // Create empty result for tracking

                            // Add to tracking maps
                            sessionToOptName.put(sessionKey, optName);
                            optimisationResults.put(sessionKey, optInfo.result);
                            lastKnownStatus.put(sessionKey, OptimisationStatus.STARTING);

                            // Add to tree
                            DefaultMutableTreeNode optNode = new DefaultMutableTreeNode(optInfo);
                            currentOptimisationsNode.add(optNode);
                            sessionToTreeNode.put(sessionKey, optNode);

                            // Update tree
                            treeModel.nodeStructureChanged(currentOptimisationsNode);
                            optTree.expandPath(new TreePath(currentOptimisationsNode.getPath()));

                            // Select the new optimisation
                            TreePath optPath = new TreePath(optNode.getPath());
                            optTree.setSelectionPath(optPath);

                            // Show the optimisation panel
                            optimisationPanel.setVisible(true);

                            // Initialize the optimisation (load model)
                            program.initialize(finalModelText);

                            if (statusUpdater != null) {
                                statusUpdater.accept("Created " + optName);
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        handleError("Failed to start session: " + throwable.getMessage());
                        return null;
                    });

            } catch (Exception e) {
                handleError("Error creating optimisation: " + e.getMessage());
            }
        });
    }

    /**
     * Saves the current config from GUI/text editor back to the node's configSnapshot.
     * Called when switching tree selections or before running optimization.
     */
    private void saveCurrentConfigToNode() {
        if (currentlyDisplayedNode == null) return;

        if (!(currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();

        // Only save if the optimization hasn't started running yet
        if (!optInfo.hasStartedRunning) {
            // Save the config text from the editor (it's the source of truth)
            optInfo.configSnapshot = configEditor.getText();
        }
    }

    /**
     * Loads config from the selected node into the GUI/text editor.
     * Also updates tab states (editable vs read-only) and button visibility.
     */
    private void loadConfigFromNode(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) node.getUserObject();

        // Load config into text editor
        if (optInfo.configSnapshot != null) {
            configEditor.setText(optInfo.configSnapshot);
        }

        // Set editable state based on whether optimization has started
        boolean isEditable = !optInfo.hasStartedRunning;
        configEditor.setEditable(isEditable);
        guiBuilder.setEnabled(isEditable);

        // Manage Results tab visibility
        int resultsTabIndex = getResultsTabIndex();
        if (optInfo.hasStartedRunning) {
            // Show Results tab if not already shown
            if (resultsTabIndex == -1) {
                JScrollPane resultsScrollPane = new JScrollPane(resultsDisplayArea);
                mainTabbedPane.addTab("Results", resultsScrollPane);
            }
            // Update results display
            updateResultsDisplay(optInfo);
            // Switch to Results tab
            mainTabbedPane.setSelectedIndex(mainTabbedPane.getTabCount() - 1);
        } else {
            // Hide Results tab if shown
            if (resultsTabIndex != -1) {
                mainTabbedPane.removeTabAt(resultsTabIndex);
            }
        }

        // Manage Run button visibility
        runButton.setVisible(!optInfo.hasStartedRunning);

        // Update currently displayed node
        currentlyDisplayedNode = node;
    }

    /**
     * Gets the index of the Results tab, or -1 if not present.
     */
    private int getResultsTabIndex() {
        for (int i = 0; i < mainTabbedPane.getTabCount(); i++) {
            if ("Results".equals(mainTabbedPane.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Updates the results display area with current optimization progress/results.
     */
    private void updateResultsDisplay(OptimisationInfo optInfo) {
        OptimisationStatus status = optInfo.getStatus();

        if (optInfo.result != null && status == OptimisationStatus.DONE) {
            // Show final result
            resultsDisplayArea.setText(optInfo.result.formatSummary());
        } else if (status == OptimisationStatus.RUNNING || status == OptimisationStatus.LOADING) {
            // Show live progress
            StringBuilder progressText = new StringBuilder();
            progressText.append("=== OPTIMISATION IN PROGRESS ===\n\n");
            progressText.append("Status: ").append(status.getDisplayName()).append("\n");

            if (optInfo.result != null) {
                if (optInfo.result.currentProgress != null) {
                    progressText.append("Progress: ").append(optInfo.result.currentProgress).append("%\n");
                }
                if (optInfo.result.progressDescription != null) {
                    progressText.append("Current: ").append(optInfo.result.progressDescription).append("\n");
                }
                if (optInfo.result.startTime != null) {
                    progressText.append("\nStarted: ").append(optInfo.result.startTime).append("\n");
                }
            }

            progressText.append("\n================================\n");
            resultsDisplayArea.setText(progressText.toString());
        } else if (status == OptimisationStatus.ERROR) {
            // Show error
            StringBuilder errorText = new StringBuilder();
            errorText.append("=== OPTIMISATION FAILED ===\n\n");
            if (optInfo.result != null && optInfo.result.message != null) {
                errorText.append("Error: ").append(optInfo.result.message).append("\n");
            } else {
                errorText.append("Optimisation failed with unknown error\n");
            }
            errorText.append("\n==========================\n");
            resultsDisplayArea.setText(errorText.toString());
        } else {
            // Starting/Ready state
            resultsDisplayArea.setText("Optimisation ready to start...");
        }
    }

    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
            }
        });
    }

    /**
     * Sets up the right-click context menu for optimisation nodes.
     */
    private void setupContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> renameSelectedOptimisation());

        JMenuItem viewInSessionMgrItem = new JMenuItem("View in Session Manager");
        viewInSessionMgrItem.addActionListener(e -> viewSelectedInSessionManager());

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeSelectedOptimisation());

        contextMenu.add(renameItem);
        contextMenu.add(viewInSessionMgrItem);
        contextMenu.addSeparator();
        contextMenu.add(removeItem);

        optTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            private void showContextMenu(MouseEvent e, JPopupMenu menu) {
                TreePath path = optTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof OptimisationInfo) {
                        optTree.setSelectionPath(path);
                        menu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * Renames the selected optimisation.
     */
    private void renameSelectedOptimisation() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();
        String currentName = optInfo.optName;

        String newName = (String) JOptionPane.showInputDialog(
            this,
            "Enter new name for optimisation:",
            "Rename Optimisation",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );

        if (newName != null && !newName.trim().isEmpty() && !newName.equals(currentName)) {
            String trimmedName = newName.trim();

            // Check for duplicate names
            boolean isDuplicate = sessionToOptName.values().stream()
                .anyMatch(name -> name.equals(trimmedName));

            if (isDuplicate) {
                JOptionPane.showMessageDialog(this,
                    "An optimisation with name '" + trimmedName + "' already exists.\nPlease choose a different name.",
                    "Duplicate Name",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Update the name
            optInfo.optName = trimmedName;
            String sessionKey = optInfo.session.getSessionKey();
            sessionToOptName.put(sessionKey, trimmedName);

            // Update tree display
            treeModel.nodeChanged(selectedNode);

            if (statusUpdater != null) {
                statusUpdater.accept("Renamed to: " + trimmedName);
            }
        }
    }

    /**
     * Opens the Session Manager window focused on the selected optimisation's session.
     */
    private void viewSelectedInSessionManager() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();
        String sessionKey = optInfo.session.getSessionKey();

        // Open Session Manager window and select this session
        SessionManagerWindow.showSessionManagerWindow(this, stdioTaskManager, statusUpdater, sessionKey);
    }

    /**
     * Removes the selected optimisation from the tree.
     */
    private void removeSelectedOptimisation() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();
        String sessionKey = optInfo.session.getSessionKey();
        boolean isActive = optInfo.getStatus() == OptimisationStatus.RUNNING ||
                          optInfo.getStatus() == OptimisationStatus.LOADING ||
                          optInfo.getStatus() == OptimisationStatus.STARTING;

        // Confirm removal
        String message = isActive
            ? "Optimisation '" + optInfo.optName + "' is still running.\n" +
              "Removing it will terminate the session.\n\nAre you sure?"
            : "Remove optimisation '" + optInfo.optName + "' from the list?\n" +
              "(This will not delete any saved results)";

        int result = JOptionPane.showConfirmDialog(this,
            message,
            "Confirm Remove",
            JOptionPane.YES_NO_OPTION,
            isActive ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            // Remove from tree
            currentOptimisationsNode.remove(selectedNode);
            treeModel.nodeStructureChanged(currentOptimisationsNode);

            // Remove from tracking maps
            sessionToOptName.remove(sessionKey);
            sessionToTreeNode.remove(sessionKey);
            lastKnownStatus.remove(sessionKey);
            // Keep result in optimisationResults for history

            // Terminate session if active
            if (isActive) {
                stdioTaskManager.terminateSession(sessionKey);
            }

            if (statusUpdater != null) {
                statusUpdater.accept("Removed: " + optInfo.optName);
            }

            // Clear selection (will trigger onOptTreeSelectionChanged to hide panel)
            optTree.clearSelection();
        }
    }

    /**
     * Handles tree selection changes to save/load config and update UI state.
     */
    private void onOptTreeSelectionChanged(TreeSelectionEvent e) {
        if (isUpdatingSelection) return;

        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) {
            // No selection - hide the panel
            optimisationPanel.setVisible(false);
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();

        if (selectedNode.getUserObject() instanceof OptimisationInfo) {
            // Save current config before switching
            saveCurrentConfigToNode();

            // Load new config and update UI state
            loadConfigFromNode(selectedNode);

            // Show the panel
            optimisationPanel.setVisible(true);
        } else {
            // Folder node selected (like "Optimisation runs")
            optimisationPanel.setVisible(false);
        }
    }


    /**
     * Runs the optimisation for the currently selected node.
     * Calls program.runOptimisation() on the existing session.
     */
    private void runOptimisation() {
        if (currentlyDisplayedNode == null) return;

        if (!(currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();

        // Check if already started
        if (optInfo.hasStartedRunning) {
            JOptionPane.showMessageDialog(this,
                "This optimisation has already been started.",
                "Already Running",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String configText = configEditor.getText();

        if (configText == null || configText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Configuration cannot be empty",
                "Invalid Configuration",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String finalConfigText = configText;

        SwingUtilities.invokeLater(() -> {
            try {
                // Get the optimisation program from the session
                if (!(optInfo.session.getActiveProgram() instanceof OptimisationProgram)) {
                    handleError("Session does not have an OptimisationProgram");
                    return;
                }

                OptimisationProgram program = (OptimisationProgram) optInfo.session.getActiveProgram();

                // Save final config
                saveCurrentConfigToNode();
                optInfo.configSnapshot = finalConfigText;
                optInfo.hasStartedRunning = true;

                // Initialize result for tracking
                if (optInfo.result == null) {
                    optInfo.result = new OptimisationResult();
                }
                optInfo.result.configUsed = finalConfigText;
                optInfo.result.startTime = java.time.LocalDateTime.now();

                // Start the optimisation
                program.runOptimisation(finalConfigText);

                // Update UI state
                loadConfigFromNode(currentlyDisplayedNode);

                // Update tree display
                treeModel.nodeChanged(currentlyDisplayedNode);

                if (statusUpdater != null) {
                    statusUpdater.accept("Started " + optInfo.optName);
                }

            } catch (Exception e) {
                handleError("Error starting optimisation: " + e.getMessage());
            }
        });
    }

    /**
     * Handles status updates from optimisation program and updates tree.
     */
    private void handleStatusUpdate(String sessionKey, String statusMessage) {
        SwingUtilities.invokeLater(() -> {
            // Update main status bar
            if (statusUpdater != null) {
                String optName = sessionToOptName.get(sessionKey);
                if (optName != null) {
                    statusUpdater.accept(optName + ": " + statusMessage);
                } else {
                    statusUpdater.accept(statusMessage);
                }
            }

            // Update tree node to reflect status change
            updateTreeNodeForSession(sessionKey);

            // Update details panel if this optimisation is currently selected
            updateDetailsIfSelected(sessionKey);
        });
    }

    /**
     * Handles progress updates during optimisation.
     */
    private void handleOptimisationProgress(String sessionKey, ProgressParser.ProgressInfo progressInfo) {
        SwingUtilities.invokeLater(() -> {
            // Update progress bar
            if (progressBar != null) {
                progressBar.setProgressPercentage(progressInfo.getPercentage());
                progressBar.setProgressText(progressInfo.getDescription());
            }

            // Update progress in result
            OptimisationResult result = optimisationResults.get(sessionKey);
            if (result != null) {
                result.currentProgress = (int) progressInfo.getPercentage();
                result.progressDescription = progressInfo.getDescription();
            }

            // Update tree node to show progress percentage
            updateTreeNodeForSession(sessionKey);

            // Update details if selected
            updateDetailsIfSelected(sessionKey);
        });
    }

    /**
     * Handles the final optimisation result.
     */
    private void handleOptimisationResult(String sessionKey, String resultJson) {
        SwingUtilities.invokeLater(() -> {
            // TODO: Parse result JSON properly in Phase 3
            OptimisationResult result = optimisationResults.get(sessionKey);
            if (result != null) {
                result.success = true;
                result.message = resultJson;
                result.endTime = java.time.LocalDateTime.now();
            }

            // Update tree node to show completion
            updateTreeNodeForSession(sessionKey);

            // Update details if selected
            updateDetailsIfSelected(sessionKey);

            if (statusUpdater != null) {
                String optName = sessionToOptName.get(sessionKey);
                statusUpdater.accept(optName + " completed");
            }
        });
    }

    /**
     * Updates the tree node for a specific session (status, icon, display text).
     */
    private void updateTreeNodeForSession(String sessionKey) {
        DefaultMutableTreeNode node = sessionToTreeNode.get(sessionKey);
        if (node != null) {
            // Get current status
            OptimisationInfo optInfo = (OptimisationInfo) node.getUserObject();
            OptimisationStatus currentStatus = optInfo.getStatus();
            OptimisationStatus previousStatus = lastKnownStatus.get(sessionKey);

            // Update last known status
            lastKnownStatus.put(sessionKey, currentStatus);

            // Notify tree model of change (triggers renderer update)
            treeModel.nodeChanged(node);

            // If this was a significant status change, expand the tree
            if (previousStatus != currentStatus &&
                (currentStatus == OptimisationStatus.DONE || currentStatus == OptimisationStatus.ERROR)) {
                optTree.expandPath(new TreePath(currentOptimisationsNode.getPath()));
            }
        }
    }

    /**
     * Updates the results display if the given session is currently selected.
     */
    private void updateDetailsIfSelected(String sessionKey) {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath != null) {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
            if (selectedNode.getUserObject() instanceof OptimisationInfo) {
                OptimisationInfo selectedInfo = (OptimisationInfo) selectedNode.getUserObject();
                if (selectedInfo.session.getSessionKey().equals(sessionKey)) {
                    // Only update results if optimization has started
                    if (selectedInfo.hasStartedRunning) {
                        updateResultsDisplay(selectedInfo);
                    }
                }
            }
        }
    }

    /**
     * Handles errors during optimisation.
     */
    private void handleError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                errorMessage,
                "Optimisation Error",
                JOptionPane.ERROR_MESSAGE);

            if (statusUpdater != null) {
                statusUpdater.accept("Error: " + errorMessage);
            }
        });
    }

    /**
     * Loads optimisation configuration from a file.
     */
    private void loadConfig() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Load Optimisation Configuration");

        // Set initial directory to current model's directory if available
        File workingDir = workingDirectorySupplier != null ? workingDirectorySupplier.get() : null;
        if (workingDir != null) {
            fileChooser.setCurrentDirectory(workingDir);
        }

        // Set file filter for INI files
        javax.swing.filechooser.FileNameExtensionFilter filter =
            new javax.swing.filechooser.FileNameExtensionFilter("INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                String content = java.nio.file.Files.readString(selectedFile.toPath());
                configEditor.setText(content);
                if (statusUpdater != null) {
                    statusUpdater.accept("Loaded configuration from " + selectedFile.getName());
                }
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Error loading configuration: " + e.getMessage(),
                    "Load Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Saves optimisation configuration to a file.
     */
    private void saveConfig() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Optimisation Configuration");

        // Set initial directory to current model's directory if available
        File workingDir = workingDirectorySupplier != null ? workingDirectorySupplier.get() : null;
        if (workingDir != null) {
            fileChooser.setCurrentDirectory(workingDir);
        }

        // Set file filter for INI files
        javax.swing.filechooser.FileNameExtensionFilter filter =
            new javax.swing.filechooser.FileNameExtensionFilter("INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Ensure .ini extension
            if (!selectedFile.getName().endsWith(".ini")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".ini");
            }

            try {
                java.nio.file.Files.writeString(selectedFile.toPath(), configEditor.getText());
                if (statusUpdater != null) {
                    statusUpdater.accept("Saved configuration to " + selectedFile.getName());
                }
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(this,
                    "Error saving configuration: " + e.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Updates the simulated series combo box options from the current model's [outputs] section.
     * Parses the model text and extracts all output series names.
     */
    private void updateSimulatedSeriesOptionsFromModel() {
        if (modelTextSupplier == null || guiBuilder == null) {
            return;
        }

        String modelText = modelTextSupplier.get();
        if (modelText == null || modelText.isEmpty()) {
            return;
        }

        java.util.List<String> outputSeries = parseOutputsSection(modelText);
        guiBuilder.updateSimulatedSeriesOptions(outputSeries);
    }

    /**
     * Parses the [outputs] section from model text and returns a list of output series names.
     *
     * @param modelText The INI model text
     * @return List of output series names (one per line in [outputs] section)
     */
    private java.util.List<String> parseOutputsSection(String modelText) {
        java.util.List<String> outputs = new ArrayList<>();

        String[] lines = modelText.split("\\r?\\n");
        boolean inOutputsSection = false;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // Check if we're entering the [outputs] section
            if (trimmedLine.equalsIgnoreCase("[outputs]")) {
                inOutputsSection = true;
                continue;
            }

            // Check if we're entering a new section (leaving [outputs])
            if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]") && !trimmedLine.equalsIgnoreCase("[outputs]")) {
                inOutputsSection = false;
                continue;
            }

            // If we're in the outputs section and the line is not empty or a comment
            if (inOutputsSection && !trimmedLine.isEmpty() && !trimmedLine.startsWith("#") && !trimmedLine.startsWith(";")) {
                outputs.add(trimmedLine);
            }
        }

        return outputs;
    }

    // ==================== Helper Classes ====================

    /**
     * Status enum for tracking optimisation progress.
     */
    public enum OptimisationStatus {
        STARTING("Starting"),
        LOADING("Loading Model"),
        RUNNING("Optimising"),
        DONE("Complete"),
        ERROR("Failed"),
        STOPPED("Stopped");

        private final String displayName;

        OptimisationStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Tracks information about a single optimisation run.
     */
    private static class OptimisationInfo {
        String optName;  // Mutable, allows renaming
        final SessionManager.KalixSession session;
        String configSnapshot;  // Store config text at time of run
        OptimisationResult result;  // Cached result (null if not complete)
        boolean hasStartedRunning = false;  // True once runOptimisation() has been called

        public OptimisationInfo(String optName, SessionManager.KalixSession session) {
            this.optName = optName;
            this.session = session;
        }

        public OptimisationStatus getStatus() {
            if (session.getActiveProgram() instanceof OptimisationProgram) {
                OptimisationProgram program = (OptimisationProgram) session.getActiveProgram();
                if (program.isFailed()) return OptimisationStatus.ERROR;
                if (program.isCompleted()) return OptimisationStatus.DONE;
                String stateDesc = program.getStateDescription();
                if (stateDesc.contains("Ready")) return OptimisationStatus.STARTING; // Ready for config
                if (stateDesc.contains("Loading")) return OptimisationStatus.LOADING;
                if (stateDesc.contains("Optimising")) return OptimisationStatus.RUNNING;
            }
            return OptimisationStatus.STARTING;
        }

        public String getDisplayText() {
            if (result != null && result.bestObjective != null) {
                return String.format("%s - %.4f", optName, result.bestObjective);
            }
            if (getStatus() == OptimisationStatus.RUNNING && result != null && result.currentProgress != null) {
                return String.format("%s - %d%%", optName, result.currentProgress);
            }
            return optName;
        }
    }

    /**
     * Stores results from a completed optimisation.
     */
    private static class OptimisationResult {
        // From run_optimisation RESULT message
        Double bestObjective;          // e.g., -0.7662164642516522
        Integer evaluations;           // e.g., 5000
        Integer generations;           // e.g., 99
        String message;                // "Optimization completed successfully"
        Map<String, Double> paramsPhysical;  // Final parameter values
        Map<String, Double> paramsNormalized; // Normalized [0,1] values
        boolean success;

        // Progress tracking (updated during PROGRESS messages)
        Integer currentProgress;       // e.g., 47 (percent)
        String progressDescription;    // e.g., "Generation 47/100"

        // Metadata
        String configUsed;             // INI config text
        java.time.LocalDateTime startTime;
        java.time.LocalDateTime endTime;

        public String formatSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== OPTIMISATION RESULT ===\n");
            sb.append(String.format("Status: %s\n", success ? "SUCCESS" : "FAILED"));
            if (bestObjective != null) {
                sb.append(String.format("Best Objective: %.8f\n", bestObjective));
            }
            if (evaluations != null) {
                sb.append(String.format("Evaluations: %d\n", evaluations));
            }
            if (generations != null) {
                sb.append(String.format("Generations: %d\n", generations));
            }
            if (message != null) {
                sb.append(String.format("Message: %s\n", message));
            }
            sb.append("\nOptimized Parameters:\n");
            if (paramsPhysical != null) {
                paramsPhysical.forEach((param, value) -> {
                    sb.append(String.format("  %s = %.8f\n", param, value));
                });
            }
            sb.append("=========================\n");
            return sb.toString();
        }
    }

    /**
     * Custom tree cell renderer for optimisation nodes with status colors and icons.
     */
    private static class OptimisationTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObject = node.getUserObject();

                if (userObject instanceof OptimisationInfo) {
                    OptimisationInfo optInfo = (OptimisationInfo) userObject;
                    OptimisationStatus status = optInfo.getStatus();

                    // Set display text with objective value or progress
                    setText(optInfo.getDisplayText());

                    // Set color and icon based on status
                    switch (status) {
                        case DONE:
                            setForeground(new Color(0, 120, 0));  // Dark green
                            setIcon(FontIcon.of(FontAwesomeSolid.CHECK_CIRCLE, 16, new Color(0, 120, 0)));
                            break;
                        case RUNNING:
                            setForeground(new Color(0, 0, 200));  // Blue
                            setIcon(FontIcon.of(FontAwesomeSolid.COG, 16, new Color(0, 0, 200)));
                            break;
                        case ERROR:
                            setForeground(new Color(200, 0, 0));  // Red
                            setIcon(FontIcon.of(FontAwesomeSolid.EXCLAMATION_TRIANGLE, 16, new Color(200, 0, 0)));
                            break;
                        case STARTING:
                        case LOADING:
                            setForeground(new Color(150, 150, 0));  // Dark yellow
                            setIcon(FontIcon.of(FontAwesomeSolid.HOURGLASS_HALF, 16, new Color(150, 150, 0)));
                            break;
                        case STOPPED:
                            setForeground(Color.GRAY);
                            setIcon(FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, 16, Color.GRAY));
                            break;
                    }
                }
            }

            return this;
        }
    }
}
