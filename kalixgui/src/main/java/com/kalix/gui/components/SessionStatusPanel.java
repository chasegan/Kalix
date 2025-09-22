package com.kalix.gui.components;

import com.kalix.gui.cli.SessionManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Panel component for displaying and managing active Kalix CLI sessions.
 * Shows session status and allows session termination.
 */
public class SessionStatusPanel extends JPanel {
    
    private final Consumer<String> statusUpdater;
    private final java.util.function.Function<String, CompletableFuture<Void>> sessionTerminator;
    private final java.util.function.Supplier<java.util.Map<String, SessionManager.KalixSession>> sessionProvider;
    
    private final JPanel sessionsContainer;
    private final JScrollPane scrollPane;
    private final JLabel noSessionsLabel;
    
    /**
     * Creates a new SessionStatusPanel.
     * 
     * @param statusUpdater callback for status updates
     * @param sessionTerminator function to terminate a session
     * @param sessionProvider function to get current active sessions
     */
    public SessionStatusPanel(Consumer<String> statusUpdater,
                             java.util.function.Function<String, CompletableFuture<Void>> sessionTerminator,
                             java.util.function.Supplier<java.util.Map<String, SessionManager.KalixSession>> sessionProvider) {
        this.statusUpdater = statusUpdater;
        this.sessionTerminator = sessionTerminator;
        this.sessionProvider = sessionProvider;
        
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Active Sessions"));
        
        // Container for session panels
        sessionsContainer = new JPanel();
        sessionsContainer.setLayout(new BoxLayout(sessionsContainer, BoxLayout.Y_AXIS));
        
        // Scroll pane for sessions
        scrollPane = new JScrollPane(sessionsContainer);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setPreferredSize(new Dimension(300, 200));
        
        // Label shown when no sessions are active
        noSessionsLabel = new JLabel("No active sessions", JLabel.CENTER);
        noSessionsLabel.setForeground(Color.GRAY);
        noSessionsLabel.setFont(noSessionsLabel.getFont().deriveFont(Font.ITALIC));
        
        add(scrollPane, BorderLayout.CENTER);
        showNoSessionsMessage();
    }
    
    /**
     * Updates the panel with current session information.
     * 
     * @param sessions map of session IDs to session info
     */
    public void updateSessions(Map<String, SessionManager.KalixSession> sessions) {
        SwingUtilities.invokeLater(() -> {
            sessionsContainer.removeAll();
            
            if (sessions == null || sessions.isEmpty()) {
                showNoSessionsMessage();
            } else {
                hideNoSessionsMessage();
                for (SessionManager.KalixSession session : sessions.values()) {
                    sessionsContainer.add(createSessionPanel(session));
                    sessionsContainer.add(Box.createVerticalStrut(5));
                }
            }
            
            sessionsContainer.revalidate();
            sessionsContainer.repaint();
        });
    }
    
    /**
     * Creates a panel for displaying a single session.
     */
    private JPanel createSessionPanel(SessionManager.KalixSession session) {
        JPanel sessionPanel = new JPanel(new BorderLayout());
        sessionPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        // Session info panel
        JPanel infoPanel = new JPanel(new GridLayout(0, 1));
        infoPanel.add(new JLabel("ID: " + session.getSessionKey()));
        infoPanel.add(new JLabel("Type: Model Run"));
        infoPanel.add(new JLabel("State: " + session.getState()));
        
        // Controls panel
        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        
        
        // Terminate button
        JButton terminateButton = new JButton("Terminate");
        terminateButton.setFont(terminateButton.getFont().deriveFont(10f));
        terminateButton.setForeground(Color.RED);
        terminateButton.addActionListener(e -> terminateSession(session.getSessionKey()));
        controlsPanel.add(terminateButton);
        
        sessionPanel.add(infoPanel, BorderLayout.CENTER);
        sessionPanel.add(controlsPanel, BorderLayout.SOUTH);
        
        // Color coding by state
        switch (session.getState()) {
            case STARTING:
                sessionPanel.setBackground(new Color(255, 248, 220)); // Light yellow
                break;
            case RUNNING:
                sessionPanel.setBackground(new Color(220, 240, 255)); // Light blue
                break;
            case READY:
                sessionPanel.setBackground(new Color(220, 255, 220)); // Light green
                break;
            case ERROR:
                sessionPanel.setBackground(new Color(255, 220, 220)); // Light red
                break;
            default:
                sessionPanel.setBackground(Color.WHITE);
                break;
        }
        
        return sessionPanel;
    }
    
    /**
     * Shows the "no sessions" message.
     */
    private void showNoSessionsMessage() {
        remove(scrollPane);
        add(noSessionsLabel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
    
    /**
     * Hides the "no sessions" message and shows the sessions scroll pane.
     */
    private void hideNoSessionsMessage() {
        remove(noSessionsLabel);
        add(scrollPane, BorderLayout.CENTER);
        revalidate();
        repaint();
    }
    
    /**
     * Terminates a session with confirmation.
     */
    private void terminateSession(String sessionId) {
        int result = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to terminate session " + sessionId + "?",
            "Terminate Session", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        
        if (result == JOptionPane.YES_OPTION) {
            statusUpdater.accept("Terminating session " + sessionId + "...");
            
            sessionTerminator.apply(sessionId)
                .thenRun(() -> SwingUtilities.invokeLater(() -> {
                    statusUpdater.accept("Session " + sessionId + " terminated");
                    // Immediately refresh the session list to show the termination
                    if (sessionProvider != null) {
                        updateSessions(sessionProvider.get());
                    }
                }))
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        statusUpdater.accept("Error terminating session: " + throwable.getMessage());
                        JOptionPane.showMessageDialog(this,
                            "Failed to terminate session: " + throwable.getMessage(),
                            "Termination Error", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
        }
    }
}