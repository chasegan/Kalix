package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationRule;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tooltip display for validation issues.
 * Handles mouse hover detection and shows custom tooltips with icons and formatting.
 */
public class LinterTooltipManager {

    private final RSyntaxTextArea textArea;
    private final ConcurrentHashMap<Integer, ValidationIssue> issuesByLine;

    // Tooltip components
    private JWindow tooltipWindow;
    private JPanel tooltipPanel;
    private Timer hideTimer;

    public LinterTooltipManager(RSyntaxTextArea textArea, ConcurrentHashMap<Integer, ValidationIssue> issuesByLine) {
        this.textArea = textArea;
        this.issuesByLine = issuesByLine;
        setupTooltipComponents();
        setupMouseListeners();
    }

    private void setupTooltipComponents() {
        tooltipWindow = new JWindow();
        tooltipWindow.setType(Window.Type.POPUP);
        tooltipWindow.setFocusableWindowState(false);

        tooltipPanel = new JPanel();
        tooltipPanel.setLayout(new BoxLayout(tooltipPanel, BoxLayout.X_AXIS));
        tooltipPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        tooltipPanel.setBackground(new Color(255, 255, 225)); // Light yellow background

        tooltipWindow.add(tooltipPanel);
    }

    private void setupMouseListeners() {
        textArea.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                // Cancel any existing hide timer
                if (hideTimer != null && hideTimer.isRunning()) {
                    hideTimer.stop();
                }

                ValidationIssue issue = getValidationIssueForPosition(e.getPoint());

                if (issue != null) {
                    showCustomTooltip(issue, e.getLocationOnScreen());
                } else {
                    // Hide tooltip after a short delay to prevent flickering
                    hideTimer = new Timer(100, evt -> hideCustomTooltip());
                    hideTimer.setRepeats(false);
                    hideTimer.start();
                }
            }
        });

        textArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hideCustomTooltip();
            }
        });
    }

    private void showCustomTooltip(ValidationIssue issue, Point screenLocation) {
        buildTooltipContent(issue);
        tooltipWindow.pack();

        // Position tooltip slightly offset from mouse
        int x = screenLocation.x + 10;
        int y = screenLocation.y + 20;

        // Adjust position if tooltip would go off screen
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension tooltipSize = tooltipWindow.getSize();

        if (x + tooltipSize.width > screenSize.width) {
            x = screenLocation.x - tooltipSize.width - 10;
        }
        if (y + tooltipSize.height > screenSize.height) {
            y = screenLocation.y - tooltipSize.height - 10;
        }

        tooltipWindow.setLocation(x, y);
        tooltipWindow.setVisible(true);
    }

    private void hideCustomTooltip() {
        if (tooltipWindow != null) {
            tooltipWindow.setVisible(false);
        }
    }

    private ValidationIssue getValidationIssueForPosition(Point point) {
        try {
            int offset = textArea.viewToModel2D(point);
            int line = textArea.getLineOfOffset(offset) + 1; // Convert to 1-based line numbers
            return issuesByLine.get(line);
        } catch (BadLocationException e) {
            // Invalid position, no tooltip
            return null;
        }
    }

    private void buildTooltipContent(ValidationIssue issue) {
        // Clear previous content
        tooltipPanel.removeAll();

        // Create appropriate FontAwesome icon based on severity
        FontIcon icon;
        Color severityColor;
        if (issue.getSeverity() == ValidationRule.Severity.ERROR) {
            icon = FontIcon.of(FontAwesomeSolid.TIMES, 12, Color.RED);
            severityColor = Color.RED;
        } else {
            icon = FontIcon.of(FontAwesomeSolid.EXCLAMATION_TRIANGLE, 12, Color.ORANGE);
            severityColor = Color.ORANGE;
        }

        // Add icon with proper spacing
        JLabel iconLabel = new JLabel(icon);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        tooltipPanel.add(iconLabel);

        // Create message label with proper formatting
        JLabel messageLabel = new JLabel(issue.getMessage());
        messageLabel.setForeground(Color.BLACK);
        messageLabel.setFont(messageLabel.getFont().deriveFont(12f));
        tooltipPanel.add(messageLabel);

        // Add severity indicator
        JLabel severityLabel = new JLabel(" [" + issue.getSeverity() + "]");
        severityLabel.setForeground(severityColor);
        severityLabel.setFont(severityLabel.getFont().deriveFont(Font.BOLD, 10f));
        tooltipPanel.add(severityLabel);
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        if (hideTimer != null && hideTimer.isRunning()) {
            hideTimer.stop();
        }
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
        }
    }
}