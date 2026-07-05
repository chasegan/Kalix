package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationRule;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a hover tooltip for validation issues: the dwell/positioning/lifecycle machinery lives in
 * {@link DwellTooltipSupport}; this class supplies the issue hit-test and the stacked-row content.
 * A line can carry several issues; the tooltip stacks one row per issue.
 */
public class LinterTooltipManager extends DwellTooltipSupport {

    private final ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine;

    // Cached severity icons, built once to avoid re-rendering the glyph on every tooltip build.
    private final FontIcon errorIcon = FontIcon.of(FontAwesomeSolid.TIMES, 12, Color.RED);
    private final FontIcon warningIcon = FontIcon.of(FontAwesomeSolid.EXCLAMATION_TRIANGLE, 12, Color.ORANGE);

    public LinterTooltipManager(RSyntaxTextArea textArea, ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine) {
        super(textArea, BoxLayout.Y_AXIS, new Color(255, 255, 225)); // Light yellow background
        this.issuesByLine = issuesByLine;
    }

    /** A line carrying at least one validation issue; content is known at hover time. */
    private record IssueTarget(int line, List<ValidationIssue> issues) implements Target {
    }

    @Override
    protected Target probe(Point point) {
        List<ValidationIssue> issues = getValidationIssuesForPosition(point);
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        return new IssueTarget(issues.get(0).getLineNumber(), issues);
    }

    @Override
    protected boolean populate(Target target) {
        buildTooltipContent(((IssueTarget) target).issues());
        return true;
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
}
