package com.kalix.ide.utils;

import java.util.function.Consumer;

/**
 * Where components report progress to the user: {@link #accept} for ordinary status-bar
 * messages, {@link #error} for failures, which are also kept in the IDE's error log.
 * Still a {@code Consumer<String>}, so code that only reports status can take it as one.
 *
 * <p>What the log holds is errors only: every status message reported through
 * {@link #error}, and every error dialog, which {@link DialogUtils#showError} records.
 * Warnings, whether a warning dialog or a status message, are not logged. Each failure
 * is logged once: where a status line and a dialog report the same failure, the status
 * line uses {@link #accept} and the dialog does the logging.
 */
public interface StatusReporter extends Consumer<String> {

    /** Reports a failure: shown in the status bar and recorded in the error log. */
    void error(String message);

    /** Combines the two channels. */
    static StatusReporter of(Consumer<String> status, Consumer<String> error) {
        return new StatusReporter() {
            @Override
            public void accept(String message) {
                status.accept(message);
            }

            @Override
            public void error(String message) {
                error.accept(message);
            }
        };
    }

    /** For callers with no error log of their own: errors are shown like any other status. */
    static StatusReporter statusOnly(Consumer<String> status) {
        return of(status, status);
    }
}
