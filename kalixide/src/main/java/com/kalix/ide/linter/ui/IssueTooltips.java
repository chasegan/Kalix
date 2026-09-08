package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationIssue;
import com.kalix.ide.linter.model.ValidationRule;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hover tip for a line carrying validation issues: one row per issue, a
 * severity glyph, the message, and a severity tag. Content only — the tip's
 * window, timing and dismissal belong to {@link HoverTipSupplier}.
 */
public final class IssueTooltips implements HoverTipSupplier.Source {

    private static final String ERROR_COLOUR = "#d32f2f";
    private static final String WARNING_COLOUR = "#e08a00";

    private final ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine;

    public IssueTooltips(ConcurrentHashMap<Integer, List<ValidationIssue>> issuesByLine) {
        this.issuesByLine = issuesByLine;
    }

    @Override
    public String tipAt(int line, int offset) {
        List<ValidationIssue> issues = issuesByLine.get(line);
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        return html(issues);
    }

    /** The tip HTML for a line's issues, stacked one per row. Package-private for tests. */
    static String html(List<ValidationIssue> issues) {
        StringBuilder html = new StringBuilder("<html>");
        boolean first = true;
        for (ValidationIssue issue : issues) {
            if (!first) {
                html.append("<br>");
            }
            first = false;
            boolean error = issue.getSeverity() == ValidationRule.Severity.ERROR;
            String colour = error ? ERROR_COLOUR : WARNING_COLOUR;
            String glyph = error ? "&#x2716;" : "&#x26A0;";
            html.append("<font color='").append(colour).append("'><b>").append(glyph).append("</b></font>&nbsp; ")
                .append(HoverTipSupplier.escapeHtml(issue.getMessage()))
                .append(" <font color='").append(colour).append("' size='-2'><b>[")
                .append(issue.getSeverity()).append("]</b></font>");
        }
        return html.append("</html>").toString();
    }
}
