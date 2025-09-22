package com.kalix.gui.windows;

import com.kalix.gui.managers.StdioTaskManager;
import com.kalix.gui.cli.SessionManager;
import com.kalix.gui.cli.RunModelProgram;
import com.kalix.gui.utils.DialogUtils;
import com.kalix.gui.constants.AppConstants;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * User-friendly Run Manager window for managing model runs.
 * Replaces the debugging-focused CLI Sessions window with a more intuitive interface.
 */
public class RunManager extends JFrame {

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private Timer sessionUpdateTimer;
    private static RunManager instance;

    // Tree components
    private JTree runTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private DefaultMutableTreeNode currentRunsNode;
    private DefaultMutableTreeNode libraryNode;

    // Details panel components
    private JPanel detailsPanel;
    private CardLayout detailsCardLayout;
    private JTree outputsTree;
    private DefaultTreeModel outputsTreeModel;
    private JScrollPane outputsScrollPane;

    // Run tracking
    private Map<String, String> sessionToRunName = new HashMap<>();
    private Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private Map<String, RunStatus> lastKnownStatus = new HashMap<>();
    private int runCounter = 1;

    // Flag to prevent selection listener from firing during programmatic updates
    private boolean isUpdatingSelection = false;

    /**
     * Private constructor for singleton pattern.
     */
    private RunManager(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupUpdateTimer();
    }

    /**
     * Shows the Run Manager window using singleton pattern.
     */
    public static void showRunManager(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        if (instance == null) {
            instance = new RunManager(parentFrame, stdioTaskManager, statusUpdater);
        }

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
        instance.refreshRuns();
    }

    /**
     * Checks if the Run Manager window is currently open.
     */
    public static boolean isWindowOpen() {
        return instance != null && instance.isVisible();
    }

    /**
     * Refreshes the Run Manager if it's open.
     */
    public static void refreshRunManagerIfOpen() {
        if (instance != null) {
            instance.refreshRuns();
        }
    }

    /**
     * Selects the run associated with the given session ID if the Run Manager is open.
     */
    public static void selectRunIfOpen(String sessionId) {
        if (instance != null && instance.isVisible()) {
            instance.selectRun(sessionId);
        }
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Run Manager");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1000, 600); // Increased width by 25% (800 -> 1000) for better outputs viewing

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 50, parentLocation.y + 50);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // Initialize tree structure
        rootNode = new DefaultMutableTreeNode("Runs");
        currentRunsNode = new DefaultMutableTreeNode("Current runs");
        libraryNode = new DefaultMutableTreeNode("Library");

        rootNode.add(currentRunsNode);
        rootNode.add(libraryNode);

        treeModel = new DefaultTreeModel(rootNode);
        runTree = new JTree(treeModel);
        runTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        runTree.setRootVisible(false);
        runTree.setShowsRootHandles(true);
        runTree.setCellRenderer(new RunTreeCellRenderer());

        // Expand the tree nodes by default
        runTree.expandPath(new TreePath(currentRunsNode.getPath()));
        runTree.expandPath(new TreePath(libraryNode.getPath()));

        // Add tree selection listener to update details panel with outputs
        runTree.addTreeSelectionListener(this::onRunTreeSelectionChanged);

        // Add context menu
        setupContextMenu();

        // Initialize details panel components
        detailsCardLayout = new CardLayout();
        detailsPanel = new JPanel(detailsCardLayout);

        createDetailsComponents();
        createDetailsLayouts();
    }

    private void createDetailsComponents() {
        // Create outputs tree
        DefaultMutableTreeNode outputsRootNode = new DefaultMutableTreeNode("Outputs");
        outputsTreeModel = new DefaultTreeModel(outputsRootNode);
        outputsTree = new JTree(outputsTreeModel);
        outputsTree.setRootVisible(false);
        outputsTree.setShowsRootHandles(true);
        outputsScrollPane = new JScrollPane(outputsTree);
    }

    private void createDetailsLayouts() {
        // Create message panel for when no run is selected
        JPanel messagePanel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel("<html><center>Select a run to view outputs<br><br>Right-click on a run to:<br>• View STDIO Log<br>• Remove run</center></html>", SwingConstants.CENTER);
        messageLabel.setForeground(Color.GRAY);
        messagePanel.add(messageLabel, BorderLayout.CENTER);
        messagePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Create outputs panel for when a run is selected
        JPanel outputsPanel = new JPanel(new BorderLayout());
        outputsPanel.add(new JLabel("Model Outputs:"), BorderLayout.NORTH);
        outputsPanel.add(outputsScrollPane, BorderLayout.CENTER);
        outputsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Add both panels to details panel
        detailsPanel.add(messagePanel, "MESSAGE_PANEL");
        detailsPanel.add(outputsPanel, "OUTPUTS_PANEL");

        // Show message panel by default
        detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(150); // Reduced to 150px for more outputs space
        splitPane.setResizeWeight(0.15);   // Adjusted to match new proportions

        // Left side: tree
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Runs"));
        JScrollPane treeScrollPane = new JScrollPane(runTree);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Right side: details
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Details"));

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(detailsPanel);

        add(splitPane, BorderLayout.CENTER);

        // Footer
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);
        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        add(footerPanel, BorderLayout.SOUTH);
    }

    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.start();
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.stop();
                }
                instance = null;
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.stop();
                }
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.start();
                }
            }

            @Override
            public void windowActivated(WindowEvent e) {
                refreshRuns();
            }
        });
    }

    private void setupUpdateTimer() {
        sessionUpdateTimer = new Timer(2000, e -> refreshRuns());
        sessionUpdateTimer.setRepeats(true);
    }

    /**
     * Sets up the context menu for the run tree.
     */
    private void setupContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem cliLogItem = new JMenuItem("STDIO Log");
        cliLogItem.addActionListener(e -> showCliLogFromContextMenu());
        contextMenu.add(cliLogItem);

        contextMenu.addSeparator();

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeRunFromContextMenu());
        contextMenu.add(removeItem);

        // Add mouse listener for right-click
        runTree.addMouseListener(new MouseAdapter() {
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
                // Get the path at the mouse location
                TreePath path = runTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked
                    runTree.setSelectionPath(path);

                    // Check if it's a run item (not a folder)
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof RunInfo) {
                        contextMenu.show(runTree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    public void refreshRuns() {
        if (stdioTaskManager == null) return;

        SwingUtilities.invokeLater(() -> {
            Map<String, SessionManager.KalixSession> activeSessions = stdioTaskManager.getActiveSessions();
            boolean[] treeStructureChanged = {false};

            // Check for new sessions
            for (SessionManager.KalixSession session : activeSessions.values()) {
                String sessionKey = session.getSessionKey();

                if (!sessionToTreeNode.containsKey(sessionKey)) {
                    // New session - add to tree
                    String runName = "Run_" + runCounter++;
                    sessionToRunName.put(sessionKey, runName);

                    RunInfo runInfo = new RunInfo(runName, session);
                    DefaultMutableTreeNode runNode = new DefaultMutableTreeNode(runInfo);
                    currentRunsNode.add(runNode);
                    sessionToTreeNode.put(sessionKey, runNode);
                    lastKnownStatus.put(sessionKey, runInfo.getRunStatus());

                    treeStructureChanged[0] = true;
                } else {
                    // Existing session - check for status changes
                    DefaultMutableTreeNode existingNode = sessionToTreeNode.get(sessionKey);
                    RunInfo runInfo = (RunInfo) existingNode.getUserObject();
                    RunStatus currentStatus = runInfo.getRunStatus();
                    RunStatus lastStatus = lastKnownStatus.get(sessionKey);

                    if (lastStatus != currentStatus) {
                        // Status changed - refresh this node's display
                        treeModel.nodeChanged(existingNode);
                        lastKnownStatus.put(sessionKey, currentStatus);

                        // Update outputs if this run is currently selected
                        TreePath selectedPath = runTree.getSelectionPath();
                        if (selectedPath != null && selectedPath.getLastPathComponent() == existingNode) {
                            updateOutputsTree(runInfo);
                        }
                    }
                }
            }

            // Check for removed sessions
            sessionToTreeNode.entrySet().removeIf(entry -> {
                String sessionId = entry.getKey();
                if (!activeSessions.containsKey(sessionId)) {
                    // Session removed - remove from tree
                    DefaultMutableTreeNode nodeToRemove = entry.getValue();
                    currentRunsNode.remove(nodeToRemove);
                    sessionToRunName.remove(sessionId);
                    lastKnownStatus.remove(sessionId);
                    treeStructureChanged[0] = true;
                    return true;
                }
                return false;
            });

            // Only notify of structure changes if something actually changed
            if (treeStructureChanged[0]) {
                treeModel.nodeStructureChanged(currentRunsNode);
                runTree.expandPath(new TreePath(currentRunsNode.getPath()));
            }
        });
    }



    /**
     * Shows the CLI log window for the selected run.
     */
    private void showCliLogFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        StdioLogWindow.showStdioLogWindow(runInfo.runName, runInfo.session, stdioTaskManager, this);
    }

    /**
     * Removes a run from the context menu - terminates if active and removes from list.
     */
    private void removeRunFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        boolean isActive = runInfo.session.isActive();

        String message = isActive
            ? "Are you sure you want to stop and remove " + runInfo.runName + "?\n\nThis will terminate the running session and remove it from the list."
            : "Are you sure you want to remove " + runInfo.runName + " from the list?";

        if (DialogUtils.showConfirmation(this, message, "Remove Run")) {
            if (isActive) {
                // First terminate the session, then remove it
                stdioTaskManager.terminateSession(sessionKey)
                    .thenCompose(v -> {
                        // After termination, remove from list
                        return stdioTaskManager.removeSession(sessionKey);
                    })
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Stopped and removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionKey);
                        refreshRuns();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to stop/remove run: " + throwable.getMessage());
                            DialogUtils.showError(this,
                                "Failed to stop/remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            } else {
                // Just remove from list (session already terminated)
                stdioTaskManager.removeSession(sessionKey)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionKey);
                        refreshRuns();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to remove run: " + throwable.getMessage());
                            DialogUtils.showError(this,
                                "Failed to remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            }
        }
    }

    /**
     * Handles tree selection changes to update the details panel.
     */
    private void onRunTreeSelectionChanged(TreeSelectionEvent e) {
        // Ignore selection changes during programmatic updates
        if (isUpdatingSelection) {
            return;
        }

        updateDetailsPanel();
    }

    /**
     * Updates the details panel based on current tree selection.
     */
    private void updateDetailsPanel() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) {
            detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();

        // Check if the selected node is a run (has RunInfo as user object)
        if (selectedNode.getUserObject() instanceof RunInfo) {
            RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
            updateOutputsTree(runInfo);
            detailsCardLayout.show(detailsPanel, "OUTPUTS_PANEL");
        } else {
            // Selection is on a parent node (Current runs, Library)
            detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
        }
    }

    /**
     * Updates the outputs tree based on the selected run.
     */
    private void updateOutputsTree(RunInfo runInfo) {
        // Remember current expansion state
        List<TreePath> expandedPaths = new ArrayList<>();
        for (int i = 0; i < outputsTree.getRowCount(); i++) {
            TreePath path = outputsTree.getPathForRow(i);
            if (outputsTree.isExpanded(path)) {
                expandedPaths.add(path);
            }
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) outputsTreeModel.getRoot();
        root.removeAllChildren();

        // Get outputs from the run's program
        List<String> outputs = null;
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            outputs = program.getOutputsGenerated();
        }

        if (outputs != null && !outputs.isEmpty()) {
            // Sort outputs alphabetically for consistent tree organization
            outputs.sort(String::compareTo);

            // Parse dot-delimited strings into tree structure
            for (String output : outputs) {
                addOutputToTree(root, output);
            }
        } else {
            // Add a message indicating no outputs are available
            String message = runInfo.getRunStatus() == RunStatus.DONE ?
                "No outputs available" :
                "Outputs will appear when simulation completes";
            root.add(new DefaultMutableTreeNode(message));
        }

        outputsTreeModel.reload();

        // Restore expansion state or expand all if first time
        if (expandedPaths.isEmpty()) {
            // First time - expand all nodes
            for (int i = 0; i < outputsTree.getRowCount(); i++) {
                outputsTree.expandRow(i);
            }
        } else {
            // Restore previous expansion state
            for (TreePath expandedPath : expandedPaths) {
                // Try to find equivalent path in new tree
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    outputsTree.expandPath(newPath);
                }
            }
        }
    }

    /**
     * Adds a dot-delimited output string to the tree structure.
     * For example: "hydrology.streamflow.daily" becomes a tree:
     *   hydrology
     *     └── streamflow
     *           └── daily
     */
    private void addOutputToTree(DefaultMutableTreeNode root, String dotDelimitedOutput) {
        String[] parts = dotDelimitedOutput.split("\\.");
        DefaultMutableTreeNode currentNode = root;

        for (String part : parts) {
            // Look for existing child with this name
            DefaultMutableTreeNode childNode = null;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(part)) {
                    childNode = child;
                    break;
                }
            }

            // If child doesn't exist, create it
            if (childNode == null) {
                childNode = new DefaultMutableTreeNode(part);
                currentNode.add(childNode);
            }

            currentNode = childNode;
        }
    }

    /**
     * Finds an equivalent path in the current tree based on node names.
     */
    private TreePath findEquivalentPath(TreePath oldPath) {
        if (oldPath == null || oldPath.getPathCount() <= 1) {
            return null;
        }

        // Build path of node names
        String[] pathNames = new String[oldPath.getPathCount() - 1]; // Skip root
        for (int i = 1; i < oldPath.getPathCount(); i++) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) oldPath.getPathComponent(i);
            pathNames[i - 1] = node.getUserObject().toString();
        }

        // Try to find equivalent path in current tree
        DefaultMutableTreeNode currentNode = (DefaultMutableTreeNode) outputsTreeModel.getRoot();
        List<Object> newPathComponents = new ArrayList<>();
        newPathComponents.add(currentNode);

        for (String pathName : pathNames) {
            DefaultMutableTreeNode foundChild = null;
            for (int i = 0; i < currentNode.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentNode.getChildAt(i);
                if (child.getUserObject().toString().equals(pathName)) {
                    foundChild = child;
                    break;
                }
            }

            if (foundChild == null) {
                return null; // Path doesn't exist in new tree
            }

            newPathComponents.add(foundChild);
            currentNode = foundChild;
        }

        return new TreePath(newPathComponents.toArray());
    }

    /**
     * Selects the run associated with the given session ID.
     * This will expand the tree and select the run node if found.
     */
    private void selectRun(String sessionId) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode runNode = sessionToTreeNode.get(sessionId);
            if (runNode != null) {
                TreePath pathToRun = new TreePath(runNode.getPath());

                // Expand parent nodes to make the run visible
                runTree.expandPath(new TreePath(currentRunsNode.getPath()));

                // Select the run
                isUpdatingSelection = true;
                runTree.setSelectionPath(pathToRun);
                isUpdatingSelection = false;

                // Scroll to make the selection visible
                runTree.scrollPathToVisible(pathToRun);

                // Update details panel
                updateDetailsPanel();
            }
        });
    }

    /**
     * Enum representing the status of a simulation run.
     * This is separate from CLI session status.
     */
    public enum RunStatus {
        STARTING("Starting"),
        LOADING("Loading Model"),
        RUNNING("Running"),
        DONE("Done"),
        ERROR("Error"),
        STOPPED("Stopped");

        private final String displayName;

        RunStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Data class to hold run information.
     */
    private static class RunInfo {
        final String runName;
        final SessionManager.KalixSession session;

        RunInfo(String runName, SessionManager.KalixSession session) {
            this.runName = runName;
            this.session = session;
        }

        /**
         * Gets the run status based on the session's active program state.
         */
        public RunStatus getRunStatus() {
            if (session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) session.getActiveProgram();

                if (program.isFailed()) {
                    return RunStatus.ERROR;
                } else if (program.isCompleted()) {
                    return RunStatus.DONE;
                } else {
                    String stateDesc = program.getStateDescription();
                    if (stateDesc.contains("Loading")) {
                        return RunStatus.LOADING;
                    } else if (stateDesc.contains("Running")) {
                        return RunStatus.RUNNING;
                    } else {
                        return RunStatus.STARTING;
                    }
                }
            } else {
                // No active program or different program type
                // Determine status based on session state
                switch (session.getState()) {
                    case STARTING:
                        return RunStatus.STARTING;
                    case RUNNING:
                        return RunStatus.RUNNING;
                    case READY:
                        return RunStatus.DONE;
                    case ERROR:
                        return RunStatus.ERROR;
                    case TERMINATED:
                        return RunStatus.STOPPED;
                    default:
                        return RunStatus.STARTING;
                }
            }
        }

        @Override
        public String toString() {
            return runName;
        }
    }

    /**
     * Custom tree cell renderer for run tree.
     */
    private static class RunTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObject = node.getUserObject();

                if (userObject instanceof RunInfo) {
                    RunInfo runInfo = (RunInfo) userObject;
                    setText(runInfo.runName);

                    RunStatus runStatus = runInfo.getRunStatus();

                    // Color code by run status
                    if (!sel) {
                        switch (runStatus) {
                            case DONE:
                                setForeground(new Color(0, 120, 0)); // Dark green
                                break;
                            case RUNNING:
                            case LOADING:
                                setForeground(new Color(0, 0, 200)); // Blue
                                break;
                            case ERROR:
                                setForeground(new Color(200, 0, 0)); // Red
                                break;
                            case STARTING:
                                setForeground(new Color(150, 150, 0)); // Dark yellow
                                break;
                            case STOPPED:
                                setForeground(new Color(128, 128, 128)); // Gray
                                break;
                            default:
                                setForeground(Color.BLACK);
                                break;
                        }
                    }

                    // Set appropriate FontAwesome icon based on run status (25% smaller than toolbar icons)
                    int treeIconSize = 12; // 75% of AppConstants.TOOLBAR_ICON_SIZE (16px)
                    switch (runStatus) {
                        case STARTING:
                        case LOADING:
                            setIcon(FontIcon.of(FontAwesomeSolid.SUN, treeIconSize));
                            break;
                        case RUNNING:
                            setIcon(FontIcon.of(FontAwesomeSolid.HIKING, treeIconSize));
                            break;
                        case DONE:
                            setIcon(FontIcon.of(FontAwesomeSolid.CAMPGROUND, treeIconSize));
                            break;
                        case ERROR:
                            setIcon(FontIcon.of(FontAwesomeSolid.BUG, treeIconSize));
                            break;
                        case STOPPED:
                            setIcon(FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, treeIconSize));
                            break;
                        default:
                            setIcon(null);
                            break;
                    }
                }
            }

            return this;
        }
    }
}