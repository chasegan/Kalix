package com.kalix.gui.windows;

import com.kalix.gui.cli.SessionManager;
import com.kalix.gui.cli.JsonStdioProtocol;
import com.kalix.gui.managers.CliTaskManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Separate window for displaying CLI communication logs for a specific run.
 */
public class CliLogWindow extends JFrame {

    private static Map<String, CliLogWindow> openWindows = new HashMap<>();

    private final String runName;
    private final SessionManager.KalixSession session;
    private final CliTaskManager cliTaskManager;
    private RSyntaxTextArea logArea;
    private RTextScrollPane logScrollPane;
    private Timer updateTimer;
    private String lastLogContent = "";

    // CLI Session Details components
    private JLabel sessionNameLabel;
    private JLabel sessionStatusLabel;
    private JLabel sessionStartTimeLabel;
    private JLabel sessionDurationLabel;

    /**
     * Creates a new CLI Log window for the specified run.
     */
    private CliLogWindow(String runName, SessionManager.KalixSession session, CliTaskManager cliTaskManager, JFrame parentFrame) {
        this.runName = runName;
        this.session = session;
        this.cliTaskManager = cliTaskManager;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupUpdateTimer();

        // Initial log update
        updateLog();
    }

    /**
     * Shows the CLI Log window for the specified run.
     * Uses one window per run (singleton per run).
     */
    public static void showCliLogWindow(String runName, SessionManager.KalixSession session, CliTaskManager cliTaskManager, JFrame parentFrame) {
        String sessionId = session.getSessionId();

        CliLogWindow existingWindow = openWindows.get(sessionId);
        if (existingWindow != null) {
            // Bring existing window to front
            existingWindow.setVisible(true);
            existingWindow.toFront();
            existingWindow.requestFocus();
        } else {
            // Create new window
            CliLogWindow newWindow = new CliLogWindow(runName, session, cliTaskManager, parentFrame);
            openWindows.put(sessionId, newWindow);
            newWindow.setVisible(true);
        }
    }

    /**
     * Closes the CLI Log window for the specified session.
     */
    public static void closeCliLogWindow(String sessionId) {
        CliLogWindow window = openWindows.get(sessionId);
        if (window != null) {
            window.dispose();
        }
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("CLI Log - " + runName);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(800, 600);

        // Position relative to parent window
        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 100, parentLocation.y + 100);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // Initialize CLI Session Details components
        sessionNameLabel = new JLabel();
        sessionStatusLabel = new JLabel();
        sessionStartTimeLabel = new JLabel();
        sessionDurationLabel = new JLabel();

        // Initialize log text area
        logArea = new RSyntaxTextArea();
        logArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE); // Plain text
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(Color.WHITE);
        logArea.setCodeFoldingEnabled(false);

        logScrollPane = new RTextScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Header panel
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel headerLabel = new JLabel("CLI Communication Log for " + runName);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(headerLabel);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        // Create CLI Session Details panel
        JPanel detailsPanel = createSessionDetailsPanel();

        // Create main content panel with details and log
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(detailsPanel, BorderLayout.NORTH);
        mainPanel.add(logScrollPane, BorderLayout.CENTER);

        add(headerPanel, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);

        // Footer
        JPanel footerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton pingButton = new JButton("Ping");
        pingButton.addActionListener(e -> sendPingCommand());
        footerPanel.add(pingButton);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> forceUpdateLog());
        footerPanel.add(refreshButton);

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> setVisible(false));
        footerPanel.add(closeButton);

        footerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        add(footerPanel, BorderLayout.SOUTH);
    }

    /**
     * Creates the CLI Session Details panel.
     */
    private JPanel createSessionDetailsPanel() {
        JPanel detailsPanel = new JPanel(new GridBagLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("CLI Session Details"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Add info fields
        gbc.gridx = 0; gbc.gridy = 0;
        detailsPanel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        detailsPanel.add(sessionNameLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        detailsPanel.add(new JLabel("Session Status:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        detailsPanel.add(sessionStatusLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        detailsPanel.add(new JLabel("Start Time:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        detailsPanel.add(sessionStartTimeLabel, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE;
        detailsPanel.add(new JLabel("Duration:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        detailsPanel.add(sessionDurationLabel, gbc);

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
                // Remove from tracking
                openWindows.remove(session.getSessionId());
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
                // Immediate update when window becomes active
                updateLog();
            }
        });
    }

    private void setupUpdateTimer() {
        updateTimer = new Timer(2000, e -> updateLog());
        updateTimer.setRepeats(true);
    }

    /**
     * Updates the CLI Session Details information.
     */
    private void updateSessionDetails() {
        // Update session details
        sessionNameLabel.setText(session.getSessionId());
        sessionStatusLabel.setText(session.getState().toString());
        sessionStartTimeLabel.setText(session.getStartTime().toString());

        // Calculate duration
        long durationMs = System.currentTimeMillis() - session.getStartTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        String duration = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
        sessionDurationLabel.setText(duration);
    }

    /**
     * Updates the log and session details.
     */
    private void updateLog() {
        // Update session details
        updateSessionDetails();

        // Update log content
        if (session.getCommunicationLog() != null) {
            String logContent = session.getCommunicationLog().getFormattedLog();

            // Only update if content has actually changed
            if (!logContent.equals(lastLogContent)) {
                updateLogWithContent(logContent);
            }
        } else {
            String noLogMessage = "No communication log available for this session.";
            if (!noLogMessage.equals(lastLogContent)) {
                updateLogWithContent(noLogMessage);
            }
        }
    }

    /**
     * Forces an update of the log and session details regardless of content changes.
     */
    private void forceUpdateLog() {
        // Update session details
        updateSessionDetails();

        // Update log content
        if (session.getCommunicationLog() != null) {
            String logContent = session.getCommunicationLog().getFormattedLog();
            updateLogWithContent(logContent);
        } else {
            updateLogWithContent("No communication log available for this session.");
        }
    }

    /**
     * Updates the log with the given content, implementing smart auto-scroll.
     */
    private void updateLogWithContent(String logContent) {
        // Check if user is at or near the bottom before updating
        boolean shouldAutoScroll = isUserAtBottom();

        logArea.setText(logContent);
        lastLogContent = logContent;

        // Only auto-scroll if user was already at the bottom
        if (shouldAutoScroll) {
            SwingUtilities.invokeLater(this::scrollToBottom);
        }
    }

    /**
     * Checks if the user is currently at or near the bottom of the log.
     */
    private boolean isUserAtBottom() {
        if (logScrollPane == null || logArea == null) {
            return true; // Default to auto-scroll if components aren't ready
        }

        JScrollBar verticalScrollBar = logScrollPane.getVerticalScrollBar();
        if (verticalScrollBar == null) {
            return true; // Default to auto-scroll if no scroll bar
        }

        // Check if user is within a small threshold of the bottom
        int currentValue = verticalScrollBar.getValue();
        int maximum = verticalScrollBar.getMaximum();
        int extent = verticalScrollBar.getVisibleAmount();
        int threshold = 10; // pixels of tolerance

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
     * Sends a ping command (echo) to the kalixcli session.
     */
    private void sendPingCommand() {
        try {
            Map<String, Object> parameters = Map.of("string", "Ping from GUI at " + java.time.LocalDateTime.now());

            // Get CLI session ID if available
            String cliSessionId = session.getCliSessionId() != null ? session.getCliSessionId() : "";

            String jsonCommand = JsonStdioProtocol.createCommandMessage("echo", parameters, cliSessionId);

            // Use CliTaskManager to send command, which handles proper logging
            cliTaskManager.sendCommand(session.getSessionId(), jsonCommand);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Failed to send ping command: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

}