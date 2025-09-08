package com.kalix.gui.windows;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Simplified session tab for JSON STDIO protocol sessions that don't have 
 * a full SessionManager.KalixSession backing them.
 * Implements the same interface as SessionTab but without SessionManager dependency.
 */
public class JsonProtocolSessionTab extends JPanel implements LoggableSessionTab {
    
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LOG_LINES = 1000;
    
    private final String sessionId;
    private final Consumer<String> statusUpdater;
    
    // UI Components
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    private JLabel sessionInfoLabel;
    private JCheckBox autoScrollCheckBox;
    
    // Log management
    private int logLineCount = 0;
    
    /**
     * Creates a new JsonProtocolSessionTab for the given session ID.
     */
    public JsonProtocolSessionTab(String sessionId, Consumer<String> statusUpdater) {
        this.sessionId = sessionId;
        this.statusUpdater = statusUpdater;
        
        setupLayout();
        updateSessionInfo();
    }
    
    /**
     * Sets up the tab layout.
     */
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Top panel with session info
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
            BorderFactory.createTitledBorder("JSON STDIO Protocol Session"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        
        sessionInfoLabel = new JLabel();
        topPanel.add(sessionInfoLabel, BorderLayout.CENTER);
        
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
        logScrollPane.setPreferredSize(new Dimension(550, 350));
        
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        // Add initial welcome message
        addLogMessage("SYSTEM", "JSON Protocol session tab created at " + LocalDateTime.now().format(TIME_FORMAT));
        
        return logPanel;
    }
    
    /**
     * Creates the bottom control panel.
     */
    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton clearLogButton = new JButton("Clear Log");
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
    private void updateSessionInfo() {
        sessionInfoLabel.setText(String.format(
            "<html><b>ID:</b> %s &nbsp;&nbsp; <b>Type:</b> JSON STDIO Protocol &nbsp;&nbsp; <b>Status:</b> Active</html>",
            sessionId
        ));
    }
    
    /**
     * Adds a log message to the communication log.
     * 
     * @param direction the direction of communication ("GUI->CLI", "CLI->GUI", "SYSTEM")
     * @param message the message content
     */
    @Override
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
     * Gets the session ID for this tab.
     */
    @Override
    public String getSessionId() {
        return sessionId;
    }
}