package com.kalix.ide.windows;

import com.kalix.ide.KalixIDE;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.optimisation.OptimisationConfigManager;
import com.kalix.ide.managers.optimisation.OptimisationEventHandlers;
import com.kalix.ide.managers.optimisation.OptimisationModelManager;
import com.kalix.ide.managers.optimisation.OptimisationPanelBuilder;
import com.kalix.ide.managers.optimisation.OptimisationPlotManager;
import com.kalix.ide.managers.optimisation.OptimisationProgressManager;
import com.kalix.ide.managers.optimisation.OptimisationResultsManager;
import com.kalix.ide.managers.optimisation.OptimisationSessionManager;
import com.kalix.ide.managers.optimisation.OptimisationTreeManager;
import com.kalix.ide.managers.optimisation.OptimisationUpdateCoordinator;
import com.kalix.ide.managers.optimisation.OptimisationWindowInitializer;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.kalix.ide.windows.optimisation.OptimisationUIConstants;
import com.kalix.ide.flowviz.PlotPanel;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
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
    private OptimisationModelManager modelManager;
    private OptimisationWindowInitializer windowInitializer;
    private OptimisationUpdateCoordinator updateCoordinator;

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
    private final boolean isUpdatingConfigEditor = false;

    // Flag to prevent selection listener from firing during programmatic updates
    private final boolean isUpdatingSelection = false;

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
        this.modelManager = new OptimisationModelManager(
            workingDirectorySupplier,
            modelTextSupplier,
            modelText -> {
                if (parentIDE != null) {
                    parentIDE.setModelTextAndMarkDirty(modelText);
                }
            }
        );
        this.windowInitializer = new OptimisationWindowInitializer(
            treeManager, configManager, progressManager, resultsManager,
            plotManager, sessionManager, panelBuilder, eventHandlers
        );
        this.updateCoordinator = new OptimisationUpdateCoordinator(
            treeManager, progressManager, resultsManager, plotManager, sessionManager
        );

        // Set up basic dependencies
        resultsManager.setWorkingDirectorySupplier(workingDirectorySupplier);
        resultsManager.setOriginalModelSupplier(modelTextSupplier);
        resultsManager.setStatusUpdater(statusUpdater);

        configManager.setStatusUpdater(statusUpdater);
        sessionManager.setStatusUpdater(statusUpdater);
        modelManager.setStatusUpdater(statusUpdater);
    }

    /**
     * Sets up callbacks between managers and this window.
     */
    private void setupManagerCallbacks() {
        windowInitializer.setupManagerCallbacks(
            this,
            stdioTaskManager,
            statusUpdater,
            () -> rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE),
            this::displayOptimisation,
            this::saveCurrentConfigToNode,
            updateCoordinator::updateTreeNodeForSession,
            updateCoordinator::updateDetailsIfSelected,
            updateCoordinator::updateConvergencePlotIfSelected
        );
    }

    /**
     * Displays an optimisation's information in the UI.
     * This is the central method for updating all UI elements when an optimisation is selected.
     */
    private void displayOptimisation(OptimisationInfo optInfo) {
        if (optInfo == null) {
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE);
            currentlyDisplayedNode = null;
            return;
        }

        // Load configuration through managers
        configManager.loadConfiguration(optInfo);

        // Set editable state based on whether optimization has started
        boolean isEditable = !optInfo.hasStartedRunning();
        configEditor.setEditable(isEditable);
        guiBuilder.setComponentsEnabled(isEditable);

        // Update button states
        runButton.setEnabled(isEditable);
        loadConfigButton.setEnabled(isEditable);
        saveConfigButton.setEnabled(true);

        // Update displays based on running state
        if (optInfo.hasStartedRunning()) {
            // Update timing labels
            progressManager.updateTimingLabels(optInfo);
            // Update results display
            resultsManager.updateOptimisedModelDisplay(optInfo);
            // Update convergence plot with current data
            plotManager.updatePlot(optInfo.getResult());
        } else {
            // Clear results displays
            optimisedModelEditor.setText("");
            plotManager.clearPlot();
            if (bestObjectiveLabel != null) {
                bestObjectiveLabel.setText("Best: —");
            }
            if (evaluationProgressLabel != null) {
                evaluationProgressLabel.setText("Evaluations: —");
            }
        }

        // Update progress manager
        progressManager.setCurrentOptimisation(optInfo);

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
        instance.configManager.updateSimulatedSeriesOptionsFromModel(modelTextSupplier);

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
        OptimisationWindowInitializer.InitializationResult result = windowInitializer.initializeComponents(
            optInfo -> modelManager.copyOptimisedModelToMain(optInfo, getRootPane()),
            optInfo -> resultsManager.compareModels(optInfo, this),
            optInfo -> resultsManager.saveResults(optInfo, this),
            () -> runOptimisation(),
            e -> {
                if (!isUpdatingSelection) {
                    treeManager.handleTreeSelection(e);
                }
            }
        );

        // Store component references
        optTree = result.optTree;
        treeModel = result.treeModel;
        rootNode = result.rootNode;
        currentOptimisationsNode = result.currentOptimisationsNode;
        rightPanel = result.rightPanel;
        rightPanelLayout = result.rightPanelLayout;
        mainTabbedPane = result.mainTabbedPane;
        loadConfigButton = result.loadConfigButton;
        saveConfigButton = result.saveConfigButton;
        runButton = result.runButton;
        configStatusLabel = result.configStatusLabel;
        optimisedModelEditor = result.optimisedModelEditor;
        convergencePlot = result.convergencePlot;

        // Get additional components from managers
        guiBuilder = configManager.getGuiBuilder();
        configEditor = configManager.getConfigEditor();
        startTimeLabel = progressManager.getStartTimeLabel();
        elapsedTimeLabel = progressManager.getElapsedTimeLabel();
        evaluationProgressLabel = progressManager.getEvaluationProgressLabel();
        bestObjectiveLabel = progressManager.getBestObjectiveLabel();
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

        if (!(currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo optInfo)) return;

        String sessionKey = optInfo.getSession() != null ? optInfo.getSession().getSessionKey() : null;

        // Use config manager to save
        configManager.saveCurrentConfigToOptimisation(optInfo, sessionKey, sessionManager);
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
     * Runs the optimisation for the currently selected node.
     * Delegates to session manager with appropriate configuration.
     */
    private void runOptimisation() {
        if (currentlyDisplayedNode == null) return;

        Object userObject = currentlyDisplayedNode.getUserObject();
        if (!(userObject instanceof OptimisationInfo optInfo)) return;

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

        // Run optimisation through manager with validation
        boolean started = sessionManager.runOptimisation(optInfo, configText,
            config -> configManager.validateConfiguration());

        if (started) {
            // Switch to results tab
            mainTabbedPane.setSelectedIndex(2); // Results tab
            progressManager.startProgress(optInfo);

            // Update tree display
            treeModel.nodeChanged(currentlyDisplayedNode);
            optTree.repaint();
        } else if (!configManager.validateConfiguration()) {
            JOptionPane.showMessageDialog(this,
                "Invalid configuration. Please check the configuration.",
                "Invalid Configuration",
                JOptionPane.WARNING_MESSAGE);
        }
    }


}
