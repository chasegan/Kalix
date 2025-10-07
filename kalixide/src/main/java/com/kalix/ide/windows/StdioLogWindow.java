package com.kalix.ide.windows;

import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.JsonStdioProtocol;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.constants.UIConstants;
import com.kalix.ide.utils.JsonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Map;

import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Separate window for displaying STDIO communication logs for a specific run.
 */
public class StdioLogWindow extends JFrame {


    private static Map<String, StdioLogWindow> openWindows = new HashMap<>();

    private final String runName;
    private final SessionManager.KalixSession session;
    private final StdioTaskManager stdioTaskManager;
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
     * Creates a new STDIO Log window for the specified run.
     */
    private StdioLogWindow(String runName, SessionManager.KalixSession session, StdioTaskManager stdioTaskManager, JFrame parentFrame) {
        this.runName = runName;
        this.session = session;
        this.stdioTaskManager = stdioTaskManager;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
        setupUpdateTimer();

        // Initial log update
        updateLog();
    }

    /**
     * Shows the STDIO Log window for the specified run.
     * Uses one window per run (singleton per run).
     */
    public static void showStdioLogWindow(String runName, SessionManager.KalixSession session, StdioTaskManager stdioTaskManager, JFrame parentFrame) {
        String sessionKey = session.getSessionKey();

        StdioLogWindow existingWindow = openWindows.get(sessionKey);
        if (existingWindow != null) {
            // Bring existing window to front
            existingWindow.setVisible(true);
            existingWindow.toFront();
            existingWindow.requestFocus();
        } else {
            // Create new window
            StdioLogWindow newWindow = new StdioLogWindow(runName, session, stdioTaskManager, parentFrame);
            openWindows.put(sessionKey, newWindow);
            newWindow.setVisible(true);
        }
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("STDIO Log - " + runName);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(UIConstants.StdioLog.WINDOW_WIDTH, UIConstants.StdioLog.WINDOW_HEIGHT);

        // Position relative to parent window
        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + UIConstants.StdioLog.WINDOW_OFFSET, parentLocation.y + UIConstants.StdioLog.WINDOW_OFFSET);

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
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIConstants.StdioLog.FONT_SIZE));
        logArea.setBackground(Color.WHITE);
        logArea.setCodeFoldingEnabled(false);

        logScrollPane = new RTextScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    private void setupLayout() {
        setLayout(new BorderLayout());

        // Create STDIO Session Details panel
        JPanel detailsPanel = createSessionDetailsPanel();

        // Create main content panel with details and log
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(detailsPanel, BorderLayout.NORTH);
        mainPanel.add(logScrollPane, BorderLayout.CENTER);

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
     * Creates the STDIO Session Details panel.
     */
    private JPanel createSessionDetailsPanel() {
        JPanel detailsPanel = new JPanel(new GridBagLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Info"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.anchor = GridBagConstraints.WEST;

        // Add info fields
        gbc.gridx = 0; gbc.gridy = 0;
        detailsPanel.add(new JLabel("Kalixcli UID:"), gbc);
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
        detailsPanel.add(new JLabel("Uptime:"), gbc);
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
                openWindows.remove(session.getSessionKey());
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
        updateTimer = new Timer(UIConstants.StdioLog.UPDATE_INTERVAL_MS, e -> updateLog());
        updateTimer.setRepeats(true);
    }

    /**
     * Updates the STDIO Session Details information.
     */
    private void updateSessionDetails() {
        if (session == null) return;

        // Update session details - show kalixcli_uid if available, otherwise "unknown"
        String displayId = session.getKalixcliUid() != null ? session.getKalixcliUid() : UIConstants.StdioLog.UNKNOWN_SESSION;
        sessionNameLabel.setText(displayId);

        sessionStatusLabel.setText(session.getState() != null ? session.getState().toString() : "Unknown");
        sessionStartTimeLabel.setText(session.getStartTime() != null ? session.getStartTime().toString() : "Unknown");

        // Calculate and display uptime
        sessionDurationLabel.setText(formatUptime(session.getStartTime()));
    }

    /**
     * Updates the log and session details.
     */
    private void updateLog() {
        updateLog(false);
    }

    /**
     * Forces an update of the log and session details regardless of content changes.
     */
    private void forceUpdateLog() {
        updateLog(true);
    }

    /**
     * Updates the log and session details with option to force update.
     *
     * @param forceUpdate if true, updates regardless of content changes
     */
    private void updateLog(boolean forceUpdate) {
        // Update session details
        updateSessionDetails();

        // Update log content
        String logContent = getLogContent();

        if (forceUpdate || !logContent.equals(lastLogContent)) {
            updateLogWithContent(logContent);
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
     * Sends a ping command (echo) to the kalixcli session.
     * Shows a dialog allowing the user to edit the JSON before sending.
     */
    private void sendPingCommand() {
        try {
            // Generate initial JSON command
            Map<String, Object> parameters = Map.of("string", "Ping from IDE at " + java.time.LocalDateTime.now());
            String kalixcliUid = session.getKalixcliUid() != null ? session.getKalixcliUid() : "";
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
                // Get edited JSON and flatten to single line
                String editedJson = textArea.getText().trim();
                String flattenedJson = JsonUtils.flattenJson(editedJson);

                // Send the flattened JSON command
                stdioTaskManager.sendCommand(session.getSessionKey(), flattenedJson);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Failed to send ping command: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    // Utility Methods

    /**
     * Formats the uptime duration from a start time to a readable HH:MM:SS format.
     *
     * @param startTime the session start time
     * @return formatted uptime string
     */
    private static String formatUptime(java.time.LocalDateTime startTime) {
        if (startTime == null) return "00:00:00";

        long durationMs = System.currentTimeMillis() -
            startTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }


    /**
     * Gets the appropriate log content, handling null communication logs.
     *
     * @return the log content or fallback message
     */
    private String getLogContent() {
        return session.getCommunicationLog() != null
            ? session.getCommunicationLog().getFormattedLog()
            : UIConstants.StdioLog.NO_LOG_MESSAGE;
    }

}