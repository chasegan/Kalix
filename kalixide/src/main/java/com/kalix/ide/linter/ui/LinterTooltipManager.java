package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationRule;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tooltip display for validation issues.
 * Handles mouse hover detection and shows custom tooltips with icons and formatting.
 * A line can carry several issues; the tooltip stacks one row per issue.
 */
public class LinterTooltipManager {

    private final RSyntaxTextArea textArea;
    private final ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine;

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

    // Listeners added to the shared text area, retained so dispose() can detach them
    // (the text area outlives this manager when the linter is re-initialised).
    private MouseMotionAdapter mouseMotionListener;
    private java.awt.event.MouseAdapter mouseListener;

    public LinterTooltipManager(RSyntaxTextArea textArea, ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine) {
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
        tooltipPanel.setLayout(new BoxLayout(tooltipPanel, BoxLayout.Y_AXIS));
        tooltipPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        tooltipPanel.setBackground(new Color(255, 255, 225)); // Light yellow background

        tooltipWindow.add(tooltipPanel);
    }

    private void setupMouseListeners() {
        mouseMotionListener = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                List<ValidationIssue> issues = getValidationIssuesForPosition(e.getPoint());

                if (issues != null && !issues.isEmpty()) {
                    int line = issues.get(0).getLineNumber();

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
                    scheduleShow(line, issues, e.getLocationOnScreen());
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
        };
        textArea.addMouseMotionListener(mouseMotionListener);

        mouseListener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hideCustomTooltip();
            }
        };
        textArea.addMouseListener(mouseListener);
    }

    /**
     * Schedule the tooltip for the given line's issues to appear after the dwell delay.
     * Cancels any previously scheduled show.
     */
    private void scheduleShow(int line, List<ValidationIssue> issues, Point screenLocation) {
        stopTimer(showTimer);
        pendingLine = line;
        showTimer = new Timer(SHOW_DELAY_MS, evt -> {
            showCustomTooltip(issues, screenLocation);
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

    private void showCustomTooltip(List<ValidationIssue> issues, Point screenLocation) {
        buildTooltipContent(issues);
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

    private List<ValidationIssue> getValidationIssuesForPosition(Point point) {
        try {
            int offset = textArea.viewToModel2D(point);
            int line = textArea.getLineOfOffset(offset) + 1; // Convert to 1-based line numbers
            return issuesByLine.get(line);
        } catch (BadLocationException e) {
            // Invalid position, no tooltip
            return null;
        }
    }

    private void buildTooltipContent(List<ValidationIssue> issues) {
        // Clear previous content, then stack one row per issue on the line
        tooltipPanel.removeAll();
        for (ValidationIssue issue : issues) {
            tooltipPanel.add(buildIssueRow(issue));
        }
    }

    private JPanel buildIssueRow(ValidationIssue issue) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

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
        row.add(iconLabel);

        // Create message label with proper formatting
        JLabel messageLabel = new JLabel(issue.getMessage());
        messageLabel.setForeground(Color.BLACK);
        messageLabel.setFont(messageLabel.getFont().deriveFont(12f));
        row.add(messageLabel);

        // Add severity indicator
        JLabel severityLabel = new JLabel(" [" + issue.getSeverity() + "]");
        severityLabel.setForeground(severityColor);
        severityLabel.setFont(severityLabel.getFont().deriveFont(Font.BOLD, 10f));
        row.add(severityLabel);

        return row;
    }

    /**
     * Clean up resources: stop timers, close the tooltip window, and detach the
     * mouse listeners added to the shared text area in the constructor.
     */
    public void dispose() {
        stopTimer(hideTimer);
        stopTimer(showTimer);
        if (tooltipWindow != null) {
            tooltipWindow.dispose();
        }
        if (mouseMotionListener != null) {
            textArea.removeMouseMotionListener(mouseMotionListener);
            mouseMotionListener = null;
        }
        if (mouseListener != null) {
            textArea.removeMouseListener(mouseListener);
            mouseListener = null;
        }
    }
}