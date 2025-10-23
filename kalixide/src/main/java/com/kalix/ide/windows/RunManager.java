package com.kalix.ide.windows;

import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.managers.TimeSeriesRequestManager;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.flowviz.data.TimeSeriesData;
import com.kalix.ide.flowviz.data.DataSet;
import com.kalix.ide.flowviz.PlotPanel;
import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * User-friendly Run Manager window for managing model runs.
 * Replaces the debugging-focused CLI Sessions window with a more intuitive interface.
 */
public class RunManager extends JFrame {

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final TimeSeriesRequestManager timeSeriesRequestManager;
    private javax.swing.Timer sessionUpdateTimer;
    private static RunManager instance;
    private static java.util.function.Supplier<java.io.File> baseDirectorySupplier;

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

    // Plot and stats components
    private PlotPanel plotPanel;
    private DataSet plotDataSet;
    private JScrollPane statsScrollPane;
    private JTable statsTable;
    private StatsTableModel statsTableModel;

    // Series color management
    private static final Color[] SERIES_COLORS = {
        Color.BLUE, Color.RED, Color.GREEN, Color.ORANGE,
        Color.MAGENTA, Color.CYAN, Color.PINK, Color.YELLOW
    };
    private Map<String, Color> seriesColorMap = new HashMap<>();
    private Set<String> selectedSeries = new HashSet<>();

    // Run tracking
    private Map<String, String> sessionToRunName = new HashMap<>();
    private Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private Map<String, RunStatus> lastKnownStatus = new HashMap<>();
    private int runCounter = 1;

    // Flag to prevent selection listener from firing during programmatic updates
    private boolean isUpdatingSelection = false;

    /**
     * Natural sorting comparator that is case-insensitive and number-aware.
     * Compares strings by splitting them into text and numeric parts.
     * Text parts are compared case-insensitively, numeric parts as integers.
     *
     * Examples:
     * - "node1" < "node2" < "node10" (numeric comparison)
     * - "Node1" == "node1" (case-insensitive)
     * - "node1_inflow" < "node10_gauge" (numeric-aware)
     */
    private static int naturalCompare(String s1, String s2) {
        int i1 = 0, i2 = 0;
        int len1 = s1.length(), len2 = s2.length();

        while (i1 < len1 && i2 < len2) {
            // Determine if current characters are digits
            boolean isDigit1 = Character.isDigit(s1.charAt(i1));
            boolean isDigit2 = Character.isDigit(s2.charAt(i2));

            if (isDigit1 && isDigit2) {
                // Both are digits - extract and compare as numbers
                int numStart1 = i1, numStart2 = i2;
                while (i1 < len1 && Character.isDigit(s1.charAt(i1))) i1++;
                while (i2 < len2 && Character.isDigit(s2.charAt(i2))) i2++;

                String numStr1 = s1.substring(numStart1, i1);
                String numStr2 = s2.substring(numStart2, i2);

                // Compare as integers (handle large numbers gracefully)
                int cmp;
                try {
                    long num1 = Long.parseLong(numStr1);
                    long num2 = Long.parseLong(numStr2);
                    cmp = Long.compare(num1, num2);
                } catch (NumberFormatException e) {
                    // Fall back to string comparison if numbers too large
                    cmp = numStr1.compareTo(numStr2);
                }

                if (cmp != 0) return cmp;
            } else if (isDigit1) {
                // Digits come before non-digits
                return -1;
            } else if (isDigit2) {
                // Non-digits come after digits
                return 1;
            } else {
                // Both are non-digits - compare case-insensitively
                char c1 = Character.toLowerCase(s1.charAt(i1));
                char c2 = Character.toLowerCase(s2.charAt(i2));
                if (c1 != c2) {
                    return Character.compare(c1, c2);
                }
                i1++;
                i2++;
            }
        }

        // One string is a prefix of the other
        return Integer.compare(len1, len2);
    }

    /**
     * Private constructor for singleton pattern.
     */
    private RunManager(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.timeSeriesRequestManager = new TimeSeriesRequestManager(stdioTaskManager.getSessionManager());

        // Connect TimeSeriesRequestManager to SessionManager for response handling
        stdioTaskManager.getSessionManager().setTimeSeriesResponseHandler(
            timeSeriesRequestManager::handleJsonResponse
        );

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
     * Sets the base directory supplier for file dialogs.
     * This should be called to provide the model's directory for saving results.
     *
     * @param supplier Supplier that returns the base directory (null if no file is loaded)
     */
    public static void setBaseDirectorySupplier(java.util.function.Supplier<java.io.File> supplier) {
        baseDirectorySupplier = supplier;
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
     * Selects the run associated with the given session key if the Run Manager is open.
     */
    public static void selectRunIfOpen(String sessionKey) {
        if (instance != null && instance.isVisible()) {
            instance.selectRun(sessionKey);
        }
    }

    /**
     * Gets the Run Manager instance if it exists and is open.
     * Used by ThemeManager for theme updates.
     */
    public static RunManager getOpenInstance() {
        return (instance != null && instance.isVisible()) ? instance : null;
    }

    /**
     * Gets the run name for a session key, if available.
     */
    public static String getRunNameForSession(String sessionKey) {
        if (instance != null) {
            return instance.sessionToRunName.get(sessionKey);
        }
        return null;
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
        runTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
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
        outputsTree.setCellRenderer(new OutputsTreeCellRenderer());

        // Enable multiple selection for the outputs tree to allow plotting multiple series
        outputsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);

        // Add selection listener to fetch timeseries data for leaf nodes and update plot
        outputsTree.addTreeSelectionListener(this::onOutputsTreeSelectionChanged);

        // Add context menu for expand/collapse all
        setupOutputsTreeContextMenu();

        outputsScrollPane = new JScrollPane(outputsTree);

        // Create plot panel
        plotPanel = new PlotPanel();
        plotDataSet = new DataSet();
        plotPanel.setDataSet(plotDataSet);

        // Enable coordinate display for mouse hover
        plotPanel.setShowCoordinates(true);

        // Set Auto-Y mode to default preference value
        boolean autoYMode = PreferenceManager.getFileBoolean(PreferenceKeys.FLOWVIZ_AUTO_Y_MODE, true);
        plotPanel.setAutoYMode(autoYMode);

        // Create stats panel
        createStatsPanel();
    }

    private void createStatsPanel() {
        statsTableModel = new StatsTableModel();
        statsTable = new JTable(statsTableModel);
        statsTable.setFillsViewportHeight(true);
        statsTable.setRowSelectionAllowed(false);

        statsScrollPane = new JScrollPane(statsTable);
        statsScrollPane.setPreferredSize(new Dimension(0, 150));
    }

    private void createDetailsLayouts() {
        // Create message panel for when no run is selected
        JPanel messagePanel = new JPanel(new BorderLayout());
        JLabel messageLabel = new JLabel("<html><center>Select a run to view outputs<br><br>Right-click on a run to:<br>• View in KalixCLI Session Manager<br>• Remove run</center></html>", SwingConstants.CENTER);
        messageLabel.setForeground(Color.GRAY);
        messagePanel.add(messageLabel, BorderLayout.CENTER);
        messagePanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Create main outputs panel with three sections when a run is selected
        JPanel outputsMainPanel = createOutputsMainPanel();

        // Add both panels to details panel
        detailsPanel.add(messagePanel, "MESSAGE_PANEL");
        detailsPanel.add(outputsMainPanel, "OUTPUTS_PANEL");

        // Show message panel by default
        detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
    }

    private JPanel createOutputsMainPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        // Create horizontal split pane for outputs tree and visualization
        JSplitPane horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        horizontalSplit.setDividerLocation(150); // 200px for outputs tree
        horizontalSplit.setResizeWeight(0); // Give equal weight initially

        // Left side: outputs tree panel
        JPanel outputsTreePanel = new JPanel(new BorderLayout());
        outputsTreePanel.add(outputsScrollPane, BorderLayout.CENTER);

        // Right side: vertical split pane for plot and stats
        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplit.setDividerLocation(350); // 350px for plot
        verticalSplit.setResizeWeight(0.75); // Give more space to plot

        // Top of right side: plot panel
        JPanel plotWrapperPanel = new JPanel(new BorderLayout());
        plotWrapperPanel.add(plotPanel, BorderLayout.CENTER);

        // Bottom of right side: stats table
        verticalSplit.setTopComponent(plotWrapperPanel);
        verticalSplit.setBottomComponent(statsScrollPane);

        // Assemble the layout
        horizontalSplit.setLeftComponent(outputsTreePanel);
        horizontalSplit.setRightComponent(verticalSplit);

        mainPanel.add(horizontalSplit, BorderLayout.CENTER);

        return mainPanel;
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create main split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(190);
        splitPane.setResizeWeight(0);

        // Left side: vertical split with runs tree and outputs tree
        JSplitPane leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        leftSplitPane.setDividerLocation(200); // 200px for runs tree
        leftSplitPane.setResizeWeight(0.5); // Equal space for both trees

        // Top of left side: runs tree
        JPanel runsPanel = new JPanel(new BorderLayout());
        runsPanel.setBorder(BorderFactory.createTitledBorder("Runs"));
        JScrollPane treeScrollPane = new JScrollPane(runTree);
        runsPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Bottom of left side: outputs tree
        JPanel outputsPanel = new JPanel(new BorderLayout());
        outputsPanel.setBorder(BorderFactory.createTitledBorder("Available timeseries"));
        outputsPanel.add(outputsScrollPane, BorderLayout.CENTER);

        leftSplitPane.setTopComponent(runsPanel);
        leftSplitPane.setBottomComponent(outputsPanel);

        // Right side: vertical split pane for plot and stats (reusing existing layout)
        JSplitPane rightVerticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightVerticalSplit.setDividerLocation(350); // 350px for plot
        rightVerticalSplit.setResizeWeight(0.75); // Give more space to plot

        // Top of right side: plot panel
        JPanel plotWrapperPanel = new JPanel(new BorderLayout());
        plotWrapperPanel.add(plotPanel, BorderLayout.CENTER);

        // Bottom of right side: stats table
        rightVerticalSplit.setTopComponent(plotWrapperPanel);
        rightVerticalSplit.setBottomComponent(statsScrollPane);

        splitPane.setLeftComponent(leftSplitPane);
        splitPane.setRightComponent(rightVerticalSplit);

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
        sessionUpdateTimer = new javax.swing.Timer(2000, e -> refreshRuns());
        sessionUpdateTimer.setRepeats(true);
    }

    /**
     * Sets up the context menu for the run tree.
     */
    private void setupContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> renameRunFromContextMenu());
        contextMenu.add(renameItem);

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeRunFromContextMenu());
        contextMenu.add(removeItem);

        contextMenu.addSeparator();

        JMenuItem saveResultsItem = new JMenuItem("Save results (csv)");
        saveResultsItem.addActionListener(e -> saveResultsFromContextMenu());
        contextMenu.add(saveResultsItem);

        JMenuItem showModelItem = new JMenuItem("Show Model");
        showModelItem.addActionListener(e -> showModelFromContextMenu());
        contextMenu.add(showModelItem);

        JMenuItem sessionManagerItem = new JMenuItem("View in KalixCLI Session Manager");
        sessionManagerItem.addActionListener(e -> showInSessionManagerFromContextMenu());
        contextMenu.add(sessionManagerItem);

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

    /**
     * Sets up the context menu for the outputs tree with expand/collapse operations.
     */
    private void setupOutputsTreeContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem expandAllItem = new JMenuItem("Expand All");
        expandAllItem.addActionListener(e -> expandAllFromSelected());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse All");
        collapseAllItem.addActionListener(e -> collapseAllFromSelected());
        contextMenu.add(collapseAllItem);

        // Add mouse listener for right-click
        outputsTree.addMouseListener(new MouseAdapter() {
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
                TreePath path = outputsTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked if not already selected
                    if (!outputsTree.isPathSelected(path)) {
                        outputsTree.setSelectionPath(path);
                    }
                    contextMenu.show(outputsTree, e.getX(), e.getY());
                } else {
                    // Right-clicked on empty space - still show menu (applies to root)
                    contextMenu.show(outputsTree, e.getX(), e.getY());
                }
            }
        });
    }

    /**
     * Expands all nodes recursively from the selected node(s).
     */
    private void expandAllFromSelected() {
        TreePath[] selectedPaths = outputsTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // No selection - expand all from root
            expandAllChildren(new TreePath(outputsTreeModel.getRoot()));
        } else {
            // Expand all from each selected node
            for (TreePath path : selectedPaths) {
                expandAllChildren(path);
            }
        }
    }

    /**
     * Recursively expands all children of a given tree path.
     */
    private void expandAllChildren(TreePath path) {
        outputsTree.expandPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath childPath = path.pathByAddingChild(node.getChildAt(i));
            expandAllChildren(childPath);
        }
    }

    /**
     * Collapses all nodes recursively from the selected node(s).
     */
    private void collapseAllFromSelected() {
        TreePath[] selectedPaths = outputsTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // No selection - collapse all from root
            collapseAllChildren(new TreePath(outputsTreeModel.getRoot()));
        } else {
            // Collapse all from each selected node
            for (TreePath path : selectedPaths) {
                collapseAllChildren(path);
            }
        }
    }

    /**
     * Recursively collapses all children of a given tree path.
     */
    private void collapseAllChildren(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        // Collapse children first (bottom-up)
        for (int i = 0; i < node.getChildCount(); i++) {
            TreePath childPath = path.pathByAddingChild(node.getChildAt(i));
            collapseAllChildren(childPath);
        }
        outputsTree.collapsePath(path);
    }

    public void refreshRuns() {
        if (stdioTaskManager == null) return;

        SwingUtilities.invokeLater(() -> {
            Map<String, SessionManager.KalixSession> activeSessions = stdioTaskManager.getActiveSessions();
            boolean[] treeStructureChanged = {false};

            // Check for new sessions
            for (SessionManager.KalixSession session : activeSessions.values()) {
                // FILTER: Only show simulation runs (RunModelProgram)
                // Other session types (OptimisationProgram, etc.) are managed elsewhere
                if (!(session.getActiveProgram() instanceof RunModelProgram)) {
                    continue; // Skip non-simulation sessions
                }

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
                String sessionKey = entry.getKey();
                if (!activeSessions.containsKey(sessionKey)) {
                    // Session removed - remove from tree
                    DefaultMutableTreeNode nodeToRemove = entry.getValue();
                    currentRunsNode.remove(nodeToRemove);
                    sessionToRunName.remove(sessionKey);
                    lastKnownStatus.remove(sessionKey);
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
     * Shows a dialog to rename the selected run.
     */
    private void renameRunFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String currentName = runInfo.runName;

        // Show input dialog for new name
        String newName = (String) JOptionPane.showInputDialog(
            this,
            "Enter new name for the run:",
            "Rename Run",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );

        if (newName != null) {
            newName = newName.trim();

            // Validate the new name
            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Run name cannot be empty.",
                    "Invalid Name",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Check if name is already in use by another run
            String sessionKey = runInfo.session.getSessionKey();
            for (String existingName : sessionToRunName.values()) {
                if (existingName.equals(newName) && !existingName.equals(currentName)) {
                    JOptionPane.showMessageDialog(this,
                        "A run with the name '" + newName + "' already exists.\nPlease choose a different name.",
                        "Duplicate Name",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // Update the run name
            runInfo.setRunName(newName);
            sessionToRunName.put(sessionKey, newName);

            // Simple node refresh - just update this one node
            treeModel.nodeChanged(selectedNode);

            // Update status
            statusUpdater.accept("Renamed run '" + currentName + "' to '" + newName + "'");
        }
    }

    /**
     * Opens the KalixCLI Session Manager window with the selected run's session.
     */
    private void showInSessionManagerFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        SessionManagerWindow.showSessionManagerWindow(this, stdioTaskManager, statusUpdater, sessionKey);
    }

    /**
     * Shows the model INI string for the selected run in a MinimalEditorWindow.
     */
    private void showModelFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();

        // Get the model text from the RunModelProgram
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            String modelText = program.getModelText();

            if (modelText != null && !modelText.isEmpty()) {
                // Create and show MinimalEditorWindow with the model text
                MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText);
                editorWindow.setTitle("Model - " + runInfo.runName);
                editorWindow.setSyntaxEditingStyle(org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_NONE);
                editorWindow.setVisible(true);
            } else {
                JOptionPane.showMessageDialog(
                    this,
                    "Model text is not available for this run.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        } else {
            JOptionPane.showMessageDialog(
                this,
                "This run does not contain model information.",
                "Not a Model Run",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
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
     * Handles save results action from context menu.
     */
    private void saveResultsFromContextMenu() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo)) return;

        RunInfo runInfo = (RunInfo) selectedNode.getUserObject();
        String sessionKey = runInfo.session.getSessionKey();
        String kalixcliUid = runInfo.session.getKalixcliUid();

        // Check if the run has completed successfully
        RunStatus status = runInfo.getRunStatus();
        if (status != RunStatus.DONE) {
            String statusText = status == RunStatus.ERROR ? "failed" :
                              status == RunStatus.RUNNING ? "still running" : "not completed";
            statusUpdater.accept("Cannot save results: run " + runInfo.runName + " has " + statusText);
            return;
        }

        // Generate default filename: {run_name}_{uid}.csv
        String safeRunName = runInfo.runName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String defaultFilename = safeRunName + "_" + (kalixcliUid != null ? kalixcliUid : "unknown") + ".csv";

        // Show save dialog
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        fileChooser.setSelectedFile(new File(defaultFilename));

        // Set initial directory to model directory if available
        if (baseDirectorySupplier != null) {
            java.io.File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Add .csv extension if not present
            String fileName = selectedFile.getName();
            if (!fileName.toLowerCase().endsWith(".csv")) {
                selectedFile = new File(selectedFile.getParent(), fileName + ".csv");
            }

            // Send save_results command to kalixcli
            String command = String.format(
                "{\"m\":\"cmd\",\"c\":\"save_results\",\"p\":{\"path\":\"%s\",\"format\":\"csv\"}}",
                selectedFile.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"")
            );

            try {
                stdioTaskManager.sendCommand(sessionKey, command);
                statusUpdater.accept("Saving results to: " + selectedFile.getName());

                // TODO: We should ideally wait for the response and update status accordingly
                // For now, we'll show immediate feedback

            } catch (Exception e) {
                statusUpdater.accept("Failed to send save command: " + e.getMessage());
                DialogUtils.showError(this,
                    "Failed to save results: " + e.getMessage(),
                    "Save Results Error");
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
        TreePath[] selectedPaths = runTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
            return;
        }

        // Collect all selected RunInfo objects
        List<RunInfo> selectedRuns = new ArrayList<>();
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            if (node.getUserObject() instanceof RunInfo) {
                selectedRuns.add((RunInfo) node.getUserObject());
            }
        }

        if (selectedRuns.isEmpty()) {
            // Selection is on parent nodes only (Current runs, Library)
            detailsCardLayout.show(detailsPanel, "MESSAGE_PANEL");
        } else {
            // Update outputs tree with all selected runs
            updateOutputsTreeForMultipleRuns(selectedRuns);
            detailsCardLayout.show(detailsPanel, "OUTPUTS_PANEL");
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
            // Sort outputs using natural sorting (case-insensitive, number-aware)
            outputs.sort(RunManager::naturalCompare);

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
     * Updates the outputs tree for multiple selected runs using smart hybrid structure.
     * Series available in multiple runs become parent nodes with run children.
     * Series available in only one run become simple leaf nodes.
     */
    private void updateOutputsTreeForMultipleRuns(List<RunInfo> selectedRuns) {
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

        // If only one run selected, use the original simple tree structure
        if (selectedRuns.size() == 1) {
            updateOutputsTreeSingleRun(root, selectedRuns.get(0));
        } else {
            // Build multi-run hybrid tree
            updateOutputsTreeMultiRun(root, selectedRuns);
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
                TreePath newPath = findEquivalentPath(expandedPath);
                if (newPath != null) {
                    outputsTree.expandPath(newPath);
                }
            }
        }
    }

    /**
     * Populates the tree for a single run using SeriesLeafNode objects.
     */
    private void updateOutputsTreeSingleRun(DefaultMutableTreeNode root, RunInfo runInfo) {
        List<String> outputs = null;
        if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
            RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
            outputs = program.getOutputsGenerated();
        }

        if (outputs != null && !outputs.isEmpty()) {
            outputs.sort(RunManager::naturalCompare);
            for (String seriesName : outputs) {
                // Create standalone leaf node with showSeriesName=true (shows "ds_1 [Run_1]")
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, runInfo, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            }
        } else {
            String message = runInfo.getRunStatus() == RunStatus.DONE ?
                "No outputs available" :
                "Outputs will appear when simulation completes";
            root.add(new DefaultMutableTreeNode(message));
        }
    }

    /**
     * Populates the tree for multiple runs using smart hybrid structure.
     */
    private void updateOutputsTreeMultiRun(DefaultMutableTreeNode root, List<RunInfo> selectedRuns) {
        // Map: series name -> list of RunInfo that have this series
        Map<String, List<RunInfo>> seriesAvailability = new LinkedHashMap<>();

        // Collect all outputs from all selected runs
        for (RunInfo runInfo : selectedRuns) {
            List<String> outputs = null;
            if (runInfo.session.getActiveProgram() instanceof RunModelProgram) {
                RunModelProgram program = (RunModelProgram) runInfo.session.getActiveProgram();
                outputs = program.getOutputsGenerated();
            }

            if (outputs != null) {
                for (String output : outputs) {
                    seriesAvailability.computeIfAbsent(output, k -> new ArrayList<>()).add(runInfo);
                }
            }
        }

        if (seriesAvailability.isEmpty()) {
            root.add(new DefaultMutableTreeNode("No outputs available from selected runs"));
            return;
        }

        // Sort series names using natural sorting (case-insensitive, number-aware)
        List<String> sortedSeries = new ArrayList<>(seriesAvailability.keySet());
        sortedSeries.sort(RunManager::naturalCompare);

        // Build hybrid tree structure
        for (String seriesName : sortedSeries) {
            List<RunInfo> runsWithSeries = seriesAvailability.get(seriesName);

            if (runsWithSeries.size() == 1) {
                // Only one run has this series - create standalone leaf node
                RunInfo singleRun = runsWithSeries.get(0);
                SeriesLeafNode leafNode = new SeriesLeafNode(seriesName, singleRun, true);
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(leafNode);
                addHierarchicalNodeToTree(root, seriesName, node);
            } else {
                // Multiple runs have this series - create parent with children
                SeriesParentNode parentNode = new SeriesParentNode(seriesName, runsWithSeries);
                DefaultMutableTreeNode parentTreeNode = new DefaultMutableTreeNode(parentNode);

                // Add children for each run
                for (RunInfo runInfo : runsWithSeries) {
                    SeriesLeafNode childLeaf = new SeriesLeafNode(seriesName, runInfo, false);
                    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(childLeaf);
                    parentTreeNode.add(childNode);
                }

                addHierarchicalNodeToTree(root, seriesName, parentTreeNode);
            }
        }
    }

    /**
     * Adds a node to the tree while preserving hierarchical structure (dot-delimited paths).
     */
    private void addHierarchicalNodeToTree(DefaultMutableTreeNode root, String seriesName, DefaultMutableTreeNode nodeToAdd) {
        String[] parts = seriesName.split("\\.");
        if (parts.length == 1) {
            // No hierarchy, add directly
            root.add(nodeToAdd);
        } else {
            // Build hierarchy
            DefaultMutableTreeNode current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                DefaultMutableTreeNode child = findOrCreateChild(current, parts[i]);
                current = child;
            }
            current.add(nodeToAdd);
        }
    }

    /**
     * Finds or creates a child node with the given name.
     */
    private DefaultMutableTreeNode findOrCreateChild(DefaultMutableTreeNode parent, String childName) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object userObject = child.getUserObject();
            if (userObject instanceof String && userObject.equals(childName)) {
                return child;
            }
        }
        // Not found, create new
        DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(childName);
        parent.add(newChild);
        return newChild;
    }

    // Helper classes to track series nodes with run information

    /**
     * Represents a leaf node that is a plottable series from a specific run.
     */
    private static class SeriesLeafNode {
        final String seriesName;
        final RunInfo runInfo;
        final boolean showSeriesName;  // If true, show "ds_1 [Run_1]", else just "Run_1"

        SeriesLeafNode(String seriesName, RunInfo runInfo, boolean showSeriesName) {
            this.seriesName = seriesName;
            this.runInfo = runInfo;
            this.showSeriesName = showSeriesName;
        }

        @Override
        public String toString() {
            if (showSeriesName) {
                // Standalone leaf: show "ds_1 [Run_1]"
                String lastSegment = seriesName.contains(".")
                    ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                    : seriesName;
                return lastSegment + " [" + runInfo.runName + "]";
            } else {
                // Child of parent node: just show "Run_1"
                return runInfo.runName;
            }
        }
    }

    /**
     * Represents a parent node for a series available in multiple runs.
     */
    private static class SeriesParentNode {
        final String seriesName;
        final List<RunInfo> runsWithSeries;

        SeriesParentNode(String seriesName, List<RunInfo> runsWithSeries) {
            this.seriesName = seriesName;
            this.runsWithSeries = runsWithSeries;
        }

        @Override
        public String toString() {
            // Extract just the last segment of the series name (e.g., "ds_1" from "node.node9.ds_1")
            String lastSegment = seriesName.contains(".")
                ? seriesName.substring(seriesName.lastIndexOf('.') + 1)
                : seriesName;

            String[] runLabels = runsWithSeries.stream()
                .map(r -> r.runName)
                .toArray(String[]::new);
            return lastSegment + " [" + String.join(", ", runLabels) + "]";
        }
    }

    /**
     * Creates a new TimeSeriesData with a different name but same data.
     */
    private TimeSeriesData renameTimeSeriesData(TimeSeriesData original, String newName) {
        // Convert timestamps back to LocalDateTime array
        long[] timestamps = original.getTimestamps();
        java.time.LocalDateTime[] dateTimes = new java.time.LocalDateTime[timestamps.length];
        for (int i = 0; i < timestamps.length; i++) {
            dateTimes[i] = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamps[i]),
                java.time.ZoneOffset.UTC
            );
        }

        // Create new TimeSeriesData with new name
        return new TimeSeriesData(newName, dateTimes, original.getValues());
    }

    /**
     * Handles selection changes in the outputs tree.
     * Supports recursive selection: selecting a parent node plots all its leaf children.
     * Fetches timeseries data for leaf nodes and updates plot and stats.
     */
    private void onOutputsTreeSelectionChanged(TreeSelectionEvent e) {
        TreePath[] selectedPaths = outputsTree.getSelectionPaths();
        if (selectedPaths == null || selectedPaths.length == 0) {
            // Clear plot and stats when nothing is selected
            clearPlotAndStats();
            selectedSeries.clear();
            return;
        }

        // Collect all leaf nodes recursively (parent selection = all children)
        List<SeriesLeafNode> allLeaves = new ArrayList<>();
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            collectLeafNodes(node, allLeaves);
        }

        // If no valid leaves found, clear everything
        if (allLeaves.isEmpty()) {
            clearPlotAndStats();
            selectedSeries.clear();
            return;
        }

        // Build new set of selected series (with unique keys: "seriesName [RunName]")
        Set<String> newSelectedSeries = new HashSet<>();
        Map<String, SeriesLeafNode> seriesKeyToLeaf = new HashMap<>();

        for (SeriesLeafNode leaf : allLeaves) {
            String seriesKey = leaf.seriesName + " [" + leaf.runInfo.runName + "]";
            newSelectedSeries.add(seriesKey);
            seriesKeyToLeaf.put(seriesKey, leaf);
        }

        // Determine which series to add and which to remove
        Set<String> seriesToAdd = new HashSet<>(newSelectedSeries);
        seriesToAdd.removeAll(selectedSeries);

        Set<String> seriesToRemove = new HashSet<>(selectedSeries);
        seriesToRemove.removeAll(newSelectedSeries);

        // Remove series that are no longer selected
        for (String seriesKey : seriesToRemove) {
            plotDataSet.removeSeries(seriesKey);
            seriesColorMap.remove(seriesKey);
            statsTableModel.removeSeries(seriesKey);
        }

        // Add new series
        for (String seriesKey : seriesToAdd) {
            SeriesLeafNode leaf = seriesKeyToLeaf.get(seriesKey);
            if (leaf == null) continue;

            String sessionKey = leaf.runInfo.session.getSessionKey();
            String seriesName = leaf.seriesName;

            // Assign consistent color to new series
            Color seriesColor = getColorForSeries(seriesKey);
            seriesColorMap.put(seriesKey, seriesColor);

            // Check if we already have this data cached
            TimeSeriesData cachedData = timeSeriesRequestManager.getTimeSeriesFromCache(sessionKey, seriesName);
            if (cachedData != null) {
                // Add immediately with cached data, using seriesKey as display name
                TimeSeriesData renamedData = renameTimeSeriesData(cachedData, seriesKey);
                addSeriesToPlot(renamedData, seriesColor);
                statsTableModel.addOrUpdateSeries(renamedData);
            } else if (!timeSeriesRequestManager.isRequestInProgress(sessionKey, seriesName)) {
                // Show loading state for this series
                statsTableModel.addLoadingSeries(seriesKey);

                // Request the timeseries data
                timeSeriesRequestManager.requestTimeSeries(sessionKey, seriesName)
                    .thenAccept(timeSeriesData -> {
                        SwingUtilities.invokeLater(() -> {
                            // Check if this series is still selected
                            if (selectedSeries.contains(seriesKey)) {
                                // Rename series to include run label
                                TimeSeriesData renamedData = renameTimeSeriesData(timeSeriesData, seriesKey);
                                addSeriesToPlot(renamedData, seriesColorMap.get(seriesKey));
                                statsTableModel.addOrUpdateSeries(renamedData);
                                plotPanel.zoomToFit();
                            }
                        });
                    })
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            if (selectedSeries.contains(seriesKey)) {
                                statsTableModel.addErrorSeries(seriesKey, throwable.getMessage());
                            }
                        });
                        return null;
                    });
            } else {
                // Request already in progress, show loading state
                statsTableModel.addLoadingSeries(seriesKey);
            }
        }

        // Update the selected series set
        selectedSeries = newSelectedSeries;

        // Update plot visibility and auto-zoom if we have any series
        updatePlotVisibility();
        if (!plotDataSet.isEmpty()) {
            plotPanel.zoomToFit();
        }
    }

    /**
     * Recursively collects all SeriesLeafNode objects from a tree node.
     * If the node is a leaf, adds it directly. If it's a parent, recursively collects from children.
     * This enables selecting a parent node (like "node.node9") to plot all its children.
     */
    private void collectLeafNodes(DefaultMutableTreeNode node, List<SeriesLeafNode> leaves) {
        if (node == null) return;

        Object userObject = node.getUserObject();

        // Check if this is a SeriesLeafNode (plottable leaf)
        if (userObject instanceof SeriesLeafNode) {
            leaves.add((SeriesLeafNode) userObject);
            return;
        }

        // Check if this is a SeriesParentNode (select all children)
        if (userObject instanceof SeriesParentNode) {
            SeriesParentNode parentNode = (SeriesParentNode) userObject;
            // Add all runs for this series
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
            return;
        }

        // For regular folder nodes (String user objects), recurse to all children
        if (!node.isLeaf()) {
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectLeafNodes(child, leaves);
            }
        }
    }

    /**
     * Get the full series name for a tree node by walking up the path
     */
    private String getFullSeriesName(DefaultMutableTreeNode node) {
        if (node == null) return null;

        List<String> pathParts = new ArrayList<>();
        TreeNode currentNode = node;

        while (currentNode != null && currentNode.getParent() != null) {
            if (currentNode instanceof DefaultMutableTreeNode) {
                String part = ((DefaultMutableTreeNode) currentNode).getUserObject().toString();
                pathParts.add(0, part); // Add to beginning to build path from root to leaf
            }
            currentNode = currentNode.getParent();
        }

        return pathParts.isEmpty() ? null : String.join(".", pathParts);
    }

    /**
     * Check if a node represents a special message (like "No outputs available")
     */
    private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
        String variableName = node.getUserObject().toString();
        return variableName.equals("No outputs available") ||
               variableName.equals("Outputs will appear when simulation completes");
    }

    /**
     * Get the currently selected run information
     */
    private RunInfo getSelectedRunInfo() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) {
            return null;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        Object userObject = selectedNode.getUserObject();

        if (userObject instanceof RunInfo) {
            return (RunInfo) userObject;
        }

        return null;
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
     * Selects the run associated with the given sessionKey.
     * This will expand the tree and select the run node if found.
     */
    private void selectRun(String sessionKey) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode runNode = sessionToTreeNode.get(sessionKey);
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
        String runName; // Made non-final to allow renaming
        final SessionManager.KalixSession session;

        RunInfo(String runName, SessionManager.KalixSession session) {
            this.runName = runName;
            this.session = session;
        }

        /**
         * Updates the run name. Used when renaming runs.
         */
        public void setRunName(String newName) {
            this.runName = newName;
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
     * Gets a consistent color for a series based on its name hash.
     * This ensures the same series always gets the same color.
     */
    private Color getColorForSeries(String seriesName) {
        int hash = Math.abs(seriesName.hashCode());
        return SERIES_COLORS[hash % SERIES_COLORS.length];
    }

    /**
     * Adds a series to the plot with the specified color.
     */
    private void addSeriesToPlot(TimeSeriesData timeSeriesData, Color seriesColor) {
        plotDataSet.addSeries(timeSeriesData);
        seriesColorMap.put(timeSeriesData.getName(), seriesColor);
        updatePlotVisibility();
    }

    /**
     * Updates the plot panel's color map and visible series list.
     */
    private void updatePlotVisibility() {
        plotPanel.setSeriesColors(new HashMap<>(seriesColorMap));
        plotPanel.setVisibleSeries(new ArrayList<>(selectedSeries));
    }

    /**
     * Clears the plot and resets stats table.
     */
    private void clearPlotAndStats() {
        plotDataSet.removeAllSeries();
        plotPanel.setVisibleSeries(new ArrayList<>());
        seriesColorMap.clear();
        statsTableModel.clear();
    }


    /**
     * Custom tree cell renderer for outputs tree.
     */
    private static class OutputsTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                String variableName = node.getUserObject().toString();

                // Only add icons to leaf nodes
                if (leaf && !isSpecialMessageNode(node)) {
                    int treeIconSize = 12; // Same size as run tree icons

                    // Determine icon based on the variable name
                    if (variableName.equals("dsflow") || variableName.equals("usflow") || variableName.matches("ds_\\d+")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.WATER, treeIconSize));
                    } else if (variableName.equals("storage") || variableName.equals("volume")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.GLASS_WHISKEY, treeIconSize));
                    } else if (variableName.equals("runoff_depth")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.TINT, treeIconSize));
                    } else if (variableName.equals("demand")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_CIRCLE_LEFT, treeIconSize));
                    } else if (variableName.equals("diversion")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_LEFT, treeIconSize));
                    } else if (variableName.equals("inflow") || variableName.equals("runoff_volume")) {
                        setIcon(FontIcon.of(FontAwesomeSolid.ARROW_ALT_CIRCLE_RIGHT, treeIconSize));
                    } else {
                        // Default icon for any other leaf nodes
                        setIcon(FontIcon.of(FontAwesomeSolid.WAVE_SQUARE, treeIconSize));
                    }
                } else {
                    // No icon for non-leaf nodes
                    setIcon(null);
                }
            }

            return this;
        }

        /**
         * Helper method to check if a node represents a special message (reused from parent class logic)
         */
        private boolean isSpecialMessageNode(DefaultMutableTreeNode node) {
            String variableName = node.getUserObject().toString();
            return variableName.equals("No outputs available") ||
                   variableName.equals("Outputs will appear when simulation completes");
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
                            setIcon(FontIcon.of(FontAwesomeSolid.ROCKET, treeIconSize));
                            break;
                        case DONE:
                            setIcon(FontIcon.of(FontAwesomeSolid.GRIP_HORIZONTAL, treeIconSize));
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

    /**
     * Table model for displaying statistics of multiple time series.
     */
    private static class StatsTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Series", "Min", "Max", "Mean", "Points"};
        private final List<SeriesStats> seriesData = new ArrayList<>();

        static class SeriesStats {
            String name;
            String min;
            String max;
            String mean;
            String points;
            SeriesStats(String name) {
                this.name = name;
                this.min = "";
                this.max = "";
                this.mean = "";
                this.points = "";
            }

            SeriesStats(String name, TimeSeriesData data) {
                this.name = name;
                if (data.getPointCount() == 0) {
                    this.min = "-";
                    this.max = "-";
                    this.mean = "-";
                    this.points = "0";
                } else {
                    this.min = String.format("%.3f", data.getMinValue());
                    this.max = String.format("%.3f", data.getMaxValue());
                    this.mean = String.format("%.3f", data.getMeanValue());
                    this.points = String.valueOf(data.getPointCount());
                }
            }
        }

        @Override
        public int getRowCount() {
            return seriesData.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= seriesData.size()) return "";

            SeriesStats stats = seriesData.get(rowIndex);
            switch (columnIndex) {
                case 0: return stats.name;
                case 1: return stats.min;
                case 2: return stats.max;
                case 3: return stats.mean;
                case 4: return stats.points;
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        public void addLoadingSeries(String seriesName) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            seriesData.add(new SeriesStats(seriesName));
            fireTableDataChanged();
        }

        public void addOrUpdateSeries(TimeSeriesData data) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(data.getName()));
            seriesData.add(new SeriesStats(data.getName(), data));
            fireTableDataChanged();
        }

        public void addErrorSeries(String seriesName, String errorMessage) {
            // Remove existing entry if present
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            SeriesStats errorStats = new SeriesStats(seriesName);
            errorStats.min = "Error";
            errorStats.max = "Error";
            errorStats.mean = "Error";
            errorStats.points = "Error";
            seriesData.add(errorStats);
            fireTableDataChanged();
        }

        public void removeSeries(String seriesName) {
            seriesData.removeIf(stats -> stats.name.equals(seriesName));
            fireTableDataChanged();
        }

        public void clear() {
            seriesData.clear();
            fireTableDataChanged();
        }
    }
}