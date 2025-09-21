package com.kalix.gui.windows;

import com.kalix.gui.managers.CliTaskManager;
import com.kalix.gui.cli.SessionManager;
import com.kalix.gui.cli.RunModelProgram;
import com.kalix.gui.utils.DialogUtils;
import com.kalix.gui.constants.AppConstants;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * User-friendly Run Manager window for managing model runs.
 * Replaces the debugging-focused CLI Sessions window with a more intuitive interface.
 */
public class RunManager extends JFrame {

    private final CliTaskManager cliTaskManager;
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

    // Run tracking
    private Map<String, String> sessionToRunName = new HashMap<>();
    private int runCounter = 1;

    /**
     * Private constructor for singleton pattern.
     */
    private RunManager(JFrame parentFrame, CliTaskManager cliTaskManager, Consumer<String> statusUpdater) {
        this.cliTaskManager = cliTaskManager;
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
    public static void showRunManager(JFrame parentFrame, CliTaskManager cliTaskManager, Consumer<String> statusUpdater) {
        if (instance == null) {
            instance = new RunManager(parentFrame, cliTaskManager, statusUpdater);
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

    private void setupWindow(JFrame parentFrame) {
        setTitle("Run Manager");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(800, 600);

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

        // Selection listener no longer needed - details moved to CLI Log window

        // Add context menu
        setupContextMenu();

        // Initialize details panel components
        detailsCardLayout = new CardLayout();
        detailsPanel = new JPanel(detailsCardLayout);

        createDetailsComponents();
        createDetailsLayouts();
    }

    private void createDetailsComponents() {
        // No longer needed - details moved to CLI Log window
    }

    private void createDetailsLayouts() {
        // Create simple message panel
        JPanel messagePanel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel("<html><center>Right-click on a run to:<br>• View CLI Log<br>• Remove run</center></html>", SwingConstants.CENTER);
        messageLabel.setForeground(Color.GRAY);
        messagePanel.add(messageLabel, BorderLayout.CENTER);
        messagePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Add to details panel
        detailsPanel.add(messagePanel, BorderLayout.CENTER);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(300);
        splitPane.setResizeWeight(0.3);

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

        JMenuItem cliLogItem = new JMenuItem("CLI Log");
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
        if (cliTaskManager == null) return;

        SwingUtilities.invokeLater(() -> {
            Map<String, SessionManager.KalixSession> activeSessions = cliTaskManager.getActiveSessions();

            // Remember current selection
            TreePath selectedPath = runTree.getSelectionPath();
            String selectedRunName = null;
            if (selectedPath != null && selectedPath.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
                if (selectedNode.getUserObject() instanceof RunInfo) {
                    selectedRunName = ((RunInfo) selectedNode.getUserObject()).runName;
                }
            }

            // Clear current runs
            currentRunsNode.removeAllChildren();

            // Add sessions as runs
            for (SessionManager.KalixSession session : activeSessions.values()) {
                String sessionId = session.getSessionId();
                String runName = sessionToRunName.get(sessionId);

                if (runName == null) {
                    runName = "Run_" + runCounter++;
                    sessionToRunName.put(sessionId, runName);
                }

                RunInfo runInfo = new RunInfo(runName, session);
                DefaultMutableTreeNode runNode = new DefaultMutableTreeNode(runInfo);
                currentRunsNode.add(runNode);
            }

            // Remove mappings for sessions that no longer exist
            sessionToRunName.entrySet().removeIf(entry -> !activeSessions.containsKey(entry.getKey()));

            // Refresh tree
            treeModel.nodeStructureChanged(currentRunsNode);
            runTree.expandPath(new TreePath(currentRunsNode.getPath()));

            // Restore selection if possible
            if (selectedRunName != null) {
                restoreSelection(selectedRunName);
            }
        });
    }

    private void restoreSelection(String runName) {
        for (int i = 0; i < currentRunsNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) currentRunsNode.getChildAt(i);
            if (child.getUserObject() instanceof RunInfo) {
                RunInfo runInfo = (RunInfo) child.getUserObject();
                if (runInfo.runName.equals(runName)) {
                    TreePath path = new TreePath(child.getPath());
                    runTree.setSelectionPath(path);
                    break;
                }
            }
        }
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
        CliLogWindow.showCliLogWindow(runInfo.runName, runInfo.session, this);
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
        String sessionId = runInfo.session.getSessionId();
        boolean isActive = runInfo.session.isActive();

        String message = isActive
            ? "Are you sure you want to stop and remove " + runInfo.runName + "?\n\nThis will terminate the running session and remove it from the list."
            : "Are you sure you want to remove " + runInfo.runName + " from the list?";

        if (DialogUtils.showConfirmation(this, message, "Remove Run")) {
            if (isActive) {
                // First terminate the session, then remove it
                cliTaskManager.terminateSession(sessionId)
                    .thenCompose(v -> {
                        // After termination, remove from list
                        return cliTaskManager.removeSession(sessionId);
                    })
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Stopped and removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionId);
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
                cliTaskManager.removeSession(sessionId)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Removed run: " + runInfo.runName);
                        sessionToRunName.remove(sessionId);
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

                    // Set appropriate FontAwesome icon based on run status
                    switch (runStatus) {
                        case STARTING:
                        case LOADING:
                            setIcon(FontIcon.of(FontAwesomeSolid.SUN, AppConstants.TOOLBAR_ICON_SIZE));
                            break;
                        case RUNNING:
                            setIcon(FontIcon.of(FontAwesomeSolid.HIKING, AppConstants.TOOLBAR_ICON_SIZE));
                            break;
                        case DONE:
                            setIcon(FontIcon.of(FontAwesomeSolid.CAMPGROUND, AppConstants.TOOLBAR_ICON_SIZE));
                            break;
                        case ERROR:
                            setIcon(FontIcon.of(FontAwesomeSolid.BUG, AppConstants.TOOLBAR_ICON_SIZE));
                            break;
                        case STOPPED:
                            setIcon(FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, AppConstants.TOOLBAR_ICON_SIZE));
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