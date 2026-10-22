package com.kalix.ide.windows;

import com.kalix.ide.diff.DiffWindow;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * Compares a run's mass balance report with a reference report and shows the result:
 * a success dialog if they match, a plain-text diff window if they do not.
 *
 * <p>The comparison is of the report text, ignoring line endings and leading and
 * trailing whitespace, as {@code kalix simulate -v} does. Nothing here knows the
 * report's format.
 */
public final class MassBalanceComparison {

    // A reference more than this many times the report's length is not compared. The
    // comparison and the diff run on the EDT, and the reference can be any file or
    // editor tab, a large data file included. The figure is arbitrary.
    private static final int MAX_REFERENCE_TO_REPORT_LENGTH = 10;

    private MassBalanceComparison() {
    }

    /**
     * Compares {@code report} with {@code referenceReport}: an info box over
     * {@code parent} if they match, a diff window if they do not. A reference far
     * larger than the report is refused, with a message. Call on the EDT.
     *
     * @param parent          the component the dialogs are shown over
     * @param runName         the run the report belongs to, for the diff window
     * @param report          the run's mass balance report
     * @param referenceReport the text to compare it with
     */
    public static void showResult(Component parent, String runName, String report,
                                  String referenceReport) {
        if (referenceReport.length() > (long) MAX_REFERENCE_TO_REPORT_LENGTH * report.length()) {
            JOptionPane.showMessageDialog(
                parent,
                "The reference is more than " + MAX_REFERENCE_TO_REPORT_LENGTH
                    + " times the size of this run's mass balance report, so it was not compared.",
                "Reference Too Large",
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Ignores leading and trailing whitespace, as `kalix simulate -v` does, and line
        // endings: a reference checked out on Windows has CRLF, the engine's report LF.
        String thisText = normaliseReport(report);
        String referenceText = normaliseReport(referenceReport);
        if (thisText.equals(referenceText)) {
            JOptionPane.showMessageDialog(
                parent,
                "Mass balance validation successful: the run's report matches the reference.",
                "Validation Successful",
                JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }
        String title = "Changes: " + runName + " vs Reference Mass Balance";
        DiffWindow.ofPlainText(thisText, referenceText, title, "Reference Mass Balance", runName);
    }

    private static String normaliseReport(String report) {
        return report.replaceAll("\\R", "\n").strip();
    }
}
