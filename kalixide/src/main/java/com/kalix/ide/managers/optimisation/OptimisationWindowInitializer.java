package com.kalix.ide.managers.optimisation;

import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.windows.MinimalEditorWindow;
import com.kalix.ide.windows.SessionManagerWindow;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

/**
 * Handles initialization and setup of the OptimisationWindow components.
 * Reduces complexity in the main window class by centralizing initialization logic.
 */
public class OptimisationWindowInitializer {

    private final OptimisationTreeManager treeManager;
    private final OptimisationConfigManager configManager;
    private final OptimisationProgressManager progressManager;
    private final OptimisationResultsManager resultsManager;
    private final OptimisationPlotManager plotManager;
    private final OptimisationSessionManager sessionManager;
    private final OptimisationPanelBuilder panelBuilder;
    private final OptimisationEventHandlers eventHandlers;

    private com.kalix.ide.managers.StdioTaskManager stdioTaskManager;

    // Component references that need to be accessed
    private JTree optTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode currentOptimisationsNode;

    /**
     * Creates a new OptimisationWindowInitializer.
     */
    public OptimisationWindowInitializer(
            OptimisationTreeManager treeManager,
            OptimisationConfigManager configManager,
            OptimisationProgressManager progressManager,
            OptimisationResultsManager resultsManager,
            OptimisationPlotManager plotManager,
            OptimisationSessionManager sessionManager,
            OptimisationPanelBuilder panelBuilder,
            OptimisationEventHandlers eventHandlers) {
        this.treeManager = treeManager;
        this.configManager = configManager;
        this.progressManager = progressManager;
        this.resultsManager = resultsManager;
        this.plotManager = plotManager;
        this.sessionManager = sessionManager;
        this.panelBuilder = panelBuilder;
        this.eventHandlers = eventHandlers;
    }

    /**
     * Initializes all components and returns initialization result.
     */
    public InitializationResult initializeComponents(
            Consumer<OptimisationInfo> copyToMainAction,
            Consumer<OptimisationInfo> compareModelsAction,
            Consumer<OptimisationInfo> saveResultsAction,
            Runnable runOptimisationAction,
            Consumer<TreeSelectionEvent> treeSelectionHandler) {

        // Initialize tree
        optTree = treeManager.getTree();
        treeModel = (DefaultTreeModel) optTree.getModel();
        TreeNode root = (TreeNode) treeModel.getRoot();
        rootNode = (DefaultMutableTreeNode) root;

        // Get the Current Optimisations node (first child)
        if (rootNode.getChildCount() > 0) {
            currentOptimisationsNode = (DefaultMutableTreeNode) rootNode.getChildAt(0);
        }

        // Selection listener (renderer and context menu are installed by
        // OptimisationTreeManager's constructor - do not repeat them here)
        optTree.addTreeSelectionListener(treeSelectionHandler::accept);

        // Expand "Optimisation runs" node by default
        optTree.expandRow(0);

        // Build right panel using PanelBuilder
        JPanel rightPanel = panelBuilder.buildRightPanel();
        CardLayout rightPanelLayout = panelBuilder.getRightPanelLayout();
        JTabbedPane mainTabbedPane = panelBuilder.getMainTabbedPane();

        // Tab 1: Config (GUI Builder from ConfigManager)
        panelBuilder.addConfigGuiTab(configManager.getGuiBuilder());

        // Tab 2: Config INI (Text Editor from ConfigManager)
        RTextScrollPane configScrollPane = configManager.getConfigScrollPane();

        // Build Config INI tab with generate button
        JLabel configStatusLabel = panelBuilder.buildConfigIniTab(
            configManager.getConfigEditor(),
            configScrollPane,
            createGenerateConfigAction()
        );

        // Wire up config status callback now that the label exists
        configManager.setConfigStatusCallback(status -> configStatusLabel.setText(status));

        // Tab 3: Results
        JPanel plotWithLabelsPanel = plotManager.createPlotPanelWithLabels();

        panelBuilder.buildResultsTab(
            resultsManager.getOptimisedModelEditor(),
            plotWithLabelsPanel,
            progressManager,
            e -> copyToMainAction.accept(getCurrentOptimisationInfo()),
            e -> compareModelsAction.accept(getCurrentOptimisationInfo()),
            e -> saveResultsAction.accept(getCurrentOptimisationInfo())
        );

        // Setup button actions
        panelBuilder.getLoadConfigButton().addActionListener(
            e -> configManager.loadConfigFromFile(rightPanel.getRootPane())
        );

        panelBuilder.getSaveConfigButton().addActionListener(
            e -> configManager.saveConfigToFile(rightPanel.getRootPane(), configManager.getCurrentConfig())
        );

        panelBuilder.getRunButton().addActionListener(e -> runOptimisationAction.run());

        return new InitializationResult(
            optTree, treeModel, rootNode, currentOptimisationsNode,
            rightPanel, rightPanelLayout, mainTabbedPane,
            panelBuilder.getLoadConfigButton(),
            panelBuilder.getSaveConfigButton(),
            panelBuilder.getRunButton(),
            configStatusLabel,
            resultsManager.getOptimisedModelEditor(),
            plotManager.getPlotPanel()
        );
    }

    /**
     * Window-provided callbacks for manager wiring. A named interface (not ten
     * positional Consumer/Runnable parameters) so that a transposed argument can
     * no longer compile — per the July 2026 review, optimisation finding #15.
     */
    public interface WindowCallbacks {
        /** Shows the "select an optimisation" message card. */
        void showMessagePanel();

        /** Displays the given optimisation in the right-hand panel. */
        void displayOptimisation(OptimisationInfo optInfo);

        /** Saves the currently displayed config back to its optimisation node. */
        void saveCurrentConfig();
    }

    /**
     * Sets up all manager callbacks and interconnections.
     */
    public void setupManagerCallbacks(
            JFrame parentFrame,
            com.kalix.ide.managers.StdioTaskManager stdioTaskManager,
            Consumer<String> statusUpdater,
            WindowCallbacks callbacks) {

        // Store stdioTaskManager for use in action callbacks
        this.stdioTaskManager = stdioTaskManager;

        // Session manager callbacks
        sessionManager.setOnOptimisationCreated(optInfo -> {
            SwingUtilities.invokeLater(() -> {
                treeManager.addOptimisation(optInfo.getSessionKey(), optInfo);
                // Auto-select the new optimisation
                DefaultMutableTreeNode node = treeManager.getNodeForSession(optInfo.getSessionKey());
                if (node != null) {
                    TreePath path = new TreePath(treeModel.getPathToRoot(node));
                    optTree.setSelectionPath(path);
                    optTree.scrollPathToVisible(path);
                }
            });
        });

        sessionManager.setOnSessionCompleted(sessionKey -> {
            SwingUtilities.invokeLater(() -> {
                eventHandlers.updateTreeNodeForSession(sessionKey);
                // Refresh displays if this is the current optimisation
                DefaultMutableTreeNode currentNode = getCurrentNode();
                if (currentNode != null) {
                    Object userObject = currentNode.getUserObject();
                    if (userObject instanceof OptimisationInfo optInfo) {
                        if (optInfo.getSessionKey().equals(sessionKey)) {
                            callbacks.displayOptimisation(optInfo);
                        }
                    }
                }
            });
        });

        sessionManager.setOnErrorOccurred(errorMessage -> {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(parentFrame, errorMessage, "Error", JOptionPane.ERROR_MESSAGE);
            });
        });

        // Tree manager action callbacks
        setupTreeManagerActions(parentFrame, statusUpdater);

        // Tree selection callbacks
        treeManager.setOnNoSelectionCallback(callbacks::showMessagePanel);
        treeManager.setOnFolderSelectedCallback(callbacks::showMessagePanel);
        treeManager.setSaveCurrentConfigCallback(callbacks::saveCurrentConfig);
        treeManager.setOnOptimisationSelectedCallback(callbacks::displayOptimisation);
    }

    private void setupTreeManagerActions(JFrame parentFrame, Consumer<String> statusUpdater) {
        treeManager.setShowModelAction(optInfo -> {
            if (optInfo.getSession() != null &&
                    optInfo.getSession().getActiveProgram() instanceof OptimisationProgram program) {
                String modelText = program.getModelText();

                if (modelText != null && !modelText.isEmpty()) {
                    MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText, true);
                    editorWindow.setTitle(optInfo.getName() + " - Original Model");
                    editorWindow.setVisible(true);
                } else {
                    showModelNotAvailable(parentFrame);
                }
            } else {
                showModelNotAvailable(parentFrame);
            }
        });

        treeManager.setShowOptimisedModelAction(optInfo -> {
            resultsManager.showOptimisedModel(optInfo, parentFrame);
        });

        treeManager.setCompareModelAction(optInfo -> {
            resultsManager.compareModels(optInfo, parentFrame);
        });

        treeManager.setSaveResultsAction(optInfo -> {
            resultsManager.saveResults(optInfo, parentFrame);
        });

        treeManager.setRenameAction(optInfo -> {
            String newName = JOptionPane.showInputDialog(parentFrame, "Enter new name:", optInfo.getName());
            if (newName != null && !newName.trim().isEmpty()) {
                // renameOptimisation writes the new name onto the OptimisationInfo -
                // the single source of truth for the display name.
                boolean renamed = sessionManager.renameOptimisation(optInfo.getSessionKey(), newName);
                if (renamed) {
                    treeManager.refreshNode(optInfo.getSessionKey());
                }
            }
        });

        treeManager.setRemoveAction(optInfo -> {
            int confirm = JOptionPane.showConfirmDialog(parentFrame,
                "Remove optimisation '" + optInfo.getName() + "'?",
                "Confirm Remove",
                JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                // Detach the displayed node first: the session manager's
                // removeOptimisation performs the shared bookkeeping's single-shot
                // remove, which clears the sessionKey -> node entry the tree
                // manager needs to find the node.
                treeManager.removeOptimisation(optInfo.getSessionKey());
                sessionManager.removeOptimisation(optInfo.getSessionKey());
                optTree.clearSelection();
            }
        });

        treeManager.setStopOptimisationAction(optInfo -> {
            sessionManager.stopOptimisation(optInfo.getSessionKey());
            statusUpdater.accept("Stopped: " + optInfo.getName());
        });

        treeManager.setViewInSessionManagerAction(optInfo -> {
            String sessionKey = optInfo.getSession().getSessionKey();
            SessionManagerWindow.showSessionManagerWindow(parentFrame, this.stdioTaskManager, statusUpdater, sessionKey);
        });
    }

    private void showModelNotAvailable(JFrame parentFrame) {
        JOptionPane.showMessageDialog(parentFrame,
            "Model text is not available for this optimisation.",
            "Model Not Available",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private ActionListener createGenerateConfigAction() {
        return e -> {
            OptimisationInfo optInfo = getCurrentOptimisationInfo();
            if (optInfo != null && optInfo.isIniLocked()) {
                JOptionPane.showMessageDialog(optTree,
                    "This optimisation is configured via INI text and the form is locked.\n"
                        + "Regenerating from the form would discard your INI edits.",
                    "Configuration Locked",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            configManager.getGuiBuilder().generateAndSwitchToTextEditor();
        };
    }

    private OptimisationInfo getCurrentOptimisationInfo() {
        DefaultMutableTreeNode currentNode = getCurrentNode();
        if (currentNode != null && currentNode.getUserObject() instanceof OptimisationInfo) {
            return (OptimisationInfo) currentNode.getUserObject();
        }
        return null;
    }

    private DefaultMutableTreeNode getCurrentNode() {
        TreePath path = optTree.getSelectionPath();
        if (path != null) {
            return (DefaultMutableTreeNode) path.getLastPathComponent();
        }
        return null;
    }

    /**
     * Container for initialization results.
     */
    public static class InitializationResult {
        public final JTree optTree;
        public final DefaultTreeModel treeModel;
        public final DefaultMutableTreeNode rootNode;
        public final DefaultMutableTreeNode currentOptimisationsNode;
        public final JPanel rightPanel;
        public final CardLayout rightPanelLayout;
        public final JTabbedPane mainTabbedPane;
        public final JButton loadConfigButton;
        public final JButton saveConfigButton;
        public final JButton runButton;
        public final JLabel configStatusLabel;
        public final KalixIniTextArea optimisedModelEditor;
        public final PlotPanel convergencePlot;

        public InitializationResult(
                JTree optTree,
                DefaultTreeModel treeModel,
                DefaultMutableTreeNode rootNode,
                DefaultMutableTreeNode currentOptimisationsNode,
                JPanel rightPanel,
                CardLayout rightPanelLayout,
                JTabbedPane mainTabbedPane,
                JButton loadConfigButton,
                JButton saveConfigButton,
                JButton runButton,
                JLabel configStatusLabel,
                KalixIniTextArea optimisedModelEditor,
                PlotPanel convergencePlot) {
            this.optTree = optTree;
            this.treeModel = treeModel;
            this.rootNode = rootNode;
            this.currentOptimisationsNode = currentOptimisationsNode;
            this.rightPanel = rightPanel;
            this.rightPanelLayout = rightPanelLayout;
            this.mainTabbedPane = mainTabbedPane;
            this.loadConfigButton = loadConfigButton;
            this.saveConfigButton = saveConfigButton;
            this.runButton = runButton;
            this.configStatusLabel = configStatusLabel;
            this.optimisedModelEditor = optimisedModelEditor;
            this.convergencePlot = convergencePlot;
        }
    }
}