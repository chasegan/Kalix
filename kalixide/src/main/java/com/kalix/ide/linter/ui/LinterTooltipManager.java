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

    // Delay before a tooltip appears once the pointer settles on an issue line. Transient
    // crossings (e.g. sweeping the mouse past several error lines) cancel the pending show
    // before it fires, so no tooltip is ever built for lines the pointer merely passes over.
    private static final int SHOW_DELAY_MS = 200;

    // Tooltip components
    private JWindow tooltipWindow;
    private JPanel tooltipPanel;
    private Timer hideTimer;
    private Timer showTimer;

    // Cached severity icons, built once to avoid re-rendering the glyph on every tooltip build.
    private final FontIcon errorIcon = FontIcon.of(FontAwesomeSolid.TIMES, 12, Color.RED);
    private final FontIcon warningIcon = FontIcon.of(FontAwesomeSolid.EXCLAMATION_TRIANGLE, 12, Color.ORANGE);

    // Line number (1-based) of the issue whose tooltip is currently displayed, or -1 if none.
    // Used to avoid rebuilding/re-showing the tooltip on every mouseMoved event while the
    // pointer remains over the same issue line.
    private int currentlyDisplayedLine = -1;

    // Line number (1-based) of the issue whose tooltip is scheduled to appear, or -1 if none.
    private int pendingLine = -1;

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
                ValidationIssue issue = getValidationIssueForPosition(e.getPoint());

                if (issue != null) {
                    int line = issue.getLineNumber();

                    // Pointer is still over the issue line whose tooltip is already showing:
                    // nothing to rebuild, just keep it visible.
                    if (line == currentlyDisplayedLine) {
                        stopTimer(hideTimer);
                        return;
                    }

                    // A show for this same line is already scheduled: let it fire, don't reschedule.
                    if (line == pendingLine && showTimer != null && showTimer.isRunning()) {
                        return;
                    }

                    // Moved onto a different issue line: hide anything currently shown, then
                    // schedule a single build+show after a short dwell delay. If the pointer
                    // moves on before the delay elapses, the show is cancelled and never builds.
                    stopTimer(hideTimer);
                    if (currentlyDisplayedLine != -1) {
                        hideCustomTooltip();
                    }
                    scheduleShow(issue, e.getLocationOnScreen());
                } else {
                    // Moved off an issue line: cancel any pending show, and hide after a short
                    // delay to prevent flickering. Guard against restarting on every event.
                    stopTimer(showTimer);
                    pendingLine = -1;
                    if (currentlyDisplayedLine != -1 && (hideTimer == null || !hideTimer.isRunning())) {
                        hideTimer = new Timer(100, evt -> hideCustomTooltip());
                        hideTimer.setRepeats(false);
                        hideTimer.start();
                    }
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

    /**
     * Schedule the tooltip for the given issue to appear after the dwell delay.
     * Cancels any previously scheduled show.
     */
    private void scheduleShow(ValidationIssue issue, Point screenLocation) {
        stopTimer(showTimer);
        pendingLine = issue.getLineNumber();
        showTimer = new Timer(SHOW_DELAY_MS, evt -> {
            showCustomTooltip(issue, screenLocation);
            currentlyDisplayedLine = pendingLine;
            pendingLine = -1;
        });
        showTimer.setRepeats(false);
        showTimer.start();
    }

    private static void stopTimer(Timer timer) {
        if (timer != null && timer.isRunning()) {
            timer.stop();
        }
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
        currentlyDisplayedLine = -1;
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
            icon = errorIcon;
            severityColor = Color.RED;
        } else {
            icon = warningIcon;
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
        stopTimer(hideTimer);
        stopTimer(showTimer);
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
        }
    }
}