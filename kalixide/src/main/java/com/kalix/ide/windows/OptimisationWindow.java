package com.kalix.ide.windows;

import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.KalixCliLocator;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.components.StatusProgressBar;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Window for configuring and running model optimisation tasks.
 * Provides a simple interface to:
 * - Edit optimisation configuration (INI format)
 * - Run optimisation via kalixcli
 * - Display results
 */
public class OptimisationWindow extends JFrame {

    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final StatusProgressBar progressBar;
    private final Supplier<File> workingDirectorySupplier;
    private final Supplier<String> modelTextSupplier;

    // UI Components
    private RSyntaxTextArea configEditor;
    private JTextArea resultArea;
    private JButton runButton;
    private JButton terminateButton;
    private JLabel statusLabel;

    // Session tracking
    private String currentSessionKey;
    private OptimisationProgram currentProgram;

    private static OptimisationWindow instance;

    /**
     * Private constructor for singleton pattern.
     */
    private OptimisationWindow(JFrame parentFrame,
                               StdioTaskManager stdioTaskManager,
                               Consumer<String> statusUpdater,
                               StatusProgressBar progressBar,
                               Supplier<File> workingDirectorySupplier,
                               Supplier<String> modelTextSupplier) {
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.progressBar = progressBar;
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.modelTextSupplier = modelTextSupplier;

        setupWindow(parentFrame);
        initializeComponents();
        setupLayout();
        setupWindowListeners();
    }

    /**
     * Shows the Optimisation window using singleton pattern.
     */
    public static void showOptimisationWindow(JFrame parentFrame,
                                              StdioTaskManager stdioTaskManager,
                                              Consumer<String> statusUpdater,
                                              StatusProgressBar progressBar,
                                              Supplier<File> workingDirectorySupplier,
                                              Supplier<String> modelTextSupplier) {
        if (instance == null) {
            instance = new OptimisationWindow(parentFrame, stdioTaskManager,
                statusUpdater, progressBar, workingDirectorySupplier, modelTextSupplier);
        }

        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }

    private void setupWindow(JFrame parentFrame) {
        setTitle("Model Optimisation");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setSize(900, 700);

        if (parentFrame != null) {
            setLocationRelativeTo(parentFrame);
            Point parentLocation = parentFrame.getLocation();
            setLocation(parentLocation.x + 30, parentLocation.y + 30);

            if (parentFrame.getIconImage() != null) {
                setIconImage(parentFrame.getIconImage());
            }
        } else {
            setLocationRelativeTo(null);
        }
    }

    private void initializeComponents() {
        // Configuration editor with INI syntax highlighting
        configEditor = new RSyntaxTextArea(15, 60);
        configEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_INI);
        configEditor.setCodeFoldingEnabled(true);
        configEditor.setAntiAliasingEnabled(true);
        configEditor.setTabSize(4);
        configEditor.setTabsEmulated(true);

        // Set default template based on kalixcli example
        configEditor.setText("""
                [General]
                observed_data_by_name = data.csv.ObsFlow
                simulated_series = node.mynode.ds_1
                objective_function = NSE
                output_file = optimisation_results.txt

                [Algorithm]
                algorithm = DE
                population_size = 50
                termination_evaluations = 5000
                de_f = 0.8
                de_cr = 0.9
                n_threads = 4

                [Parameters]
                # Define parameters to optimise using lin_range or log_range
                # node.mynode.x1 = lin_range(g(1), 10, 2000)
                # node.mynode.x2 = lin_range(g(2), -8, 6)

                [Reporting]
                report_frequency = 10
                verbose = true
                """);

        // Result text area
        resultArea = new JTextArea(10, 60);
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setText("Results will appear here after optimisation completes...");

        // Buttons
        runButton = new JButton("Run Optimisation");
        runButton.addActionListener(e -> runOptimisation());

        terminateButton = new JButton("Terminate");
        terminateButton.setEnabled(false);
        terminateButton.addActionListener(e -> terminateOptimisation());

        // Status label
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
    }

    private void setupLayout() {
        setLayout(new BorderLayout(10, 10));

        // Top panel: Configuration editor
        JPanel configPanel = new JPanel(new BorderLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("Optimisation Configuration"));
        RTextScrollPane configScrollPane = new RTextScrollPane(configEditor);
        configPanel.add(configScrollPane, BorderLayout.CENTER);

        // Middle panel: Control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        buttonPanel.add(runButton);
        buttonPanel.add(terminateButton);
        buttonPanel.add(statusLabel);

        // Bottom panel: Results display
        JPanel resultPanel = new JPanel(new BorderLayout());
        resultPanel.setBorder(BorderFactory.createTitledBorder("Results"));
        JScrollPane resultScrollPane = new JScrollPane(resultArea);
        resultPanel.add(resultScrollPane, BorderLayout.CENTER);

        // Create split pane for config and results
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setTopComponent(configPanel);
        splitPane.setBottomComponent(resultPanel);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.6);

        // Assemble main layout
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(splitPane, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        add(mainPanel, BorderLayout.CENTER);
    }

    private void setupWindowListeners() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Clean up session if still running
                if (currentSessionKey != null && currentProgram != null && !currentProgram.isCompleted()) {
                    int result = JOptionPane.showConfirmDialog(
                        OptimisationWindow.this,
                        "Optimisation is still running. Terminate before closing?",
                        "Optimisation Running",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                    );

                    if (result == JOptionPane.YES_OPTION) {
                        terminateOptimisation();
                    }
                }
            }

            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
            }
        });
    }

    /**
     * Runs the optimisation with the current configuration.
     */
    private void runOptimisation() {
        String configText = configEditor.getText();

        if (configText == null || configText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Configuration cannot be empty",
                "Invalid Configuration",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Get model from the main editor
        String modelText = modelTextSupplier != null ? modelTextSupplier.get() : null;
        if (modelText == null || modelText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No model loaded in the editor.\nPlease load a model first.",
                "No Model",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Disable run button, enable terminate
        runButton.setEnabled(false);
        terminateButton.setEnabled(true);
        statusLabel.setText("Starting...");
        resultArea.setText("Starting optimisation...\n");

        // Make modelText effectively final for lambda
        final String finalModelText = modelText;

        // Start new session
        SwingUtilities.invokeLater(() -> {
            try {
                // Locate kalixcli
                Optional<KalixCliLocator.CliLocation> cliLocation = KalixCliLocator.findKalixCliWithPreferences();
                if (cliLocation.isEmpty()) {
                    handleError("kalixcli not found");
                    return;
                }

                // Configure session (use same "new-session" type as for simulations)
                SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session");

                // Set working directory if available
                File workingDir = workingDirectorySupplier.get();
                if (workingDir != null) {
                    config.workingDirectory(workingDir.toPath());
                }

                // Start session
                stdioTaskManager.getSessionManager().startSession(cliLocation.get().getPath(), config)
                    .thenAccept(sessionKey -> {
                        currentSessionKey = sessionKey;

                        // Create optimisation program
                        currentProgram = new OptimisationProgram(
                            sessionKey,
                            stdioTaskManager.getSessionManager(),
                            this::updateStatus,
                            this::updateProgress,
                            this::displayResult
                        );

                        // Register program with session for automatic message routing
                        stdioTaskManager.getSessionManager().getSession(sessionKey).ifPresent(session -> {
                            session.setActiveProgram(currentProgram);
                        });

                        // Start optimisation with both config and model
                        currentProgram.start(configText, finalModelText);
                    })
                    .exceptionally(throwable -> {
                        handleError("Failed to start session: " + throwable.getMessage());
                        return null;
                    });

            } catch (Exception e) {
                handleError("Error starting optimisation: " + e.getMessage());
            }
        });
    }

    /**
     * Terminates the current optimisation.
     */
    private void terminateOptimisation() {
        if (currentSessionKey != null) {
            stdioTaskManager.terminateSession(currentSessionKey)
                .thenRun(() -> SwingUtilities.invokeLater(() -> {
                    updateStatus("Optimisation terminated");
                    resetUI();
                }))
                .exceptionally(throwable -> {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus("Failed to terminate: " + throwable.getMessage());
                    });
                    return null;
                });
        }
    }

    /**
     * Updates the status label and main status bar.
     */
    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
            if (statusUpdater != null) {
                statusUpdater.accept(message);
            }
        });
    }

    /**
     * Updates progress during optimisation.
     */
    private void updateProgress(ProgressParser.ProgressInfo progressInfo) {
        SwingUtilities.invokeLater(() -> {
            if (progressBar != null) {
                progressBar.setProgressPercentage(progressInfo.getPercentage());
                progressBar.setProgressText(progressInfo.getDescription());
            }
            resultArea.append(String.format("Progress: %.0f%% - %s\n",
                progressInfo.getPercentage(), progressInfo.getDescription()));
        });
    }

    /**
     * Displays the final optimisation result.
     */
    private void displayResult(String result) {
        SwingUtilities.invokeLater(() -> {
            resultArea.append("\n=== OPTIMISATION RESULT ===\n");
            resultArea.append(result);
            resultArea.append("\n=========================\n");
            resetUI();
        });
    }

    /**
     * Handles errors during optimisation.
     */
    private void handleError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Error");
            resultArea.append("\nERROR: " + errorMessage + "\n");
            JOptionPane.showMessageDialog(this,
                errorMessage,
                "Optimisation Error",
                JOptionPane.ERROR_MESSAGE);
            resetUI();
        });
    }

    /**
     * Resets UI to ready state.
     */
    private void resetUI() {
        runButton.setEnabled(true);
        terminateButton.setEnabled(false);
        currentSessionKey = null;
        currentProgram = null;
        if (progressBar != null) {
            progressBar.reset();
        }
    }
}
