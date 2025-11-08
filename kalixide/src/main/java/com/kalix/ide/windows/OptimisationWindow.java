package com.kalix.ide.windows;

import com.kalix.ide.KalixIDE;
import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.diff.DiffWindow;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.optimisation.*;
import com.kalix.ide.models.optimisation.*;
import com.kalix.ide.renderers.OptimisationTreeCellRenderer;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.kalix.ide.windows.optimisation.OptimisationUIConstants;
import com.kalix.ide.flowviz.PlotPanel;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.*;
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
    private KalixIDE parentIDE;  // Reference to parent IDE window

    // Manager instances
    private OptimisationTreeManager treeManager;
    private OptimisationConfigManager configManager;
    private OptimisationProgressManager progressManager;
    private OptimisationResultsManager resultsManager;
    private OptimisationPlotManager plotManager;
    private OptimisationSessionManager sessionManager;
    private OptimisationPanelBuilder panelBuilder;
    private OptimisationEventHandlers eventHandlers;

    // Tree components
    private JTree optTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode currentOptimisationsNode;

    // Main panel components
    private JPanel rightPanel;  // Container that switches between message and optimisation panel
    private CardLayout rightPanelLayout;
    private JTabbedPane mainTabbedPane;

    // Tab components
    private OptimisationGuiBuilder guiBuilder;
    private KalixIniTextArea configEditor;
    private KalixIniTextArea optimisedModelEditor;  // Editor showing optimised model INI
    private PlotPanel convergencePlot;     // Convergence plot
    private JLabel bestObjectiveLabel;     // Label showing best objective value
    private JLabel evaluationProgressLabel; // Label showing evaluation count and progress
    private JLabel startTimeLabel;         // Label showing optimization start time
    private JLabel elapsedTimeLabel;       // Label showing current/finish time and elapsed time
    private javax.swing.Timer elapsedTimer; // Timer to update elapsed time display
    private JButton runButton;
    private JButton loadConfigButton;
    private JButton saveConfigButton;
    private JLabel configStatusLabel;

    // Track currently displayed node to save config when switching
    private DefaultMutableTreeNode currentlyDisplayedNode = null;

    // Flag to prevent DocumentListener from triggering during programmatic updates
    private boolean isUpdatingConfigEditor = false;

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

        // Store reference to parent IDE (cast JFrame to KalixIDE)
        if (parentFrame instanceof KalixIDE) {
            this.parentIDE = (KalixIDE) parentFrame;
        }

        // Initialize managers
        initializeManagers();
        setupManagerCallbacks();

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
    }

    /**
     * Initializes all manager instances.
     */
    private void initializeManagers() {
        // Initialize session manager first as others may depend on it
        this.sessionManager = new OptimisationSessionManager(
            stdioTaskManager,
            workingDirectorySupplier,
            modelTextSupplier
        );

        // Initialize other managers
        this.treeManager = new OptimisationTreeManager();
        this.configManager = new OptimisationConfigManager(
            workingDirectorySupplier,
            modelTextSupplier
        );
        this.progressManager = new OptimisationProgressManager(progressBar);
        this.resultsManager = new OptimisationResultsManager();
        this.plotManager = new OptimisationPlotManager();
        this.panelBuilder = new OptimisationPanelBuilder();
        this.eventHandlers = new OptimisationEventHandlers(
            sessionManager,
            treeManager,
            progressManager,
            statusUpdater
        );

        // Set up basic dependencies
        resultsManager.setWorkingDirectorySupplier(workingDirectorySupplier);
        resultsManager.setOriginalModelSupplier(modelTextSupplier);
        resultsManager.setStatusUpdater(statusUpdater);

        configManager.setStatusUpdater(statusUpdater);
        sessionManager.setStatusUpdater(statusUpdater);
    }

    /**
     * Sets up callbacks between managers and this window.
     */
    private void setupManagerCallbacks() {
        // Session manager callbacks
        sessionManager.setOnOptimisationCreated(optInfo -> {
            SwingUtilities.invokeLater(() -> {
                treeManager.addOptimisation(optInfo.getName(), optInfo);
                // Auto-select the new optimisation
                DefaultMutableTreeNode node = sessionManager.getTreeNode(optInfo.getSessionKey());
                if (node != null) {
                    TreePath path = new TreePath(treeModel.getPathToRoot(node));
                    optTree.setSelectionPath(path);
                    optTree.scrollPathToVisible(path);
                }
            });
        });

        sessionManager.setOnSessionCompleted(sessionKey -> {
            SwingUtilities.invokeLater(() -> {
                updateTreeNodeForSession(sessionKey);
                // Refresh displays if this is the current optimisation
                if (currentlyDisplayedNode != null) {
                    Object userObject = currentlyDisplayedNode.getUserObject();
                    if (userObject instanceof OptimisationInfo) {
                        OptimisationInfo optInfo = (OptimisationInfo) userObject;
                        if (optInfo.getSessionKey().equals(sessionKey)) {
                            displayOptimisation(optInfo);
                        }
                    }
                }
            });
        });

        sessionManager.setOnErrorOccurred(errorMessage -> {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
            });
        });

        // Tree manager action callbacks
        treeManager.setShowModelAction(optInfo -> {
            // Show the original model used for optimisation
            if (optInfo.getSession() != null &&
                optInfo.getSession().getActiveProgram() instanceof OptimisationProgram) {
                OptimisationProgram program = (OptimisationProgram) optInfo.getSession().getActiveProgram();
                String modelText = program.getModelText();

                if (modelText != null && !modelText.isEmpty()) {
                    MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText, true);
                    editorWindow.setTitle(optInfo.getName() + " - Original Model");
                    editorWindow.setVisible(true);
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Model text is not available for this optimisation.",
                        "Model Not Available",
                        JOptionPane.INFORMATION_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                    "Model text is not available for this optimisation.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE);
            }
        });

        treeManager.setShowOptimisedModelAction(optInfo -> {
            resultsManager.showOptimisedModel(optInfo, this);
        });

        treeManager.setCompareModelAction(optInfo -> {
            resultsManager.compareModels(optInfo, this);
        });

        treeManager.setSaveResultsAction(optInfo -> {
            resultsManager.saveResults(optInfo, this);
        });

        treeManager.setRenameAction(optInfo -> {
            String newName = JOptionPane.showInputDialog(this, "Enter new name:", optInfo.getName());
            if (newName != null && !newName.trim().isEmpty()) {
                boolean renamed = sessionManager.renameOptimisation(optInfo.getSessionKey(), newName);
                if (renamed) {
                    optInfo.setName(newName.trim());
                    treeModel.nodeChanged(sessionManager.getTreeNode(optInfo.getSessionKey()));
                }
            }
        });

        treeManager.setRemoveAction(optInfo -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                "Remove optimisation '" + optInfo.getName() + "'?",
                "Confirm Remove",
                JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                sessionManager.removeOptimisation(
                    optInfo.getSessionKey(),
                    optInfo.getStatus() == OptimisationStatus.RUNNING
                );
                DefaultMutableTreeNode node = sessionManager.getTreeNode(optInfo.getSessionKey());
                if (node != null && node.getParent() != null) {
                    treeModel.removeNodeFromParent(node);
                }
                // Clear selection
                optTree.clearSelection();
            }
        });

        // Config manager doesn't need complex callbacks as it's mostly self-contained

        // Setup event handler callbacks
        eventHandlers.setTreeNodeUpdater(this::updateTreeNodeForSession);
        eventHandlers.setDetailsUpdater(this::updateDetailsIfSelected);
        eventHandlers.setConvergencePlotUpdater(this::updateConvergencePlotIfSelected);

        // Setup tree selection callbacks
        treeManager.setOnNoSelectionCallback(() ->
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE));
        treeManager.setOnFolderSelectedCallback(() ->
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE));
        treeManager.setSaveCurrentConfigCallback(this::saveCurrentConfigToNode);
        treeManager.setOnOptimisationSelectedCallback(optInfo -> {
            loadConfigFromNode(sessionManager.getTreeNode(optInfo.getSessionKey()));
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_OPTIMISATION);
        });
    }

    /**
     * Displays an optimisation's information in the UI.
     */
    private void displayOptimisation(OptimisationInfo optInfo) {
        if (optInfo == null) {
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE);
            currentlyDisplayedNode = null;
            return;
        }

        // Update displays using managers
        configManager.loadConfiguration(optInfo);
        resultsManager.displayResults(optInfo);
        plotManager.updatePlot(optInfo.getResult());
        progressManager.setCurrentOptimisation(optInfo);

        // Update UI state
        boolean canRun = optInfo.getStatus() == OptimisationStatus.CONFIGURING;
        runButton.setEnabled(canRun);
        loadConfigButton.setEnabled(canRun);
        saveConfigButton.setEnabled(true);

        // Show optimisation panel
        rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_OPTIMISATION);
        currentlyDisplayedNode = sessionManager.getTreeNode(optInfo.getSessionKey());

        // Switch to appropriate tab based on status
        if (optInfo.getStatus() == OptimisationStatus.DONE) {
            mainTabbedPane.setSelectedIndex(2); // Results tab
        }
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

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Optimiser");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(OptimisationUIConstants.WINDOW_WIDTH, OptimisationUIConstants.WINDOW_HEIGHT);

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + OptimisationUIConstants.WINDOW_OFFSET_X,
                       parentLocation.y + OptimisationUIConstants.WINDOW_OFFSET_Y);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // ===== Tree Structure (now managed by TreeManager) =====
        optTree = treeManager.getTree();

        // Get tree model from the tree component
        treeModel = (DefaultTreeModel) optTree.getModel();
        TreeNode root = (TreeNode) treeModel.getRoot();
        rootNode = (DefaultMutableTreeNode) root;

        // Get the Current Optimisations node (first child)
        if (rootNode.getChildCount() > 0) {
            currentOptimisationsNode = (DefaultMutableTreeNode) rootNode.getChildAt(0);
        }

        // Set custom renderer and selection listener
        optTree.setCellRenderer(new OptimisationTreeCellRenderer());
        optTree.addTreeSelectionListener(e -> {
            if (!isUpdatingSelection) {
                treeManager.handleTreeSelection(e);
            }
        });

        // Setup tree manager's popup menu
        treeManager.setupContextMenu();

        // Expand "Optimisation runs" node by default
        optTree.expandRow(0);

        // ===== Build Right Panel using PanelBuilder =====
        rightPanel = panelBuilder.buildRightPanel();
        rightPanelLayout = panelBuilder.getRightPanelLayout();
        mainTabbedPane = panelBuilder.getMainTabbedPane();

        // Tab 1: Config (GUI Builder from ConfigManager)
        guiBuilder = configManager.getGuiBuilder();
        panelBuilder.addConfigGuiTab(guiBuilder);

        // Tab 2: Config INI (Text Editor from ConfigManager)
        configEditor = configManager.getConfigEditor();
        RTextScrollPane configScrollPane = configManager.getConfigScrollPane();

        // Build Config INI tab with generate button
        configStatusLabel = panelBuilder.buildConfigIniTab(configEditor, configScrollPane, e -> {
            isUpdatingConfigEditor = true;
            guiBuilder.generateAndSwitchToTextEditor();
            configStatusLabel.setText(OptimisationUIConstants.CONFIG_STATUS_ORIGINAL);
            // Update the current node's modification state
            if (currentlyDisplayedNode != null &&
                currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo) {
                OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();
                optInfo.setConfigModified(false);
            }
            isUpdatingConfigEditor = false;
        });

        // Tab 3: Results (always present)
        // Get components from managers
        optimisedModelEditor = resultsManager.getOptimisedModelEditor();
        convergencePlot = plotManager.getPlotPanel();

        // Get labels from progress manager
        startTimeLabel = progressManager.getStartTimeLabel();
        elapsedTimeLabel = progressManager.getElapsedTimeLabel();
        evaluationProgressLabel = progressManager.getEvaluationProgressLabel();
        bestObjectiveLabel = progressManager.getBestObjectiveLabel();

        // Create plot panel with labels
        JPanel plotWithLabelsPanel = plotManager.createPlotPanelWithLabels();

        // Build Results tab using builder
        panelBuilder.buildResultsTab(
            optimisedModelEditor,
            plotWithLabelsPanel,
            progressManager,
            e -> {
                if (currentlyDisplayedNode != null &&
                    currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo) {
                    OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();
                    copyOptimisedModelToMain(optInfo);
                }
            },
            e -> {
                if (currentlyDisplayedNode != null &&
                    currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo) {
                    OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();
                    resultsManager.compareModels(optInfo, this);
                }
            },
            e -> {
                if (currentlyDisplayedNode != null &&
                    currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo) {
                    OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();
                    resultsManager.saveResults(optInfo, this);
                }
            }
        );

        // Get button references from builder and set action listeners
        loadConfigButton = panelBuilder.getLoadConfigButton();
        loadConfigButton.addActionListener(e -> configManager.loadConfigFromFile(getRootPane()));

        saveConfigButton = panelBuilder.getSaveConfigButton();
        saveConfigButton.addActionListener(e -> configManager.saveConfigToFile(getRootPane(), configManager.getCurrentConfig()));

        runButton = panelBuilder.getRunButton();
        runButton.addActionListener(e -> runOptimisation());

        // Note: Elapsed timer is now managed by progressManager
        // Note: Right panel and cards are already set up by panelBuilder
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Left panel: Button + Tree
        JPanel leftPanel = new JPanel(new BorderLayout(0, 5));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Button panel at top of left side
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, OptimisationUIConstants.PADDING_SMALL,
                                                       OptimisationUIConstants.PADDING_SMALL));
        JButton newOptButton = new JButton("New");
        newOptButton.setIcon(FontIcon.of(FontAwesomeSolid.PLUS, 14, OptimisationUIConstants.ICON_COLOR_NEW));
        newOptButton.addActionListener(e -> createNewOptimisation());
        buttonPanel.add(newOptButton);
        leftPanel.add(buttonPanel, BorderLayout.NORTH);

        JScrollPane treeScrollPane = new JScrollPane(optTree);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        leftPanel.setPreferredSize(new Dimension(OptimisationUIConstants.TREE_PANEL_WIDTH, 0));

        // Create horizontal split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        splitPane.setDividerLocation(OptimisationUIConstants.TREE_PANEL_WIDTH);
        splitPane.setResizeWeight(0.0);  // Tree stays fixed width when resizing

        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * Creates a new optimisation node, starts a session, and selects it.
     * This is called when the user clicks the "+ New Optimisation" button.
     */
    private void createNewOptimisation() {
        // Get default config from GUI
        String configText = configManager.generateConfigFromGui();

        // Create optimisation through session manager with sessionKey passed to callbacks
        sessionManager.createOptimisation(
            configText,
            (sessionKey, progressInfo) -> eventHandlers.handleOptimisationProgress(sessionKey, progressInfo),
            (sessionKey, parameters) -> eventHandlers.handleOptimisableParameters(sessionKey, parameters, guiBuilder),
            (sessionKey, result) -> eventHandlers.handleOptimisationResult(sessionKey, result)
        );
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
        if (!optInfo.hasStartedRunning()) {
            // Save the config text from the editor (it's the source of truth)
            String configText = configEditor.getText();
            optInfo.setConfigSnapshot(configText);

            // Also update the stored config in session manager
            if (optInfo.getSession() != null) {
                String sessionKey = optInfo.getSession().getSessionKey();
                sessionManager.updateOptimisationConfig(sessionKey, configText);
            }
        }
    }

    /**
     * Loads config from the selected node into the GUI/text editor.
     * Also updates tab states (editable vs read-only) and button visibility.
     */
    private void loadConfigFromNode(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) node.getUserObject();

        // Load configuration through config manager
        configManager.loadConfiguration(optInfo);

        // Set editable state based on whether optimization has started
        boolean isEditable = !optInfo.hasStartedRunning();
        configEditor.setEditable(isEditable);
        guiBuilder.setComponentsEnabled(isEditable);

        // Disable Load button when optimization has started (but keep Save enabled)
        loadConfigButton.setEnabled(isEditable);

        // Update results display (Results tab is always present)
        if (optInfo.hasStartedRunning()) {
            // Update timing labels
            progressManager.updateTimingLabels(optInfo);
            // Update results display
            resultsManager.updateOptimisedModelDisplay(optInfo);
            // Update convergence plot with current data
            plotManager.updatePlot(optInfo.getResult());
        } else {
            optimisedModelEditor.setText("");
            // Clear convergence plot and labels
            plotManager.clearPlot();
            if (bestObjectiveLabel != null) {
                bestObjectiveLabel.setText("Best: —");
            }
            if (evaluationProgressLabel != null) {
                evaluationProgressLabel.setText("Evaluations: —");
            }
        }

        // Disable Run button once optimization has started
        runButton.setEnabled(!optInfo.hasStartedRunning());

        // Update currently displayed node
        currentlyDisplayedNode = node;
    }

    /**
     * Gets the index of the Results tab, or -1 if not present.
     */
    private int getResultsTabIndex() {
        for (int i = 0; i < mainTabbedPane.getTabCount(); i++) {
            if (OptimisationUIConstants.TAB_RESULTS.equals(mainTabbedPane.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
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
     * Renames the selected optimisation.
     */
    private void renameSelectedOptimisation() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();
        String currentName = optInfo.getName();

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
            boolean isDuplicate = sessionManager.getAllSessions().values().stream()
                .anyMatch(name -> name.equals(trimmedName));

            if (isDuplicate) {
                JOptionPane.showMessageDialog(this,
                    "An optimisation with name '" + trimmedName + "' already exists.\nPlease choose a different name.",
                    "Duplicate Name",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Update the name
            optInfo.setName(trimmedName);
            String sessionKey = optInfo.getSession().getSessionKey();
            // Update name in session manager
            boolean renamed = sessionManager.renameOptimisation(sessionKey, trimmedName);

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
        String sessionKey = optInfo.getSession().getSessionKey();

        // Open Session Manager window and select this session
        SessionManagerWindow.showSessionManagerWindow(this, stdioTaskManager, statusUpdater, sessionKey);
    }

    /**
     * Opens the optimised model from the selected optimisation in a new MinimalEditorWindow.
     */
    private void showSelectedOptimisationModel() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();

        // Check if optimisation has completed and has a result
        OptimisationResult result = optInfo.getResult();
        if (result == null || result.getOptimisedModelIni() == null) {
            JOptionPane.showMessageDialog(this,
                "No optimised model available.\n\nThe optimisation must complete successfully before the model can be viewed.",
                "Model Not Available",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Create and show MinimalEditorWindow with the optimised model
        MinimalEditorWindow editorWindow = new MinimalEditorWindow(result.getOptimisedModelIni(), true);
        editorWindow.setTitle("Optimised Model - " + optInfo.getName());
        editorWindow.setVisible(true);
    }

    /**
     * Compares the optimised model from the selected optimisation with the main window's model.
     */
    private void showSelectedOptimisationChanges() {
        TreePath selectedPath = optTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) selectedNode.getUserObject();

        // Check if optimisation has completed and has a result
        OptimisationResult result = optInfo.getResult();
        if (result == null || result.getOptimisedModelIni() == null) {
            JOptionPane.showMessageDialog(this,
                "No optimised model available.\n\nThe optimisation must complete successfully before model changes can be viewed.",
                "Model Not Available",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Check if parent IDE is available
        if (parentIDE == null) {
            JOptionPane.showMessageDialog(this,
                "Cannot compare with main window.\n\nThe main IDE window is not available.",
                "Main Window Not Available",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get main window's model text
        String mainModelText = parentIDE.getModelText();
        String optimisedModelText = result.getOptimisedModelIni();

        // Open DiffWindow for comparison
        new DiffWindow(optimisedModelText, mainModelText,
            "Changes: " + optInfo.getName() + " vs Reference Model", "Reference Model", optInfo.getName());
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
        String sessionKey = optInfo.getSession().getSessionKey();
        boolean isActive = optInfo.getStatus() == OptimisationStatus.RUNNING ||
                          optInfo.getStatus() == OptimisationStatus.LOADING ||
                          optInfo.getStatus() == OptimisationStatus.STARTING;

        // Confirm removal
        String message = isActive
            ? "Optimisation '" + optInfo.getName() + "' is still running.\n" +
              "Removing it will terminate the session.\n\nAre you sure?"
            : "Remove optimisation '" + optInfo.getName() + "' from the list?\n" +
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

            // Remove from session manager (handles termination if active)
            sessionManager.removeOptimisation(sessionKey, isActive);

            if (statusUpdater != null) {
                statusUpdater.accept("Removed: " + optInfo.getName());
            }

            // Clear selection (will trigger onOptTreeSelectionChanged to hide panel)
            optTree.clearSelection();
        }
    }



    /**
     * Runs the optimisation for the currently selected node.
     * Calls program.runOptimisation() on the existing session.
     * Behavior depends on which tab is currently selected:
     * - Config tab: Generate config from GUI, update Config INI editor, then run
     * - Config INI tab: Use text from editor directly
     */
    private void runOptimisation() {
        if (currentlyDisplayedNode == null) return;

        Object userObject = currentlyDisplayedNode.getUserObject();
        if (!(userObject instanceof OptimisationInfo)) return;

        OptimisationInfo optInfo = (OptimisationInfo) userObject;

        // Get config from appropriate source
        String configText;
        int selectedTabIndex = mainTabbedPane.getSelectedIndex();
        if (selectedTabIndex == 0) { // Config tab
            configText = configManager.generateConfigFromGui();
            // Update the config editor with generated config
            configManager.setConfiguration(configText);
        } else { // Config INI tab
            configText = configManager.getCurrentConfig();
        }

        // Validate config
        if (!configManager.validateConfiguration()) {
            JOptionPane.showMessageDialog(this,
                "Invalid configuration. Please check the configuration.",
                "Invalid Configuration",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Update config snapshot
        optInfo.setConfigSnapshot(configText);

        // Update the stored config in session manager
        if (optInfo.getSession() != null) {
            String sessionKey = optInfo.getSession().getSessionKey();
            sessionManager.updateOptimisationConfig(sessionKey, configText);
        }

        // Run optimisation through manager
        boolean started = sessionManager.runOptimisation(optInfo);

        if (started) {
            // Switch to results tab
            mainTabbedPane.setSelectedIndex(2); // Results tab
            progressManager.startProgress(optInfo);

            // Update tree display
            treeModel.nodeChanged(currentlyDisplayedNode);
        }
    }


    /**
     * Updates the tree node for a specific session (status, icon, display text).
     */
    private void updateTreeNodeForSession(String sessionKey) {
        DefaultMutableTreeNode node = sessionManager.getTreeNode(sessionKey);
        if (node != null) {
            // Get current status
            OptimisationInfo optInfo = (OptimisationInfo) node.getUserObject();
            OptimisationStatus currentStatus = optInfo.getStatus();
            OptimisationStatus previousStatus = sessionManager.getLastKnownStatus(sessionKey);

            // Update last known status
            sessionManager.updateStatus(sessionKey, currentStatus);

            // Delegate to tree manager for display update
            treeManager.updateTreeNodeForSession(node, currentStatus, previousStatus);
        }
    }

    /**
     * Gets the currently selected OptimisationInfo if it matches the given session key.
     *
     * @param sessionKey The session key to check
     * @return The matching OptimisationInfo, or null if not selected or doesn't match
     */
    private OptimisationInfo getSelectedOptimisationIfMatches(String sessionKey) {
        OptimisationInfo selectedInfo = treeManager.getSelectedOptimisation();
        if (selectedInfo != null && selectedInfo.getSession().getSessionKey().equals(sessionKey)) {
            return selectedInfo;
        }
        return null;
    }

    /**
     * Updates the results display if the given session is currently selected.
     */
    private void updateDetailsIfSelected(String sessionKey) {
        OptimisationInfo selectedInfo = getSelectedOptimisationIfMatches(sessionKey);
        if (selectedInfo != null && selectedInfo.hasStartedRunning()) {
            // Update timing labels
            progressManager.updateTimingLabels(selectedInfo);
            // Update results display
            resultsManager.updateOptimisedModelDisplay(selectedInfo);
        }
    }

    /**
     * Updates the convergence plot and labels if the given session is currently selected.
     */
    private void updateConvergencePlotIfSelected(String sessionKey) {
        OptimisationInfo selectedInfo = getSelectedOptimisationIfMatches(sessionKey);
        if (selectedInfo != null && selectedInfo.getResult() != null) {
            // Update the convergence plot with latest data
            plotManager.updatePlot(selectedInfo.getResult());
            // Also update labels in real-time
            progressManager.updateConvergenceLabels(selectedInfo.getResult());
        }
    }


    /**
     * Updates the elapsed time label based on the currently displayed optimization.
     * Called by the timer to provide real-time elapsed time updates.
     */
    private void updateElapsedTime() {
        // Delegate to progress manager
        progressManager.updateElapsedTime();
    }

    /**
     * Updates the timing labels (start, elapsed) for the displayed optimization.
     */
    private void updateTimingLabels(OptimisationInfo optInfo) {
        // Delegate to progress manager
        progressManager.updateTimingLabels(optInfo);

        // Handle elapsed time timer
        OptimisationResult result = optInfo == null ? null : optInfo.getResult();
        if (result == null) {
            if (elapsedTimer != null && elapsedTimer.isRunning()) {
                elapsedTimer.stop();
            }
        } else if (result.getEndTime() != null) {
            // Stop timer if optimization is finished
            if (elapsedTimer != null && elapsedTimer.isRunning()) {
                elapsedTimer.stop();
            }
        } else {
            // Start timer if optimization is running
            if (elapsedTimer != null && !elapsedTimer.isRunning() && result.getStartTime() != null) {
                elapsedTimer.start();
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

    // Note: OptimisationInfo, OptimisationResult, and OptimisationStatus classes
    // have been extracted to separate files in the models/optimisation package

    /**
     * Copies the optimised model to the main window.
     */
    private void copyOptimisedModelToMain(OptimisationInfo optInfo) {
        if (parentIDE == null || optInfo == null || optInfo.getResult() == null) {
            JOptionPane.showMessageDialog(this,
                "Cannot access main window or no results available.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        String optimisedModelText = optInfo.getResult().getOptimisedModelIni();
        if (optimisedModelText == null || optimisedModelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No optimised model available to copy.",
                "Copy to Main Window",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Check if main window has unsaved changes
        if (parentIDE.hasUnsavedChanges()) {
            int choice = JOptionPane.showConfirmDialog(
                this,
                "The main window has unsaved changes.\n\nDo you want to replace the current content with the optimised model?",
                "Unsaved Changes",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            if (choice != JOptionPane.YES_OPTION) {
                return; // User cancelled
            }
        }

        // Copy optimised model to main window and mark as dirty (unsaved)
        parentIDE.setModelTextAndMarkDirty(optimisedModelText);

        JOptionPane.showMessageDialog(this,
            "Optimised model copied to main window successfully.",
            "Copy Complete",
            JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Compares the optimised model with the main window using DiffWindow.
     */
    private void compareOptimisedModelWithMain() {
        if (parentIDE == null) {
            JOptionPane.showMessageDialog(this,
                "Cannot access main window.",
                "Error",
                JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get current optimization name
        String optimisationName = "Optimised Model"; // Default fallback
        if (currentlyDisplayedNode != null &&
            currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo) {
            OptimisationInfo optInfo = (OptimisationInfo) currentlyDisplayedNode.getUserObject();
            optimisationName = optInfo.getName();
        }

        String optimisedModelText = optimisedModelEditor.getText();
        if (optimisedModelText == null || optimisedModelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No optimised model available to compare.",
                "Show Model Changes",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Get current model from main window
        String mainModelText = parentIDE.getModelText();
        if (mainModelText == null || mainModelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Main window is empty. Nothing to compare.",
                "Show Model Changes",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Open diff window (optimised model vs main window model)
        new DiffWindow(optimisedModelText, mainModelText,
            "Changes: " + optimisationName + " vs Reference Model",
            "Reference Model",
            optimisationName);
    }

    /**
     * Saves the optimised model to a file.
     */
    private void saveOptimisedModelAs() {
        String optimisedModelText = optimisedModelEditor.getText();
        if (optimisedModelText == null || optimisedModelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No optimised model available to save.",
                "Save As",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Optimised Model");

        // Set initial directory to current model's directory if available
        File workingDir = workingDirectorySupplier != null ? workingDirectorySupplier.get() : null;
        if (workingDir != null) {
            fileChooser.setCurrentDirectory(workingDir);
        }

        // Set file filter for INI files
        javax.swing.filechooser.FileNameExtensionFilter filter =
            new javax.swing.filechooser.FileNameExtensionFilter("INI Files (*.ini)", "ini");
        fileChooser.setFileFilter(filter);

        // Suggest a filename
        fileChooser.setSelectedFile(new File("optimised_model.ini"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Ensure .ini extension
            if (!selectedFile.getName().endsWith(".ini")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".ini");
            }

            // Check if file exists
            if (selectedFile.exists()) {
                int overwrite = JOptionPane.showConfirmDialog(
                    this,
                    "File \"" + selectedFile.getName() + "\" already exists.\n\nDo you want to replace it?",
                    "File Exists",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );

                if (overwrite != JOptionPane.YES_OPTION) {
                    return; // User chose not to overwrite
                }
            }

            // Save the file
            try {
                java.nio.file.Files.writeString(selectedFile.toPath(), optimisedModelText);
                JOptionPane.showMessageDialog(this,
                    "Optimised model saved successfully to:\n" + selectedFile.getAbsolutePath(),
                    "Save Complete",
                    JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this,
                    "Failed to save file:\n" + e.getMessage(),
                    "Save Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

}
