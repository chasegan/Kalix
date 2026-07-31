package com.kalix.ide.windows;

import com.kalix.ide.KalixIDE;
import com.kalix.ide.document.DocumentLabels;
import com.kalix.ide.document.OpenModel;
import com.kalix.ide.document.WorkspaceView;
import com.kalix.ide.document.ModelWriteBack;
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
import com.kalix.ide.managers.optimisation.OptimisationWindowInitializer;
import com.kalix.ide.managers.SessionTreeBookkeeping;
import com.kalix.ide.managers.optimisation.OptimisationConfigModel;
import com.kalix.ide.managers.optimisation.OptimisationInfo;
import com.kalix.ide.managers.optimisation.OptimisationStatus;
import com.kalix.ide.windows.optimisation.ParametersConfigPanel;
import com.kalix.ide.components.StatusProgressBar;
import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.windows.optimisation.ModelSelectorPanel;
import com.kalix.ide.windows.optimisation.OptimisationGuiBuilder;
import com.kalix.ide.windows.optimisation.OptimisationUIConstants;
import com.kalix.ide.flowviz.PlotPanel;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
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
import javax.swing.SwingUtilities;
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
    private final Supplier<File> projectDirectorySupplier;
    private final WorkspaceView workspace;
    private final ModelWriteBack modelWriteBack;

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
    private JButton runButton;
    private JButton loadConfigButton;
    private JButton saveConfigButton;
    private JLabel configStatusLabel;

    // Track currently displayed node to save config when switching
    private DefaultMutableTreeNode currentlyDisplayedNode = null;

    private static OptimisationWindow instance;

    /**
     * Private constructor for singleton pattern.
     */
    private OptimisationWindow(JFrame parentFrame,
                               StdioTaskManager stdioTaskManager,
                               Consumer<String> statusUpdater,
                               StatusProgressBar progressBar,
                               Supplier<File> projectDirectorySupplier,
                               WorkspaceView workspace,
                               ModelWriteBack modelWriteBack) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.progressBar = progressBar;
        this.projectDirectorySupplier = projectDirectorySupplier;
        this.workspace = workspace;
        this.modelWriteBack = modelWriteBack;

        // Initialize managers
        initializeManagers();
        setupManagerCallbacks();

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupTabChangeListener();
        setupModelSelector();
    }

    /**
     * Initializes all manager instances.
     */
    private void initializeManagers() {
        // Session-tree bookkeeping shared by the session manager (status, removal)
        // and the tree manager (displayed nodes).
        SessionTreeBookkeeping<OptimisationStatus> sessionBookkeeping = new SessionTreeBookkeeping<>();

        // Initialize session manager first as others may depend on it
        this.sessionManager = new OptimisationSessionManager(
            stdioTaskManager,
            sessionBookkeeping,
            projectDirectorySupplier
        );

        // Initialize other managers. The config manager owns the GUI form, and with it
        // the model selector — so it is the source of the target working directory that
        // the other managers' file dialogs open at.
        this.treeManager = new OptimisationTreeManager(sessionBookkeeping);
        this.configManager = new OptimisationConfigManager(workspace);
        this.progressManager = new OptimisationProgressManager(progressBar);
        this.resultsManager = new OptimisationResultsManager();
        this.plotManager = new OptimisationPlotManager();
        this.panelBuilder = new OptimisationPanelBuilder();
        this.eventHandlers = new OptimisationEventHandlers(
            sessionManager,
            treeManager,
            progressManager,
            resultsManager,
            plotManager,
            statusUpdater
        );
        this.modelManager = new OptimisationModelManager(modelWriteBack);
        this.windowInitializer = new OptimisationWindowInitializer(
            treeManager, configManager, progressManager, resultsManager,
            plotManager, sessionManager, panelBuilder, eventHandlers
        );

        // Set up basic dependencies. File dialogs open at the *selected* model's folder.
        resultsManager.setWorkingDirectorySupplier(configManager.getGuiBuilder()::getTargetWorkingDirectory);
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
            new OptimisationWindowInitializer.WindowCallbacks() {
                @Override
                public void showMessagePanel() {
                    rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE);
                }

                @Override
                public void displayOptimisation(OptimisationInfo optInfo) {
                    OptimisationWindow.this.displayOptimisation(optInfo);
                }

                @Override
                public void saveCurrentConfig() {
                    saveCurrentConfigToNode();
                }
            }
        );

        // A direct edit of the INI text locks the GUI form for that optimisation.
        configManager.setOnIniManuallyEditedCallback(this::handleIniManuallyEdited);
    }

    /**
     * Displays an optimisation's information in the UI.
     * This is the central method for updating all UI elements when an optimisation is selected.
     */
    private void displayOptimisation(OptimisationInfo optInfo) {
        if (optInfo == null) {
            rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_MESSAGE);
            currentlyDisplayedNode = null;
            // Nothing is bound, so the next "New" should target the main window's
            // current model rather than a selection left over from a previous binding.
            guiBuilder.getModelSelectorPanel().resetToActive();
            guiBuilder.getModelSelectorPanel().setSelectionEnabled(true);
            configManager.updateSimulatedSeriesOptionsFromModel();
            return;
        }

        // Load configuration through managers
        configManager.loadConfiguration(optInfo);

        // Set editable state. The INI editor is editable unless the optimisation
        // is running; the GUI form is additionally disabled once the optimisation
        // is locked to INI-text editing.
        boolean running = optInfo.hasStartedRunning();
        boolean iniLocked = optInfo.isIniLocked();
        configEditor.setEditable(!running);
        guiBuilder.setComponentsEnabled(!running && !iniLocked);
        guiBuilder.setIniLockedBannerVisible(iniLocked);

        // Show what this optimisation is bound to. The selector deliberately survives
        // the INI lock - which model an optimisation runs against is not part of the
        // config INI - but a running optimisation cannot be retargeted at all.
        ModelSelectorPanel selector = guiBuilder.getModelSelectorPanel();
        if (optInfo.getTargetModel() != null) {
            selector.setSelectedModel(optInfo.getTargetModel());
        }
        selector.setSelectionEnabled(!running);
        configManager.updateSimulatedSeriesOptionsFromModel();

        // Update button states
        runButton.setEnabled(!running);
        loadConfigButton.setEnabled(!running);
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
            resultsManager.updateOptimisedModelDisplay(optInfo);  // Shows MSG_READY for READY status
            plotManager.clearPlot();
            if (bestObjectiveLabel != null) {
                bestObjectiveLabel.setText("Best: —");
            }
            if (evaluationProgressLabel != null) {
                evaluationProgressLabel.setText("Evaluations: —");
            }
            if (startTimeLabel != null) {
                startTimeLabel.setText("Start: —");
            }
            if (elapsedTimeLabel != null) {
                elapsedTimeLabel.setText("Elapsed: —");
            }
        }

        // Update progress manager
        progressManager.setCurrentOptimisation(optInfo);

        // Show optimisation panel
        rightPanelLayout.show(rightPanel, OptimisationUIConstants.CARD_OPTIMISATION);
        currentlyDisplayedNode = treeManager.getNodeForSession(optInfo.getSessionKey());

        // Switch to appropriate tab based on status
        if (optInfo.getStatus() == OptimisationStatus.DONE) {
            mainTabbedPane.setSelectedIndex(
                mainTabbedPane.indexOfTab(OptimisationUIConstants.TAB_RESULTS));
        }
    }

    /**
     * Shows the Optimisation window using singleton pattern.
     */
    public static void showOptimisationWindow(JFrame parentFrame,
                                              StdioTaskManager stdioTaskManager,
                                              Consumer<String> statusUpdater,
                                              StatusProgressBar progressBar,
                                              Supplier<File> projectDirectorySupplier,
                                              WorkspaceView workspace,
                                              ModelWriteBack modelWriteBack) {
        if (instance == null) {
            instance = new OptimisationWindow(parentFrame, stdioTaskManager,
                    statusUpdater, progressBar, projectDirectorySupplier,
                    workspace, modelWriteBack);
        }

        // The window is a singleton that outlives each showing, so an unbound selector
        // must be re-pointed at the model the user is looking at now.
        if (instance.getDisplayedOptimisation() == null) {
            instance.guiBuilder.getModelSelectorPanel().resetToActive();
        }
        instance.configManager.updateSimulatedSeriesOptionsFromModel();

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Kalix - Optimiser");
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
            treeManager::handleTreeSelection
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
        // Capture the current GUI form as the new optimisation's structured model,
        // and derive its INI text from that same model so the two start in sync.
        OptimisationConfigModel configModel = guiBuilder.captureToModel();
        String configText = guiBuilder.generateConfigText(configModel);

        // The target is whatever the Model selector shows — an explicit, visible choice,
        // no longer an implicit read of whichever main-window tab was in front.
        OpenModel target = guiBuilder.getSelectedModel();

        // Create optimisation through session manager with sessionKey passed to callbacks
        createOptimisation(new OptimisationSessionManager.NewOptimisation(
            target, labelFor(target), null, configText, configModel, false, null));
    }

    /** Submits a creation request with this window's three session callbacks attached. */
    private void createOptimisation(OptimisationSessionManager.NewOptimisation request) {
        sessionManager.createOptimisation(
            request,
            (sessionKey, progressInfo) -> eventHandlers.handleOptimisationProgress(sessionKey, progressInfo),
            (sessionKey, parameters) -> handleOptimisableParameters(sessionKey, parameters),
            (sessionKey, result) -> eventHandlers.handleOptimisationResult(sessionKey, result)
        );
    }

    /** The label for a model as it reads against the currently open set. */
    private String labelFor(OpenModel source) {
        return DocumentLabels.labelFor(source, workspace.openModels(), workspace.projectRoot());
    }

    /**
     * Wires the Model selector: a change retargets the displayed optimisation.
     *
     * <p>Because the kalixcli session is created — and given its copy of the model and
     * its working directory — when the optimisation is created, retargeting means
     * rebuilding that session. Nothing has been run yet at this point, so the only
     * casualty is the detected parameter list, which is model-specific and would be
     * wrong for the new target anyway. The user is told before it happens.</p>
     */
    private void setupModelSelector() {
        ModelSelectorPanel selector = guiBuilder.getModelSelectorPanel();

        selector.setSelectionGuard((previous, requested) -> {
            OptimisationInfo optInfo = getDisplayedOptimisation();
            if (optInfo == null || previous == null || requested == null) {
                return true;  // nothing bound yet - the choice is free
            }
            if (optInfo.hasStartedRunning()) {
                return false;  // guarded by the disabled selector too; belt and braces
            }
            if (requested.getWorkingDirectory() == null) {
                JOptionPane.showMessageDialog(this,
                    "'" + labelFor(requested) + "' has not been saved yet.\n"
                        + "Save it first so that data paths in the optimisation config can be resolved.",
                    "Model Not Saved",
                    JOptionPane.WARNING_MESSAGE);
                return false;
            }
            // Check the new target is usable *before* anything is torn down, so a bad
            // choice cancels the change rather than destroying the existing optimisation.
            String problem = sessionManager.describeTargetProblem(requested, labelFor(requested));
            if (problem != null) {
                JOptionPane.showMessageDialog(this, problem, "Cannot Use That Model",
                    JOptionPane.WARNING_MESSAGE);
                return false;
            }

            // A just-created optimisation has nothing configured to lose, and picking
            // the model straight after "New" is the common path - don't nag for it.
            if (!optInfo.isIniLocked() && optInfo.getConfigModel().getTerms().isEmpty()) {
                return true;
            }

            StringBuilder consequences = new StringBuilder();
            if (optInfo.isIniLocked()) {
                consequences.append("Its INI text is kept as-is — including any parameter lines,\n")
                            .append("which refer to the old model and will need updating by hand.\n");
            } else {
                consequences.append("Its detected parameters and expressions will be replaced with\n")
                            .append("ones for the new model.\n");
            }
            if (!optInfo.getConfigModel().getTerms().isEmpty()) {
                consequences.append("Observed-data paths are stored relative to the model's folder,\n")
                            .append("so objective terms may need re-pointing.\n");
            }

            int response = JOptionPane.showConfirmDialog(this,
                "Change '" + optInfo.getName() + "' to run against '" + labelFor(requested) + "'?\n\n"
                    + "The optimisation will be rebuilt against the new model.\n"
                    + consequences,
                "Change Target Model",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
            return response == JOptionPane.OK_OPTION;
        });

        selector.setSelectionListener(target -> {
            configManager.updateSimulatedSeriesOptionsFromModel();
            OptimisationInfo optInfo = getDisplayedOptimisation();
            if (optInfo != null && !optInfo.hasStartedRunning() && target != null) {
                rebindOptimisation(optInfo, target);
            }
        });
    }

    /**
     * Retargets a not-yet-run optimisation at a different model by replacing its
     * kalixcli session with one built against that model.
     *
     * <p>The config the user has built (objective terms, algorithm settings, INI text)
     * carries across; only the model-specific parameter list is rebuilt, by the
     * discovery round-trip the new session performs.</p>
     */
    private void rebindOptimisation(OptimisationInfo optInfo, OpenModel target) {
        boolean iniLocked = optInfo.isIniLocked();
        String name = optInfo.getName();
        String retiredKey = optInfo.getSessionKey();

        // Carry the config across. A locked optimisation's INI is authoritative and the
        // user owns it, so it survives verbatim; an unlocked one is regenerated from the
        // form minus its parameters, which belong to the model being left behind.
        OptimisationConfigModel configModel = guiBuilder.captureToModel();
        String configText;
        if (iniLocked) {
            configText = optInfo.getConfigSnapshot() != null ? optInfo.getConfigSnapshot() : "";
        } else {
            configModel.setParameters(new java.util.ArrayList<>());
            configText = guiBuilder.generateConfigText(configModel);
        }

        // The old optimisation is retired by the onCreated hook - i.e. only once the
        // replacement's session is up. If creation fails, the original survives.
        createOptimisation(new OptimisationSessionManager.NewOptimisation(
            target, labelFor(target), name, configText, configModel, iniLocked,
            () -> SwingUtilities.invokeLater(() -> {
                // Node detach must precede the session removal: the latter performs the
                // shared bookkeeping's single-shot remove, which clears the
                // sessionKey -> node entry the tree manager needs to find the node.
                treeManager.removeOptimisation(retiredKey);
                sessionManager.removeOptimisation(retiredKey);
                if (currentlyDisplayedNode != null
                        && currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo shown
                        && retiredKey.equals(shown.getSessionKey())) {
                    currentlyDisplayedNode = null;
                }
                if (statusUpdater != null) {
                    statusUpdater.accept("'" + name + "' retargeted to " + labelFor(target));
                }
            })));
    }

    /**
     * Handles the list of optimisable parameters reported by kalixcli for a session.
     *
     * <p>The parameters belong to a specific optimisation, so they are written into
     * that node's config model. The shared GUI form is only refreshed when that
     * optimisation is the one currently displayed — otherwise the parameters would
     * leak into whichever optimisation happens to be on screen.</p>
     */
    private void handleOptimisableParameters(String sessionKey, java.util.List<String> parameters) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            OptimisationInfo optInfo = sessionManager.getOptimisationInfo(sessionKey);
            if (optInfo == null) {
                return;
            }

            OptimisationConfigModel model = optInfo.getConfigModel();
            if (model == null) {
                model = new OptimisationConfigModel();
                optInfo.setConfigModel(model);
            }
            model.setParameters(ParametersConfigPanel.buildAutoGeneratedEntries(parameters));

            // Refresh the live GUI form only if this optimisation is on screen.
            if (isCurrentlyDisplayed(sessionKey)) {
                guiBuilder.loadFromModel(model);
            }

            if (statusUpdater != null) {
                statusUpdater.accept("Found " + parameters.size() + " optimisable parameters");
            }
        });
    }

    /**
     * Returns true if the given session is the optimisation currently shown in the tabs.
     */
    private boolean isCurrentlyDisplayed(String sessionKey) {
        if (currentlyDisplayedNode == null || sessionKey == null) {
            return false;
        }
        return currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo info
            && sessionKey.equals(info.getSessionKey());
    }

    /**
     * Returns the optimisation currently shown in the tabs, or null if none.
     */
    private OptimisationInfo getDisplayedOptimisation() {
        if (currentlyDisplayedNode != null
                && currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo info) {
            return info;
        }
        return null;
    }

    /**
     * Locks the currently displayed optimisation to INI-text editing in response
     * to a direct edit of the INI text (typing, pasting, or loading a config
     * file). The GUI form is frozen for that optimisation from this point on.
     */
    private void handleIniManuallyEdited() {
        OptimisationInfo optInfo = getDisplayedOptimisation();
        if (optInfo == null || optInfo.hasStartedRunning() || optInfo.isIniLocked()) {
            return;
        }
        optInfo.setIniLocked(true);
        guiBuilder.setComponentsEnabled(false);
        guiBuilder.setIniLockedBannerVisible(true);
        if (statusUpdater != null) {
            statusUpdater.accept("'" + optInfo.getName()
                + "' is now configured via INI text — the form is locked.");
        }
    }

    /**
     * Keeps the Config INI tab in sync with the GUI form: when the user switches
     * to the INI tab for an unlocked optimisation, the INI text is regenerated
     * from the current form so it reflects the latest edits.
     */
    private void setupTabChangeListener() {
        mainTabbedPane.addChangeListener(e -> {
            if (mainTabbedPane.getSelectedIndex()
                    == mainTabbedPane.indexOfTab(OptimisationUIConstants.TAB_CONFIG_INI)) {
                OptimisationInfo optInfo = getDisplayedOptimisation();
                if (optInfo != null && !optInfo.isIniLocked() && !optInfo.hasStartedRunning()) {
                    configManager.regenerateIniFromGui();
                }
            }
        });
    }

    /**
     * Saves the current config from GUI/text editor back to the node's configSnapshot.
     * Called when switching tree selections or before running optimization.
     */
    private void saveCurrentConfigToNode() {
        if (currentlyDisplayedNode == null) return;

        if (!(currentlyDisplayedNode.getUserObject() instanceof OptimisationInfo optInfo)) return;

        // Use config manager to save
        configManager.saveCurrentConfigToOptimisation(optInfo);
    }


    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Flush the displayed optimisation's config before the window
                // hides, so edits made without switching tree nodes are kept.
                saveCurrentConfigToNode();
            }

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

        // Determine the config to run. A locked optimisation runs its INI text
        // verbatim; an unlocked one runs config generated from the GUI form.
        String configText;
        if (optInfo.isIniLocked()) {
            configText = configManager.getCurrentConfig();
        } else {
            configText = configManager.generateConfigFromGui();
            // Keep the INI editor in sync with what is about to run.
            configManager.setConfiguration(configText);
        }

        // Capture the GUI form state onto the node before it is locked by running,
        // so re-selecting a finished optimisation restores the form it ran with.
        optInfo.setConfigModel(guiBuilder.captureToModel());

        // Run optimisation through manager with validation
        boolean started = sessionManager.runOptimisation(optInfo, configText,
            config -> configManager.validateConfiguration());

        if (started) {
            // Switch to results tab
            mainTabbedPane.setSelectedIndex(
                mainTabbedPane.indexOfTab(OptimisationUIConstants.TAB_RESULTS));
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
