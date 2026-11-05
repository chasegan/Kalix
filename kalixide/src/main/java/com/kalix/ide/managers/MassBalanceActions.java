package com.kalix.ide.managers;

import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.utils.StatusReporter;
import com.kalix.ide.windows.MassBalanceComparison;
import com.kalix.ide.windows.MassBalanceReportWindow;
import com.kalix.ide.windows.RunManager;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The project tree's mass balance items: run a model file from disk, in its own folder,
 * then act on the run's mass balance report. Each run is an ordinary run, shown in the
 * Run Manager like any other.
 */
public class MassBalanceActions {

    private final JFrame parentFrame;
    private final StdioTaskManager stdioTaskManager;
    private final StatusReporter statusReporter;

    public MassBalanceActions(JFrame parentFrame, StdioTaskManager stdioTaskManager,
                              StatusReporter statusReporter) {
        this.parentFrame = parentFrame;
        this.stdioTaskManager = stdioTaskManager;
        this.statusReporter = statusReporter;
    }

    /** Runs {@code model}, then opens a window on its mass balance report. Call on the EDT. */
    public void showReport(File model) {
        runThenGetReport(model, (runName, report) ->
            new MassBalanceReportWindow(runName, report, model::getParentFile,
                (window, reference) -> MassBalanceComparison.showResult(window, runName, report, reference)
            ).setVisible(true));
    }

    /**
     * Runs {@code model}, then compares its mass balance report with the text of
     * {@code reference}. Call on the EDT.
     */
    public void validate(File model, File reference) {
        // Read now, so an unreadable reference is reported before a run is started.
        String referenceReport = readOrReport(reference, "reference");
        if (referenceReport == null) return;

        runThenGetReport(model, (runName, report) ->
            MassBalanceComparison.showResult(parentFrame, runName, report, referenceReport));
    }

    /**
     * Runs {@code model} from disk in its own folder and hands the run's name and mass
     * balance report to {@code onReport} on the EDT. A run that fails or is stopped has
     * already been reported by the run itself, so {@code onReport} is simply not called.
     */
    private void runThenGetReport(File model, BiConsumer<String, String> onReport) {
        String modelText = readOrReport(model, "model");
        if (modelText == null) return;

        statusReporter.accept("Running " + model.getName() + " for its mass balance report...");

        // The run can end before runModelFromMemory's future hands back the session key,
        // so the outcome waits on the key rather than the other way round.
        CompletableFuture<String> sessionKey = new CompletableFuture<>();
        stdioTaskManager.runModelFromMemory(modelText, model.getParentFile(), outcome -> {
                if (outcome == RunModelProgram.Outcome.SUCCEEDED) {
                    sessionKey.thenAccept(key -> requestReport(key, model, onReport));
                }
            })
            .thenAccept(key -> {
                sessionKey.complete(key);
                SwingUtilities.invokeLater(() -> {
                    if (!RunManager.isWindowOpen()) {
                        RunManager.showRunManager(parentFrame, stdioTaskManager, statusReporter);
                    }
                });
            }); // a failure to start is reported by StdioTaskManager
    }

    private void requestReport(String sessionKey, File model, BiConsumer<String, String> onReport) {
        // The future completes on the session's reader thread.
        stdioTaskManager.requestMassBalanceReport(sessionKey).whenComplete(
            (report, throwable) -> SwingUtilities.invokeLater(() -> {
                if (throwable != null) {
                    // The dialog logs the error, so the status line does not.
                    statusReporter.accept("Failed to get mass balance report: " + throwable.getMessage());
                    DialogUtils.showError(parentFrame,
                        "Failed to get mass balance report: " + throwable.getMessage(),
                        "Mass Balance Report Error");
                    return;
                }
                String runName = RunManager.getRunNameForSession(sessionKey);
                onReport.accept(runName != null ? runName : model.getName(), report);
            }));
    }

    /** The file's text, or null after telling the user it could not be read. */
    private String readOrReport(File file, String what) {
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            DialogUtils.showError(parentFrame,
                "Failed to read " + what + " " + file.getName() + ": " + e.getMessage(),
                "Mass Balance Error");
            return null;
        }
    }
}
