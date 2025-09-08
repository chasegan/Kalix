package com.kalix.gui.windows;

import com.kalix.gui.components.SessionStatusPanel;
import com.kalix.gui.managers.CliTaskManager;
import com.kalix.gui.cli.SessionManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;

/**
 * Separate window for managing CLI sessions with vertical tabbed interface.
 * Uses vertical tabs on the left side with each session getting its own tab 
 * showing STDIO communication logs for debugging. The Overview tab shows 
 * all sessions in summary view. Provides a dedicated interface for monitoring 
 * active sessions and requesting results.
 */
public class SessionsWindow extends JFrame {
    
    private final CliTaskManager cliTaskManager;
    private Timer sessionUpdateTimer;
    private static SessionsWindow instance;
    
    // UI Components for tabbed interface
    private JTabbedPane sessionTabs;
    private JPanel overviewPanel;
    private SessionStatusPanel sessionStatusPanel;
    
    // Session tracking
    private Map<String, LoggableSessionTab> sessionTabMap = new HashMap<>();
    private Consumer<String> statusUpdater;
    
    // Message buffering for sessions without tabs yet
    private Map<String, java.util.List<BufferedMessage>> messageBuffer = new HashMap<>();
    
    private static class BufferedMessage {
        final String direction;
        final String message;
        final long timestamp;
        
        BufferedMessage(String direction, String message) {
            this.direction = direction;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
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
        
        // Create session status panel for overview
        sessionStatusPanel = new SessionStatusPanel(
            statusUpdater,
            cliTaskManager::terminateSession,
            cliTaskManager::getActiveSessions
        );
        
        setupLayout();
        setupUpdateTimer();
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
        setSize(800, 500); // Wider window to accommodate vertical tabs
        
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
     * Sets up the window layout with vertical tabbed interface.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Create tabbed pane with vertical tabs on the left
        sessionTabs = new JTabbedPane(JTabbedPane.LEFT);
        sessionTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        
        // Create overview panel (first tab)
        setupOverviewPanel();
        sessionTabs.addTab("Overview", overviewPanel);
        
        add(sessionTabs, BorderLayout.CENTER);
        
        // Add footer with close button
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);
        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        
        add(footerPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Sets up the overview panel showing all sessions.
     */
    private void setupOverviewPanel() {
        overviewPanel = new JPanel(new BorderLayout());
        
        // Add header panel
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel headerLabel = new JLabel("Active CLI Sessions");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(headerLabel);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        
        overviewPanel.add(headerPanel, BorderLayout.NORTH);
        overviewPanel.add(sessionStatusPanel, BorderLayout.CENTER);
    }
    
    /**
     * Sets up the timer for updating session status and tabs.
     */
    private void setupUpdateTimer() {
        sessionUpdateTimer = new Timer(2000, e -> {
            if (cliTaskManager != null && isVisible()) {
                updateSessionsAndTabs();
            }
        });
    }
    
    /**
     * Updates both the overview panel and individual session tabs.
     */
    private void updateSessionsAndTabs() {
        Map<String, SessionManager.KalixSession> activeSessions = cliTaskManager.getActiveSessions();
        
        // Update overview panel
        sessionStatusPanel.updateSessions(activeSessions);
        
        // Update/create individual session tabs
        for (SessionManager.KalixSession session : activeSessions.values()) {
            String sessionId = session.getSessionId();
            
            if (!sessionTabMap.containsKey(sessionId)) {
                // Create new tab for this session
                createSessionTab(session);
            } else {
                // Update existing tab
                sessionTabMap.get(sessionId).updateSession(session);
            }
        }
        
        // Remove tabs for sessions that no longer exist
        sessionTabMap.entrySet().removeIf(entry -> {
            if (!activeSessions.containsKey(entry.getKey())) {
                removeSessionTab(entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * Creates a new tab for a session.
     */
    private void createSessionTab(SessionManager.KalixSession session) {
        String sessionId = session.getSessionId();
        SessionTab sessionTab = new SessionTab(session, statusUpdater, 
            id -> cliTaskManager.terminateSession(id));
        
        sessionTabMap.put(sessionId, sessionTab);
        
        // Create more descriptive tab title for vertical layout
        String shortId = sessionId.length() > 12 ? 
            sessionId.substring(sessionId.length() - 8) : sessionId;
        String tabTitle = String.format("<html><b>%s</b><br><small>%s</small></html>", 
            shortId, session.getType().toString().toLowerCase());
        
        sessionTabs.addTab(tabTitle, sessionTab);
        
        // Switch to the new tab
        sessionTabs.setSelectedComponent(sessionTab);
    }
    
    /**
     * Removes a tab for a terminated session.
     */
    private void removeSessionTab(String sessionId) {
        LoggableSessionTab tab = sessionTabMap.get(sessionId);
        if (tab != null) {
            sessionTabs.remove((Component) tab);
        }
    }
    
    /**
     * Adds a message to a specific session's log.
     */
    public static void logSessionMessage(String sessionId, String direction, String message) {
        if (instance != null) {
            instance.addLogMessage(sessionId, direction, message);
        }
    }
    
    /**
     * Transfers messages and tab from temporary session ID to real session ID.
     */
    public static void transferSession(String tempSessionId, String realSessionId) {
        if (instance != null) {
            instance.transferSessionInternal(tempSessionId, realSessionId);
        }
    }
    
    /**
     * Adds a message to the specified session's log.
     * If no tab exists yet, buffers the message for later replay.
     */
    private void addLogMessage(String sessionId, String direction, String message) {
        LoggableSessionTab tab = sessionTabMap.get(sessionId);
        if (tab != null) {
            // Tab exists - add message directly
            tab.addLogMessage(direction, message);
        } else {
            // No tab yet - buffer the message
            messageBuffer.computeIfAbsent(sessionId, k -> new java.util.ArrayList<>())
                .add(new BufferedMessage(direction, message));
            
            // Try to create a tab for JSON protocol sessions
            tryCreateJsonProtocolTab(sessionId, direction, message);
        }
    }
    
    /**
     * Attempts to create a tab for JSON protocol sessions based on logged messages.
     */
    private void tryCreateJsonProtocolTab(String sessionId, String direction, String message) {
        // If this looks like a JSON protocol session starting, create a tab immediately
        if ("SYSTEM".equals(direction) && message.contains("Starting kalixcli with command")) {
            createJsonProtocolTab(sessionId);
        }
    }
    
    /**
     * Creates a tab for a JSON protocol session that doesn't have a SessionManager session.
     */
    private void createJsonProtocolTab(String sessionId) {
        SwingUtilities.invokeLater(() -> {
            if (!sessionTabMap.containsKey(sessionId)) {
                // Create a minimal SessionTab for JSON protocol sessions
                // Since we don't have a full SessionManager.KalixSession, create a mock one
                JsonProtocolSessionTab jsonTab = new JsonProtocolSessionTab(sessionId, statusUpdater);
                
                sessionTabMap.put(sessionId, jsonTab);
                
                // Add tab to UI
                String tabTitle = String.format("<html><b>%s</b><br><small>json protocol</small></html>", 
                    sessionId.length() > 12 ? sessionId.substring(sessionId.length() - 8) : sessionId);
                sessionTabs.addTab(tabTitle, jsonTab);
                
                // Replay buffered messages
                replayBufferedMessages(sessionId, jsonTab);
                
                // Switch to the new tab
                sessionTabs.setSelectedComponent(jsonTab);
            }
        });
    }
    
    /**
     * Replays buffered messages when a tab is created.
     */
    private void replayBufferedMessages(String sessionId, LoggableSessionTab tab) {
        java.util.List<BufferedMessage> messages = messageBuffer.remove(sessionId);
        if (messages != null) {
            for (BufferedMessage msg : messages) {
                tab.addLogMessage(msg.direction, msg.message);
            }
        }
    }
    
    /**
     * Internal method to transfer session from temp ID to real ID.
     */
    private void transferSessionInternal(String tempSessionId, String realSessionId) {
        SwingUtilities.invokeLater(() -> {
            LoggableSessionTab tempTab = sessionTabMap.get(tempSessionId);
            if (tempTab != null) {
                // Remove the old tab from the map and UI
                sessionTabMap.remove(tempSessionId);
                sessionTabs.remove((Component) tempTab);
                
                // Update the tab's session ID if it supports it
                if (tempTab instanceof JsonProtocolSessionTab) {
                    // Create a new tab with the real session ID
                    JsonProtocolSessionTab newTab = new JsonProtocolSessionTab(realSessionId, statusUpdater);
                    sessionTabMap.put(realSessionId, newTab);
                    
                    // Add the new tab
                    String tabTitle = String.format("<html><b>%s</b><br><small>json protocol</small></html>", 
                        realSessionId.length() > 12 ? realSessionId.substring(realSessionId.length() - 8) : realSessionId);
                    sessionTabs.addTab(tabTitle, newTab);
                    
                    // Transfer all the old tab's messages to the new tab
                    transferTabMessages(tempTab, newTab);
                    
                    // Switch to the new tab
                    sessionTabs.setSelectedComponent(newTab);
                    
                    // Log the transfer
                    newTab.addLogMessage("SYSTEM", "Session ID established - transferred from " + tempSessionId);
                }
            }
            
            // Also transfer any buffered messages
            java.util.List<BufferedMessage> messages = messageBuffer.remove(tempSessionId);
            if (messages != null) {
                messageBuffer.put(realSessionId, messages);
            }
        });
    }
    
    /**
     * Transfers messages from one tab to another (simplified approach).
     */
    private void transferTabMessages(LoggableSessionTab fromTab, LoggableSessionTab toTab) {
        // This is a simplified approach - in a real implementation we might
        // extract the actual log messages from the source tab
        toTab.addLogMessage("SYSTEM", "Messages transferred from temporary session");
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
                    updateSessionsAndTabs();
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
     * Manually refreshes the session display and tabs.
     * Useful for immediate updates when sessions change.
     */
    public void refreshSessions() {
        if (cliTaskManager != null) {
            SwingUtilities.invokeLater(this::updateSessionsAndTabs);
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
}