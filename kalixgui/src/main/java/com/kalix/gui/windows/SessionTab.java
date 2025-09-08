package com.kalix.gui.windows;

import com.kalix.gui.cli.SessionManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Individual tab panel for a session showing STDIO communication logs.
 * Provides detailed logging of all communication between GUI and kalixcli.
 */
public class SessionTab extends JPanel implements LoggableSessionTab {
    
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LOG_LINES = 1000;
    
    private final SessionManager.KalixSession session;
    private final Consumer<String> statusUpdater;
    private final Function<String, CompletableFuture<Void>> sessionTerminator;
    
    // UI Components
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    private JLabel sessionStatusLabel;
    private JLabel sessionInfoLabel;
    private JButton terminateButton;
    private JButton clearLogButton;
    private JCheckBox autoScrollCheckBox;
    
    // Log management
    private int logLineCount = 0;
    
    /**
     * Creates a new SessionTab for the given session.
     */
    public SessionTab(SessionManager.KalixSession session, 
                     Consumer<String> statusUpdater,
                     Function<String, CompletableFuture<Void>> sessionTerminator) {
        this.session = session;
        this.statusUpdater = statusUpdater;
        this.sessionTerminator = sessionTerminator;
        
        setupLayout();
        updateSessionInfo();
    }
    
    /**
     * Sets up the tab layout.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Top panel with session info and controls
        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);
        
        // Center panel with log area
        JPanel logPanel = createLogPanel();
        add(logPanel, BorderLayout.CENTER);
        
        // Bottom panel with controls
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Creates the top panel with session information.
     */
    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Session Information"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        // Session info labels
        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        sessionInfoLabel = new JLabel();
        sessionStatusLabel = new JLabel();
        
        infoPanel.add(sessionInfoLabel);
        infoPanel.add(sessionStatusLabel);
        
        // Control buttons
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        terminateButton = new JButton("Terminate Session");
        terminateButton.setForeground(Color.RED);
        terminateButton.addActionListener(this::terminateSession);
        
        controlPanel.add(terminateButton);
        
        topPanel.add(infoPanel, BorderLayout.CENTER);
        topPanel.add(controlPanel, BorderLayout.EAST);
        
        return topPanel;
    }
    
    /**
     * Creates the log panel with scrollable text area.
     */
    private JPanel createLogPanel() {
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("STDIO Communication Log"));
        
        // Log text area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(248, 248, 248));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        // Scroll pane for log area
        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        logScrollPane.setPreferredSize(new Dimension(550, 350)); // Adjusted for vertical tab layout
        
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        // Add initial welcome message
        addLogMessage("SYSTEM", "Session tab created at " + LocalDateTime.now().format(TIME_FORMAT));
        
        return logPanel;
    }
    
    /**
     * Creates the bottom control panel.
     */
    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        clearLogButton = new JButton("Clear Log");
        clearLogButton.addActionListener(e -> clearLog());
        
        autoScrollCheckBox = new JCheckBox("Auto-scroll", true);
        
        JLabel logCountLabel = new JLabel("Messages: 0");
        
        bottomPanel.add(clearLogButton);
        bottomPanel.add(autoScrollCheckBox);
        bottomPanel.add(Box.createHorizontalStrut(20));
        bottomPanel.add(logCountLabel);
        
        return bottomPanel;
    }
    
    /**
     * Updates the session information display.
     */
    public void updateSession(SessionManager.KalixSession updatedSession) {
        SwingUtilities.invokeLater(() -> {
            updateSessionInfo();
            
            // Update button state based on session state
            boolean canTerminate = updatedSession.isActive();
            terminateButton.setEnabled(canTerminate);
            
            if (!canTerminate) {
                terminateButton.setText("Session Ended");
                terminateButton.setForeground(Color.GRAY);
            }
        });
    }
    
    /**
     * Updates the session information labels.
     */
    private void updateSessionInfo() {
        sessionInfoLabel.setText(String.format(
            "<html><b>ID:</b> %s &nbsp;&nbsp; <b>Type:</b> %s &nbsp;&nbsp; <b>Started:</b> %s</html>",
            session.getSessionId(),
            session.getType(),
            session.getStartTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        ));
        
        Color statusColor;
        switch (session.getState()) {
            case STARTING:
                statusColor = Color.ORANGE;
                break;
            case RUNNING:
                statusColor = Color.BLUE;
                break;
            case READY:
                statusColor = Color.GREEN.darker();
                break;
            case ERROR:
                statusColor = Color.RED;
                break;
            case TERMINATED:
                statusColor = Color.GRAY;
                break;
            default:
                statusColor = Color.BLACK;
                break;
        }
        
        sessionStatusLabel.setText(String.format(
            "<html><b>Status:</b> <font color='%s'>%s</font> &nbsp;&nbsp; <b>Last Activity:</b> %s</html>",
            String.format("#%06X", statusColor.getRGB() & 0xFFFFFF),
            session.getState(),
            session.getLastActivity().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        ));
    }
    
    /**
     * Adds a log message to the communication log.
     * 
     * @param direction the direction of communication ("GUI->CLI", "CLI->GUI", "SYSTEM")
     * @param message the message content
     */
    public void addLogMessage(String direction, String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = LocalDateTime.now().format(TIME_FORMAT);
            String logLine = String.format("[%s] %s: %s\n", timestamp, direction, message);
            
            logArea.append(logLine);
            logLineCount++;
            
            // Limit log size to prevent memory issues
            if (logLineCount > MAX_LOG_LINES) {
                String text = logArea.getText();
                int firstNewline = text.indexOf('\n');
                if (firstNewline > 0) {
                    logArea.setText(text.substring(firstNewline + 1));
                    logLineCount--;
                }
            }
            
            // Auto-scroll if enabled
            if (autoScrollCheckBox.isSelected()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
            
            // Update log count in status
            updateLogCount();
        });
    }
    
    /**
     * Clears the log area.
     */
    private void clearLog() {
        logArea.setText("");
        logLineCount = 0;
        addLogMessage("SYSTEM", "Log cleared at " + LocalDateTime.now().format(TIME_FORMAT));
        updateLogCount();
    }
    
    /**
     * Updates the log message count display.
     */
    private void updateLogCount() {
        Component[] components = ((JPanel) getComponent(2)).getComponents(); // Bottom panel
        for (Component comp : components) {
            if (comp instanceof JLabel && ((JLabel) comp).getText().startsWith("Messages:")) {
                ((JLabel) comp).setText("Messages: " + logLineCount);
                break;
            }
        }
    }
    
    /**
     * Handles session termination.
     */
    private void terminateSession(ActionEvent e) {
        int result = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to terminate session " + session.getSessionId() + "?\n" +
            "This will end the CLI process and close this tab.",
            "Terminate Session", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        
        if (result == JOptionPane.YES_OPTION) {
            addLogMessage("SYSTEM", "User requested session termination");
            statusUpdater.accept("Terminating session " + session.getSessionId() + "...");
            
            terminateButton.setEnabled(false);
            terminateButton.setText("Terminating...");
            
            sessionTerminator.apply(session.getSessionId())
                .thenRun(() -> SwingUtilities.invokeLater(() -> {
                    addLogMessage("SYSTEM", "Session terminated successfully");
                    statusUpdater.accept("Session " + session.getSessionId() + " terminated");
                    terminateButton.setText("Session Ended");
                    terminateButton.setForeground(Color.GRAY);
                }))
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        addLogMessage("SYSTEM", "ERROR: Failed to terminate session: " + throwable.getMessage());
                        statusUpdater.accept("Error terminating session: " + throwable.getMessage());
                        terminateButton.setEnabled(true);
                        terminateButton.setText("Terminate Session");
                        
                        JOptionPane.showMessageDialog(this,
                            "Failed to terminate session: " + throwable.getMessage(),
                            "Termination Error", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
        }
    }
    
    /**
     * Gets the session ID for this tab.
     */
    public String getSessionId() {
        return session.getSessionId();
    }
}