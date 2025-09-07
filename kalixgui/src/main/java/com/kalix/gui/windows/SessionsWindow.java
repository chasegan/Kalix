package com.kalix.gui.windows;

import com.kalix.gui.components.SessionStatusPanel;
import com.kalix.gui.managers.CliTaskManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Separate window for managing CLI sessions.
 * Provides a dedicated interface for monitoring active sessions and requesting results.
 */
public class SessionsWindow extends JFrame {
    
    private final SessionStatusPanel sessionStatusPanel;
    private final CliTaskManager cliTaskManager;
    private Timer sessionUpdateTimer;
    private static SessionsWindow instance;
    
    /**
     * Private constructor for singleton pattern.
     * 
     * @param parentFrame parent frame for positioning
     * @param cliTaskManager CLI task manager for session operations
     * @param statusUpdater callback for status updates
     */
    private SessionsWindow(JFrame parentFrame, CliTaskManager cliTaskManager, Consumer<String> statusUpdater) {
        this.cliTaskManager = cliTaskManager;
        
        setupWindow(parentFrame);
        
        // Create session status panel
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
        setSize(500, 400);
        
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
        add(sessionStatusPanel, BorderLayout.CENTER);
        
        // Add footer with close button
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);
        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));
        
        add(footerPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Sets up the timer for updating session status.
     */
    private void setupUpdateTimer() {
        sessionUpdateTimer = new Timer(2000, e -> {
            if (cliTaskManager != null && isVisible()) {
                sessionStatusPanel.updateSessions(cliTaskManager.getActiveSessions());
            }
        });
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
                    sessionStatusPanel.updateSessions(cliTaskManager.getActiveSessions());
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
            SwingUtilities.invokeLater(() -> 
                sessionStatusPanel.updateSessions(cliTaskManager.getActiveSessions()));
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