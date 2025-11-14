package com.kalix.ide.windows;

import com.kalix.ide.cli.AbstractSessionProgram;
import com.kalix.ide.cli.JsonStdioProtocol;
import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.utils.JsonUtils;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Advanced window for viewing all KalixCLI sessions with their STDIO communication logs.
 * This is a developer/debugging tool showing raw session state and messages.
 */
public class SessionManagerWindow extends JFrame {

    private static SessionManagerWindow instance;

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;

    // UI Components
    private JTree sessionTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;

    // Details panel components
    private JLabel kalixcliUidLabel;
    private JLabel sessionKeyLabel;
    private JLabel sessionStateLabel;
    private JLabel programTypeLabel;
    private JLabel programStateLabel;
    private JLabel startTimeLabel;
    private JLabel uptimeLabel;

    // STDIO Log components
    private RSyntaxTextArea logArea;
    private RTextScrollPane logScrollPane;
    private String lastLogContent = "";

    // Session tracking
    private final Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private String selectedSessionKey = null;

    // Update timer
    private Timer updateTimer;

    // Flag to prevent selection listener from firing during programmatic updates
    private boolean isUpdatingSelection = false;

    /**
     * Private constructor for singleton pattern.
     */
    private SessionManagerWindow(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupUpdateTimer();
    }

    /**
     * Shows the Session Manager window using singleton pattern.
     */
    public static void showSessionManagerWindow(JFrame parentFrame, StdioTaskManager stdioTaskManager, Consumer<String> statusUpdater) {
        if (instance == null) {
            instance = new SessionManagerWindow(parentFrame, stdioTaskManager, statusUpdater);
        }

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
        instance.refreshSessions();
    }

    /**
     * Shows the Session Manager window and selects a specific session.
     *
     * @param parentFrame the parent frame
     * @param stdioTaskManager the task manager
     * @param statusUpdater status update callback
     * @param sessionKey the session key to select
     */
    public static void showSessionManagerWindow(JFrame parentFrame, StdioTaskManager stdioTaskManager,
                                                 Consumer<String> statusUpdater, String sessionKey) {
        // First show the window normally
        showSessionManagerWindow(parentFrame, stdioTaskManager, statusUpdater);

        // Then select the specific session
        if (instance != null && sessionKey != null) {
            instance.selectSession(sessionKey);
        }
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("KalixCLI Session Manager");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(1000, 700);

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 40, parentLocation.y + 40);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // Initialize session tree
        rootNode = new DefaultMutableTreeNode("Sessions");
        treeModel = new DefaultTreeModel(rootNode);
        sessionTree = new JTree(treeModel);
        sessionTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        sessionTree.setRootVisible(false);
        sessionTree.setShowsRootHandles(true);
        sessionTree.setCellRenderer(new SessionTreeCellRenderer());

        // Add selection listener
        sessionTree.addTreeSelectionListener(e -> {
            if (!isUpdatingSelection) {
                onSessionSelectionChanged();
            }
        });

        // Initialize details panel labels
        kalixcliUidLabel = new JLabel("-");
        sessionKeyLabel = new JLabel("-");
        sessionStateLabel = new JLabel("-");
        programTypeLabel = new JLabel("-");
        programStateLabel = new JLabel("-");
        startTimeLabel = new JLabel("-");
        uptimeLabel = new JLabel("-");

        // Initialize STDIO log area
        logArea = new RSyntaxTextArea();
        logArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIConstants.StdioLog.FONT_SIZE));
        logArea.setBackground(Color.WHITE);
        logArea.setCodeFoldingEnabled(false);
        logArea.setText("Select a session to view STDIO log...");

        logScrollPane = new RTextScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create main split pane (left: sessions tree, right: details + log)
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setDividerLocation(250);
        mainSplitPane.setResizeWeight(0);

        // Left panel: Sessions tree
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Sessions"));
        JScrollPane treeScrollPane = new JScrollPane(sessionTree);
        leftPanel.add(treeScrollPane, BorderLayout.CENTER);

        // Right panel: vertical split (details top, log bottom)
        JSplitPane rightSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplitPane.setDividerLocation(180);
        rightSplitPane.setResizeWeight(0);

        // Top of right panel: Details
        JPanel detailsPanel = createDetailsPanel();
        rightSplitPane.setTopComponent(detailsPanel);

        // Bottom of right panel: STDIO Log
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("STDIO Log"));
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        rightSplitPane.setBottomComponent(logPanel);

        // Assemble main split pane
        mainSplitPane.setLeftComponent(leftPanel);
        mainSplitPane.setRightComponent(rightSplitPane);

        add(mainSplitPane, BorderLayout.CENTER);

        // Footer with action buttons
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton pingButton = new JButton("Ping");
        pingButton.addActionListener(e -> pingSession());
        footerPanel.add(pingButton);

        JButton terminateButton = new JButton("Terminate");
        terminateButton.addActionListener(e -> terminateSession());
        footerPanel.add(terminateButton);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> forceRefresh());
        footerPanel.add(refreshButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);

        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        add(footerPanel, BorderLayout.SOUTH);
    }

    /**
     * Creates the session details panel.
     */
    private JPanel createDetailsPanel() {
        JPanel detailsPanel = new JPanel(new GridBagLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Session Details"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 10, 3, 10);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Kalixcli UID
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Kalixcli UID:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(kalixcliUidLabel, gbc);
        row++;

        // Session Key
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Session Key:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(sessionKeyLabel, gbc);
        row++;

        // Session State
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Session State:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(sessionStateLabel, gbc);
        row++;

        // Program Type
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Program Type:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(programTypeLabel, gbc);
        row++;

        // Program State
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Program State:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(programStateLabel, gbc);
        row++;

        // Start Time
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Start Time:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(startTimeLabel, gbc);
        row++;

        // Uptime
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0;
        detailsPanel.add(new JLabel("Uptime:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        detailsPanel.add(uptimeLabel, gbc);

        return detailsPanel;
    }

    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                if (updateTimer != null) {
                    updateTimer.start();
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                if (updateTimer != null) {
                    updateTimer.stop();
                }
                instance = null;
            }

            @Override
            public void windowIconified(WindowEvent e) {
                if (updateTimer != null) {
                    updateTimer.stop();
                }
            }

            @Override
            public void windowDeiconified(WindowEvent e) {
                if (updateTimer != null) {
                    updateTimer.start();
                }
            }

            @Override
            public void windowActivated(WindowEvent e) {
                refreshSessions();
            }
        });
    }

    private void setupUpdateTimer() {
        updateTimer = new Timer(2000, e -> refreshSessions());
        updateTimer.setRepeats(true);
    }

    /**
     * Refreshes the session list and updates displays.
     */
    private void refreshSessions() {
        if (stdioTaskManager == null) return;

        SwingUtilities.invokeLater(() -> {
            Map<String, SessionManager.KalixSession> activeSessions = stdioTaskManager.getActiveSessions();
            boolean[] treeStructureChanged = {false};

            // Add new sessions to tree
            for (SessionManager.KalixSession session : activeSessions.values()) {
                String sessionKey = session.getSessionKey();

                if (!sessionToTreeNode.containsKey(sessionKey)) {
                    // New session - add to tree
                    SessionInfo sessionInfo = new SessionInfo(session);
                    DefaultMutableTreeNode sessionNode = new DefaultMutableTreeNode(sessionInfo);
                    rootNode.add(sessionNode);
                    sessionToTreeNode.put(sessionKey, sessionNode);
                    treeStructureChanged[0] = true;
                } else {
                    // Existing session - update node display
                    DefaultMutableTreeNode existingNode = sessionToTreeNode.get(sessionKey);
                    treeModel.nodeChanged(existingNode);
                }
            }

            // Remove terminated sessions from tree
            sessionToTreeNode.entrySet().removeIf(entry -> {
                String sessionKey = entry.getKey();
                if (!activeSessions.containsKey(sessionKey)) {
                    DefaultMutableTreeNode nodeToRemove = entry.getValue();
                    rootNode.remove(nodeToRemove);
                    treeStructureChanged[0] = true;

                    // Clear selection if this was the selected session
                    if (sessionKey.equals(selectedSessionKey)) {
                        clearDetailsPanel();
                        selectedSessionKey = null;
                    }
                    return true;
                }
                return false;
            });

            // Notify tree of changes
            if (treeStructureChanged[0]) {
                treeModel.nodeStructureChanged(rootNode);
            }

            // Update details and log for selected session
            if (selectedSessionKey != null && activeSessions.containsKey(selectedSessionKey)) {
                updateDetailsPanel(activeSessions.get(selectedSessionKey));
                updateLog(activeSessions.get(selectedSessionKey));
            }
        });
    }

    /**
     * Handles session tree selection changes.
     */
    private void onSessionSelectionChanged() {
        TreePath selectedPath = sessionTree.getSelectionPath();
        if (selectedPath == null) {
            clearDetailsPanel();
            selectedSessionKey = null;
            return;
        }

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof SessionInfo sessionInfo)) {
            clearDetailsPanel();
            selectedSessionKey = null;
            return;
        }

        selectedSessionKey = sessionInfo.session.getSessionKey();

        updateDetailsPanel(sessionInfo.session);
        updateLog(sessionInfo.session);
    }

    /**
     * Updates the details panel with session information.
     */
    private void updateDetailsPanel(SessionManager.KalixSession session) {
        if (session == null) {
            clearDetailsPanel();
            return;
        }

        String uid = session.getKalixcliUid() != null ? session.getKalixcliUid() : "unknown";
        kalixcliUidLabel.setText(uid);

        sessionKeyLabel.setText(session.getSessionKey());

        sessionStateLabel.setText(session.getState() != null ? session.getState().toString() : "Unknown");

        AbstractSessionProgram program = session.getActiveProgram();
        String programType = getProgramType(program);
        programTypeLabel.setText(programType);

        String programState = program != null ? program.getStateDescription() : "N/A";
        programStateLabel.setText(programState);

        startTimeLabel.setText(session.getStartTime() != null ? session.getStartTime().toString() : "Unknown");

        uptimeLabel.setText(formatUptime(session));
    }

    /**
     * Clears the details panel.
     */
    private void clearDetailsPanel() {
        kalixcliUidLabel.setText("-");
        sessionKeyLabel.setText("-");
        sessionStateLabel.setText("-");
        programTypeLabel.setText("-");
        programStateLabel.setText("-");
        startTimeLabel.setText("-");
        uptimeLabel.setText("-");
        logArea.setText("Select a session to view STDIO log...");
        lastLogContent = "";
    }

    /**
     * Updates the STDIO log for the selected session.
     */
    private void updateLog(SessionManager.KalixSession session) {
        if (session == null) {
            logArea.setText("Select a session to view STDIO log...");
            lastLogContent = "";
            return;
        }

        String logContent = session.getCommunicationLog() != null
            ? session.getCommunicationLog().getFormattedLog()
            : UIConstants.StdioLog.NO_LOG_MESSAGE;

        if (!logContent.equals(lastLogContent)) {
            updateLogWithContent(logContent);
        }
    }

    /**
     * Updates the log with content, implementing smart auto-scroll.
     */
    private void updateLogWithContent(String logContent) {
        boolean shouldAutoScroll = isUserAtBottom();

        logArea.setText(logContent);
        lastLogContent = logContent;

        if (shouldAutoScroll) {
            SwingUtilities.invokeLater(this::scrollToBottom);
        }
    }

    /**
     * Checks if the user is at the bottom of the log.
     */
    private boolean isUserAtBottom() {
        if (logScrollPane == null || logArea == null) {
            return true;
        }

        JScrollBar verticalScrollBar = logScrollPane.getVerticalScrollBar();
        if (verticalScrollBar == null) {
            return true;
        }

        int currentValue = verticalScrollBar.getValue();
        int maximum = verticalScrollBar.getMaximum();
        int extent = verticalScrollBar.getVisibleAmount();
        int threshold = UIConstants.StdioLog.SCROLL_THRESHOLD_PIXELS;

        return (currentValue + extent + threshold) >= maximum;
    }

    /**
     * Scrolls to the bottom of the log.
     */
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /**
     * Forces an immediate refresh.
     */
    private void forceRefresh() {
        refreshSessions();
        statusUpdater.accept("Session list refreshed");
    }

    /**
     * Selects a specific session in the tree by session key.
     * If the session is not found, does nothing.
     *
     * @param sessionKey the session key to select
     */
    private void selectSession(String sessionKey) {
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode sessionNode = sessionToTreeNode.get(sessionKey);
            if (sessionNode != null) {
                TreePath pathToSession = new TreePath(sessionNode.getPath());

                // Select the session
                isUpdatingSelection = true;
                sessionTree.setSelectionPath(pathToSession);
                isUpdatingSelection = false;

                // Scroll to make the selection visible
                sessionTree.scrollPathToVisible(pathToSession);

                // Update details panel
                SessionInfo sessionInfo = (SessionInfo) sessionNode.getUserObject();
                updateDetailsPanel(sessionInfo.session);
                updateLog(sessionInfo.session);
                selectedSessionKey = sessionKey;
            }
        });
    }

    /**
     * Sends a ping (echo) command to the selected session.
     */
    private void pingSession() {
        if (selectedSessionKey == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a session first.",
                "No Session Selected",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        try {
            // Generate initial JSON command
            Map<String, Object> parameters = Map.of("string", "Ping from Session Manager at " + java.time.LocalDateTime.now());
            String initialJsonCommand = JsonStdioProtocol.createCommandMessage("echo", parameters);

            // Show dialog with editable JSON
            JTextArea textArea = new JTextArea(UIConstants.StdioLog.DIALOG_ROWS, UIConstants.StdioLog.DIALOG_COLS);
            textArea.setText(initialJsonCommand);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIConstants.StdioLog.FONT_SIZE));
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);

            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

            int result = JOptionPane.showConfirmDialog(this,
                scrollPane,
                "Edit JSON Command",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

            if (result == JOptionPane.OK_OPTION) {
                String editedJson = textArea.getText().trim();
                String flattenedJson = JsonUtils.flattenJson(editedJson);

                stdioTaskManager.sendCommand(selectedSessionKey, flattenedJson);
                statusUpdater.accept("Ping command sent to session");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Failed to send ping command: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Terminates the selected session.
     */
    private void terminateSession() {
        if (selectedSessionKey == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a session first.",
                "No Session Selected",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to terminate this session?\n\nSession: " + selectedSessionKey,
            "Terminate Session",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            stdioTaskManager.terminateSession(selectedSessionKey)
                .thenRun(() -> SwingUtilities.invokeLater(() -> {
                    statusUpdater.accept("Session terminated");
                    refreshSessions();
                }))
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Failed to terminate session: " + throwable.getMessage());
                        JOptionPane.showMessageDialog(this,
                            "Failed to terminate session: " + throwable.getMessage(),
                            "Termination Error",
                            JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
        }
    }

    /**
     * Formats uptime for a session.
     * Shows final uptime with "(stopped)" suffix if process has terminated.
     */
    private static String formatUptime(SessionManager.KalixSession session) {
        if (session == null || session.getStartTime() == null) {
            return "00:00:00";
        }

        long startMs = session.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMs;
        boolean isStopped = false;

        // If process has stopped, freeze the uptime at the last activity time
        if (!session.getProcess().isRunning()) {
            endMs = session.getLastActivity() != null
                ? session.getLastActivity().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis();
            isStopped = true;
        } else {
            // Process is still running, use current time
            endMs = System.currentTimeMillis();
        }

        long durationMs = endMs - startMs;
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        String formattedTime = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);

        return isStopped ? formattedTime + " (stopped)" : formattedTime;
    }

    /**
     * Gets the program type string.
     */
    private static String getProgramType(AbstractSessionProgram program) {
        if (program instanceof RunModelProgram) {
            return "RunModel";
        } else if (program instanceof OptimisationProgram) {
            return "Optimisation";
        } else if (program == null) {
            return "None";
        } else {
            return program.getClass().getSimpleName();
        }
    }

    /**
     * Data class for session display information.
     */
    private static class SessionInfo {
        final SessionManager.KalixSession session;

        SessionInfo(SessionManager.KalixSession session) {
            this.session = session;
        }

        String getDisplayName() {
            String uid = session.getKalixcliUid() != null
                ? session.getKalixcliUid()
                : session.getSessionKey().substring(0, 8);
            return uid + " [" + getProgramType(session.getActiveProgram()) + "]";
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }

    /**
     * Custom tree cell renderer for session tree.
     */
    private static class SessionTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();

                if (userObject instanceof SessionInfo sessionInfo) {
                    SessionManager.KalixSession session = sessionInfo.session;
                    SessionManager.SessionState state = session.getState();

                    setText(sessionInfo.getDisplayName());

                    // Color code by session state
                    if (!sel) {
                        switch (state) {
                            case READY:
                                setForeground(new Color(0, 120, 0)); // Dark green
                                break;
                            case RUNNING:
                                setForeground(new Color(0, 0, 200)); // Blue
                                break;
                            case ERROR:
                                setForeground(new Color(200, 0, 0)); // Red
                                break;
                            case STARTING:
                                setForeground(new Color(150, 150, 0)); // Dark yellow
                                break;
                            case TERMINATED:
                                setForeground(new Color(128, 128, 128)); // Gray
                                break;
                            default:
                                setForeground(Color.BLACK);
                                break;
                        }
                    }

                    // Set icon based on session state
                    int iconSize = 12;
                    switch (state) {
                        case STARTING:
                            setIcon(FontIcon.of(FontAwesomeSolid.SUN, iconSize));
                            break;
                        case RUNNING:
                            setIcon(FontIcon.of(FontAwesomeSolid.ROCKET, iconSize));
                            break;
                        case READY:
                            setIcon(FontIcon.of(FontAwesomeSolid.GRIP_HORIZONTAL, iconSize));
                            break;
                        case ERROR:
                            setIcon(FontIcon.of(FontAwesomeSolid.BUG, iconSize));
                            break;
                        case TERMINATED:
                            setIcon(FontIcon.of(FontAwesomeSolid.STOP_CIRCLE, iconSize));
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
