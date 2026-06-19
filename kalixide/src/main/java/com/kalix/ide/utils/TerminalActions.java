package com.kalix.ide.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * UI-facing entry point for launching a terminal from anywhere in the IDE.
 *
 * <p>This is the reusable wrapper shared by every call site (the {@code System → Terminal}
 * menu, and later the file-tree context menu): it runs {@link TerminalLauncher} off the
 * Swing event dispatch thread so the UI never blocks, then reports a status message or
 * shows an error dialog back on the EDT. The actual platform logic lives in the
 * Swing-free {@link TerminalLauncher}.
 */
public final class TerminalActions {

    private static final Logger logger = LoggerFactory.getLogger(TerminalActions.class);

    private TerminalActions() {
        // Utility class - prevent instantiation
    }

    /**
     * Launches a terminal at the folder for {@code pathOrFolder}, off the EDT.
     *
     * <p>The path may be a file or a folder (a file resolves to its containing folder),
     * or null to open the user's home directory — resolution is handled by
     * {@link TerminalLauncher#resolveFolder(File)}, so the reported path always matches
     * where the terminal actually opened.
     *
     * @param parent       component to anchor the error dialog to (may be null)
     * @param pathOrFolder file or directory indicating where to open the terminal, or null for home
     * @param status       callback for user-facing status messages, invoked on the EDT (may be null)
     */
    public static void launchAsync(Component parent, File pathOrFolder, Consumer<String> status) {
        new SwingWorker<File, Void>() {
            @Override
            protected File doInBackground() throws Exception {
                return TerminalLauncher.openTerminalAt(pathOrFolder);
            }

            @Override
            protected void done() {
                try {
                    File opened = get();
                    if (status != null) {
                        status.accept("Terminal opened at: " + opened.getAbsolutePath());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String message = "Failed to open terminal: " + cause.getMessage();
                    logger.error("Error opening terminal", cause);
                    if (status != null) {
                        status.accept(message);
                    }
                    JOptionPane.showMessageDialog(parent, message, "Terminal Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
