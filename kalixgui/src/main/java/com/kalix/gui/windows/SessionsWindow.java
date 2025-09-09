package com.kalix.gui.windows;

import com.kalix.gui.managers.CliTaskManager;
import com.kalix.gui.cli.SessionManager;
import com.kalix.gui.cli.SessionCommunicationLog;
import com.kalix.gui.utils.DialogUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Separate window for managing CLI sessions.
 * Provides a dedicated interface for monitoring active sessions and requesting results.
 */
public class SessionsWindow extends JFrame {
    
    private final CliTaskManager cliTaskManager;
    private final Consumer<String> statusUpdater;
    private Timer sessionUpdateTimer;
    private static SessionsWindow instance;
    
    // UI Components
    private DefaultListModel<SessionManager.KalixSession> sessionListModel;
    private JList<SessionManager.KalixSession> sessionList;
    private JPanel detailsPanel;
    private JLabel sessionIdLabel;
    private JLabel sessionTypeLabel;
    private JLabel sessionStateLabel;
    private JLabel sessionStartTimeLabel;
    private JButton terminateButton;
    private JButton removeFromListButton;
    private JTextArea communicationLogArea;
    private JScrollPane communicationLogScrollPane;
    private String lastLogContent = ""; // Track last communication log content
    
    /**
     * Private constructor for singleton pattern.
     * 
     * @param parentFrame parent frame for positioning
     * @param cliTaskManager CLI task manager for session operations
     * @param statusUpdater callback for status updates
     */
    private SessionsWindow(JFrame parentFrame, CliTaskManager cliTaskManager, Consumer<String> statusUpdater) {
        this.cliTaskManager = cliTaskManager;
        this.statusUpdater = statusUpdater;
        
        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
    }
    
    /**
     * Creates or shows the sessions window.
     * Uses singleton pattern to ensure only one window exists.
     * 
     * @param parentFrame parent frame for positioning
     * @param cliTaskManager CLI task manager for session operations
     * @param statusUpdater callback for status updates
     */
    public static void showSessionsWindow(JFrame parentFrame, CliTaskManager cliTaskManager, Consumer<String> statusUpdater) {
        if (instance == null) {
            instance = new SessionsWindow(parentFrame, cliTaskManager, statusUpdater);
        }
        
        // Bring window to front and make visible
        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }
    
    /**
     * Checks if the sessions window is currently open.
     * 
     * @return true if window exists and is visible
     */
    public static boolean isWindowOpen() {
        return instance != null && instance.isVisible();
    }
    
    /**
     * Sets up basic window properties.
     */
    private void setupWindow(JFrame parentFrame) {
        setTitle("CLI Sessions");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(700, 500);
        
        // Position relative to parent window
        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            // Offset slightly so it doesn't completely overlap
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 50, parentLocation.y + 50);
        } else {
            setLocationRelativeTo(null);
        }
        
        // Set window icon (same as parent if available)
        if (parentFrame != null && parentFrame.getIconImage() != null) {
            setIconImage(parentFrame.getIconImage());
        }
    }
    
    /**
     * Initialize UI components.
     */
    private void initializeComponents() {
        // Initialize session list
        sessionListModel = new DefaultListModel<>();
        sessionList = new JList<>(sessionListModel);
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.setCellRenderer(new SessionListCellRenderer());
        sessionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                // Reset log content tracking when session changes
                lastLogContent = "";
                updateDetailsPanel();
                // Update communication log when user actually selects a different session
                forceUpdateCommunicationLog();
            }
        });
        
        // Initialize details panel components
        sessionIdLabel = new JLabel();
        sessionTypeLabel = new JLabel();
        sessionStateLabel = new JLabel();
        sessionStartTimeLabel = new JLabel();
        terminateButton = new JButton("Terminate Session");
        terminateButton.setEnabled(false);
        terminateButton.addActionListener(this::terminateSelectedSession);
        
        removeFromListButton = new JButton("Remove from List");
        removeFromListButton.setEnabled(false);
        removeFromListButton.addActionListener(this::removeSelectedSession);
        
        // Initialize communication log components
        communicationLogArea = new JTextArea();
        communicationLogArea.setEditable(false);
        communicationLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        communicationLogArea.setBackground(Color.WHITE);
        communicationLogScrollPane = new JScrollPane(communicationLogArea);
        communicationLogScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        communicationLogScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        
        createDetailsPanel();
    }
    
    /**
     * Creates the session details panel.
     */
    private void createDetailsPanel() {
        detailsPanel = new JPanel(new CardLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Session Details"));
        
        // Create "no selection" panel
        JPanel noSelectionPanel = new JPanel(new BorderLayout());
        JLabel noSelectionLabel = new JLabel("Select a session to view details", SwingConstants.CENTER);
        noSelectionLabel.setForeground(Color.GRAY);
        noSelectionPanel.add(noSelectionLabel, BorderLayout.CENTER);
        
        // Create "session selected" panel with tabbed pane
        JPanel sessionSelectedPanel = new JPanel(new BorderLayout());
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // === Session Info Tab ===
        JPanel infoTabPanel = new JPanel(new BorderLayout());
        
        // Info panel for session details
        JPanel infoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;
        
        // Add session info fields
        gbc.gridx = 0; gbc.gridy = 0;
        infoPanel.add(new JLabel("Session ID:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(sessionIdLabel, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        infoPanel.add(new JLabel("Type:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(sessionTypeLabel, gbc);
        
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        infoPanel.add(new JLabel("State:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(sessionStateLabel, gbc);
        
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        infoPanel.add(new JLabel("Start Time:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        infoPanel.add(sessionStartTimeLabel, gbc);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.add(terminateButton);
        buttonPanel.add(removeFromListButton);
        
        // Assemble info tab
        infoTabPanel.add(infoPanel, BorderLayout.CENTER);
        infoTabPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        // === Communication Log Tab ===
        JPanel logTabPanel = new JPanel(new BorderLayout());
        
        // Add header for communication log
        JPanel logHeaderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel logHeaderLabel = new JLabel("Raw communication log (newest at bottom):");
        logHeaderLabel.setFont(logHeaderLabel.getFont().deriveFont(Font.BOLD));
        logHeaderPanel.add(logHeaderLabel);
        
        // Add refresh button for communication log
        JButton refreshLogButton = new JButton("Refresh");
        refreshLogButton.addActionListener(e -> forceUpdateCommunicationLog());
        logHeaderPanel.add(refreshLogButton);
        
        // Add clear button for communication log
        JButton clearLogButton = new JButton("Clear");
        clearLogButton.addActionListener(e -> clearCommunicationLog());
        logHeaderPanel.add(clearLogButton);
        
        logTabPanel.add(logHeaderPanel, BorderLayout.NORTH);
        logTabPanel.add(communicationLogScrollPane, BorderLayout.CENTER);
        
        // Add tabs to tabbed pane
        tabbedPane.addTab("Session Info", infoTabPanel);
        tabbedPane.addTab("Communication Log", logTabPanel);
        
        // Assemble session selected panel
        sessionSelectedPanel.add(tabbedPane, BorderLayout.CENTER);
        
        // Add panels to card layout
        detailsPanel.add(noSelectionPanel, "NO_SELECTION");
        detailsPanel.add(sessionSelectedPanel, "SESSION_SELECTED");
        
        // Initially show no selection panel
        CardLayout cl = (CardLayout) detailsPanel.getLayout();
        cl.show(detailsPanel, "NO_SELECTION");
    }
    
    /**
     * Sets up the window layout.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Add header panel
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel headerLabel = new JLabel("Active CLI Sessions");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(headerLabel);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        
        add(headerPanel, BorderLayout.NORTH);
        
        // Create split pane with session list on left and details on right
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(250);
        splitPane.setResizeWeight(0.4);
        
        // Left side: session list
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Sessions"));
        JScrollPane listScrollPane = new JScrollPane(sessionList);
        leftPanel.add(listScrollPane, BorderLayout.CENTER);
        
        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(detailsPanel);
        
        add(splitPane, BorderLayout.CENTER);
        
        // Add footer with close button
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);
        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        
        add(footerPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Sets up window event listeners.
     */
    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                // Start timer when window opens
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.start();
                }
            }
            
            @Override
            public void windowClosed(WindowEvent e) {
                // Stop timer when window closes
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.stop();
                }
                // Clear instance reference
                instance = null;
            }
            
            @Override
            public void windowIconified(WindowEvent e) {
                // Stop timer when minimized to save resources
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.stop();
                }
            }
            
            @Override
            public void windowDeiconified(WindowEvent e) {
                // Restart timer when restored
                if (sessionUpdateTimer != null) {
                    sessionUpdateTimer.start();
                }
            }
            
            @Override
            public void windowActivated(WindowEvent e) {
                // Immediate update when window becomes active
                if (cliTaskManager != null) {
                    updateSessionsList();
                }
            }
        });
    }
    
    /**
     * Gets the current number of active sessions.
     * 
     * @return number of active sessions, or 0 if no CLI task manager
     */
    public int getActiveSessionCount() {
        if (cliTaskManager != null) {
            return cliTaskManager.getActiveSessions().size();
        }
        return 0;
    }
    
    /**
     * Manually refreshes the session display.
     * Useful for immediate updates when sessions change.
     */
    public void refreshSessions() {
        if (cliTaskManager != null) {
            SwingUtilities.invokeLater(this::updateSessionsList);
        }
    }
    
    /**
     * Static method to refresh the sessions window if it's open.
     * This is useful for triggering updates from external code.
     */
    public static void refreshSessionsWindowIfOpen() {
        if (instance != null) {
            instance.refreshSessions();
        }
    }
    
    // ========================
    // Private helper methods
    // ========================
    
    /**
     * Updates the sessions list with current active sessions.
     */
    private void updateSessionsList() {
        Map<String, SessionManager.KalixSession> activeSessions = cliTaskManager.getActiveSessions();
        
        // Remember currently selected session
        SessionManager.KalixSession selectedSession = sessionList.getSelectedValue();
        
        // Update the list model
        sessionListModel.clear();
        for (SessionManager.KalixSession session : activeSessions.values()) {
            sessionListModel.addElement(session);
        }
        
        // Try to restore selection if the session still exists
        if (selectedSession != null) {
            for (int i = 0; i < sessionListModel.size(); i++) {
                SessionManager.KalixSession session = sessionListModel.getElementAt(i);
                if (session.getSessionId().equals(selectedSession.getSessionId())) {
                    sessionList.setSelectedIndex(i);
                    break;
                }
            }
        }
        
        // If no selection and there are sessions, don't auto-select
        // Let user explicitly select
        updateDetailsPanel();
    }
    
    /**
     * Updates the details panel based on the selected session.
     */
    private void updateDetailsPanel() {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        CardLayout cl = (CardLayout) detailsPanel.getLayout();
        
        if (selected == null) {
            // No selection - show "select a session" message
            cl.show(detailsPanel, "NO_SELECTION");
            terminateButton.setEnabled(false);
            removeFromListButton.setEnabled(false);
        } else {
            // Session selected - show details
            cl.show(detailsPanel, "SESSION_SELECTED");
            
            // Update labels
            sessionIdLabel.setText(selected.getSessionId());
            sessionTypeLabel.setText("Model Run");
            sessionStateLabel.setText(selected.getState().toString());
            sessionStartTimeLabel.setText(selected.getStartTime().toString());
            
            // Enable/disable buttons based on session state
            terminateButton.setEnabled(selected.isActive());
            removeFromListButton.setEnabled(!selected.isActive()); // Only allow removal of terminated/error sessions
            
            // Note: Communication log is NOT updated here to prevent timer-driven focus stealing
            // It will be updated when user switches sessions or clicks refresh
        }
        
        detailsPanel.revalidate();
        detailsPanel.repaint();
    }
    
    /**
     * Terminates the currently selected session.
     */
    private void terminateSelectedSession(java.awt.event.ActionEvent e) {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        if (selected != null) {
            String sessionId = selected.getSessionId();
            
            if (DialogUtils.showConfirmation(this, 
                    "Are you sure you want to terminate session " + sessionId + "?\n\nThe session will remain visible in the list but the kalixcli process will be closed.", 
                    "Terminate Session")) {
                cliTaskManager.terminateSession(sessionId)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Session terminated: " + sessionId);
                        updateSessionsList();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to terminate session: " + throwable.getMessage());
                            DialogUtils.showError(this, 
                                "Failed to terminate session: " + throwable.getMessage(),
                                "Termination Error");
                        });
                        return null;
                    });
            }
        }
    }
    
    /**
     * Removes the currently selected session from the list.
     * Only works for terminated or error sessions.
     */
    private void removeSelectedSession(java.awt.event.ActionEvent e) {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        if (selected != null) {
            String sessionId = selected.getSessionId();
            
            if (DialogUtils.showConfirmation(this, 
                    "Are you sure you want to remove session " + sessionId + " from the list?\n\nThis will permanently remove it from the Sessions window.", 
                    "Remove Session")) {
                cliTaskManager.removeSession(sessionId)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Session removed from list: " + sessionId);
                        updateSessionsList();
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            statusUpdater.accept("Failed to remove session: " + throwable.getMessage());
                            DialogUtils.showError(this, 
                                "Failed to remove session: " + throwable.getMessage(),
                                "Remove Session Error");
                        });
                        return null;
                    });
            }
        }
    }
    
    // ========================
    // Inner classes
    // ========================
    
    /**
     * Custom list cell renderer for session list.
     */
    private static class SessionListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof SessionManager.KalixSession) {
                SessionManager.KalixSession session = (SessionManager.KalixSession) value;
                String displayText = String.format("%s (%s)", 
                    session.getSessionId(), 
                    session.getState().toString());
                setText(displayText);
                
                // Color code by state
                if (!isSelected) {
                    switch (session.getState()) {
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
                        default:
                            setForeground(Color.BLACK);
                            break;
                    }
                }
            }
            
            return this;
        }
    }
    
    /**
     * Updates the communication log only if the content has actually changed.
     * This prevents unnecessary updates and scroll disruption.
     */
    private void updateCommunicationLogIfChanged() {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        if (selected != null && selected.getCommunicationLog() != null) {
            String logContent = selected.getCommunicationLog().getFormattedLog();
            
            // Only update if content has actually changed
            if (!logContent.equals(lastLogContent)) {
                updateCommunicationLogWithContent(logContent);
            }
        } else {
            String noLogMessage = "No communication log available for this session.";
            if (!noLogMessage.equals(lastLogContent)) {
                updateCommunicationLogWithContent(noLogMessage);
            }
        }
    }
    
    /**
     * Forces an update of the communication log regardless of content changes.
     * Used for manual refresh actions.
     */
    private void forceUpdateCommunicationLog() {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        if (selected != null && selected.getCommunicationLog() != null) {
            String logContent = selected.getCommunicationLog().getFormattedLog();
            updateCommunicationLogWithContent(logContent);
        } else {
            updateCommunicationLogWithContent("No communication log available for this session.");
        }
    }
    
    /**
     * Updates the communication log with the given content, implementing smart auto-scroll.
     */
    private void updateCommunicationLogWithContent(String logContent) {
        // Check if user is at or near the bottom before updating
        boolean shouldAutoScroll = isUserAtBottom();
        
        communicationLogArea.setText(logContent);
        lastLogContent = logContent; // Remember this content
        
        // Only auto-scroll if user was already at the bottom
        if (shouldAutoScroll) {
            SwingUtilities.invokeLater(() -> {
                communicationLogArea.setCaretPosition(communicationLogArea.getDocument().getLength());
            });
        }
    }
    
    /**
     * Checks if the user is currently at or near the bottom of the communication log.
     * This is used to determine if auto-scrolling should occur when the log updates.
     * 
     * @return true if the user is at the bottom and should see auto-scroll, false otherwise
     */
    private boolean isUserAtBottom() {
        if (communicationLogScrollPane == null || communicationLogArea == null) {
            return true; // Default to auto-scroll if components aren't ready
        }
        
        JScrollBar verticalScrollBar = communicationLogScrollPane.getVerticalScrollBar();
        if (verticalScrollBar == null) {
            return true; // Default to auto-scroll if no scroll bar
        }
        
        // Check if user is within a small threshold of the bottom
        // This accounts for slight rounding errors and gives a bit of tolerance
        int currentValue = verticalScrollBar.getValue();
        int maximum = verticalScrollBar.getMaximum();
        int extent = verticalScrollBar.getVisibleAmount();
        int threshold = 10; // pixels of tolerance
        
        // User is considered "at bottom" if they're within threshold pixels of the actual bottom
        return (currentValue + extent + threshold) >= maximum;
    }
    
    /**
     * Clears the communication log for the currently selected session.
     */
    private void clearCommunicationLog() {
        SessionManager.KalixSession selected = sessionList.getSelectedValue();
        if (selected != null && selected.getCommunicationLog() != null) {
            if (DialogUtils.showConfirmation(this, 
                    "Are you sure you want to clear the communication log for session " + selected.getSessionId() + "?", 
                    "Clear Communication Log")) {
                selected.getCommunicationLog().clear();
                forceUpdateCommunicationLog();
                statusUpdater.accept("Communication log cleared for session: " + selected.getSessionId());
            }
        }
    }
}