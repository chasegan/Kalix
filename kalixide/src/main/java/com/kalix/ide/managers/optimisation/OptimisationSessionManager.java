package com.kalix.ide.managers.optimisation;

import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.managers.StdioTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages optimisation session lifecycle operations.
 * Handles creation, execution, termination, and tracking of optimisation sessions.
 */
public class OptimisationSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisationSessionManager.class);

    // Core dependencies
    private final StdioTaskManager stdioTaskManager;
    private final Supplier<File> workingDirectorySupplier;
    private final Supplier<File> projectDirectorySupplier;
    private final Supplier<String> modelTextSupplier;

    // Session tracking
    private final Map<String, String> sessionToOptName = new HashMap<>();
    /**
     * Per-session OptimisationInfo. Replaces an older `sessionToTreeNode` map that
     * held an orphan tree node never added to the tree model. The displayed tree node
     * lives in {@code OptimisationTreeManager}; this map exists only for callers that
     * need the OptimisationInfo by sessionKey (e.g. {@link #getOptimisationInfo}).
     */
    private final Map<String, OptimisationInfo> sessionToOptInfo = new HashMap<>();
    private final Map<String, OptimisationStatus> lastKnownStatus = new HashMap<>();
    private final Map<String, OptimisationResult> optimisationResults = new HashMap<>();

    // Callbacks
    private Consumer<String> statusUpdater;
    private Consumer<OptimisationInfo> onOptimisationCreated;
    private Consumer<String> onSessionStarted;
    private Consumer<String> onSessionCompleted;
    private Consumer<String> onErrorOccurred;

    // Configuration
    private int optCounter = 1;

    /**
     * Creates a new OptimisationSessionManager.
     *
     * @param stdioTaskManager         The STDIO task manager
     * @param workingDirectorySupplier Supplier for the working directory
     * @param projectDirectorySupplier Supplier for the project directory
     * @param modelTextSupplier        Supplier for the model text
     */
    public OptimisationSessionManager(StdioTaskManager stdioTaskManager,
                                      Supplier<File> workingDirectorySupplier,
                                      Supplier<File> projectDirectorySupplier,
                                      Supplier<String> modelTextSupplier) {
        this.stdioTaskManager = stdioTaskManager;
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.projectDirectorySupplier = projectDirectorySupplier;
        this.modelTextSupplier = modelTextSupplier;

        // Track session death for our optimisations. Without this, a crashed or
        // terminated kalixcli was invisible here: the tree kept rendering the frozen
        // program state ("Optimising") forever. RunManager has always had the
        // equivalent listener - this closes the gap on the optimisation side.
        stdioTaskManager.getSessionManager().addSessionEventListener(event -> {
            String sessionKey = event.getSessionKey();
            if (!sessionToOptInfo.containsKey(sessionKey)) {
                return;
            }
            SessionManager.SessionState state = event.getNewState();
            if (state == SessionManager.SessionState.TERMINATED) {
                lastKnownStatus.put(sessionKey, OptimisationStatus.STOPPED);
            } else if (state == SessionManager.SessionState.ERROR) {
                lastKnownStatus.put(sessionKey, OptimisationStatus.ERROR);
            } else {
                return;
            }
            if (onSessionCompleted != null) {
                onSessionCompleted.accept(sessionKey);
            }
        });
    }

    /**
     * Creates a new optimisation session.
     *
     * @param configText The configuration INI text
     * @param configModel The structured GUI form state to store on the new optimisation
     * @param progressCallback Callback for progress updates (sessionKey, progressInfo)
     * @param parametersCallback Callback for parameters (sessionKey, parameters)
     * @param resultCallback Callback for results (sessionKey, result)
     */
    public void createOptimisation(String configText,
                                  OptimisationConfigModel configModel,
                                  java.util.function.BiConsumer<String, ProgressParser.ProgressInfo> progressCallback,
                                  java.util.function.BiConsumer<String, List<String>> parametersCallback,
                                  java.util.function.BiConsumer<String, String> resultCallback) {
        String modelText = modelTextSupplier != null ? modelTextSupplier.get() : null;
        if (modelText == null || modelText.trim().isEmpty()) {
            handleError("No model loaded. Please load a model first.");
            return;
        }

        // Get current working directory if available
        File workingDir = workingDirectorySupplier != null ? workingDirectorySupplier.get() : null;
        String currentFolder = workingDir != null ? workingDir.getAbsolutePath() : null;

        File projectDir = projectDirectorySupplier != null ? projectDirectorySupplier.get() : null;
        String projectFolder = projectDir != null ? projectDir.getAbsolutePath() : null;

        Optional<com.kalix.ide.cli.KalixCliLocator.CliLocation> cliLocationOpt =
            com.kalix.ide.cli.KalixCliLocator.findKalixCliWithPreferences(currentFolder, projectFolder);

        if (cliLocationOpt.isEmpty()) {
            handleError("kalix not found. Please configure the path in Preferences.");
            return;
        }

        Path cliPath = cliLocationOpt.get().getPath();

        try {
            // Generate optimisation name
            String optName = generateOptimisationName();

            // Configure session - use "new-session" subcommand for JSON communication
            SessionManager.SessionConfig config = new SessionManager.SessionConfig("new-session");

            if (workingDir != null) {
                config.workingDirectory(workingDir.toPath());
            }

            // Start session
            stdioTaskManager.getSessionManager().startSession(cliPath, config)
                .thenAccept(sessionKey -> {
                    // Create optimisation program with wrapped callbacks that include sessionKey
                    OptimisationProgram program = new OptimisationProgram(
                        sessionKey,
                        stdioTaskManager.getSessionManager(),
                        statusUpdater != null ? msg -> statusUpdater.accept(msg) : msg -> {},
                        progressCallback != null ? progress -> progressCallback.accept(sessionKey, progress) : progress -> {},
                        parametersCallback != null ? params -> parametersCallback.accept(sessionKey, params) : params -> {},
                        resultCallback != null ? result -> resultCallback.accept(sessionKey, result) : result -> {}
                    );

                    // Get the session
                    SessionManager.KalixSession session = stdioTaskManager.getSessionManager()
                        .getActiveSessions().get(sessionKey);

                    if (session == null) {
                        handleError("Failed to get session after creation");
                        return;
                    }

                    // Create optimisation info
                    OptimisationInfo optInfo = new OptimisationInfo(optName, session);
                    optInfo.setConfigSnapshot(configText);
                    optInfo.setConfigModel(configModel);

                    // Create result for tracking
                    OptimisationResult result = new OptimisationResult();
                    optInfo.setResult(result);

                    // Add to tracking maps
                    sessionToOptName.put(sessionKey, optName);
                    sessionToOptInfo.put(sessionKey, optInfo);
                    optimisationResults.put(sessionKey, result);

                    // Notify callback
                    if (onOptimisationCreated != null) {
                        onOptimisationCreated.accept(optInfo);
                    }

                    // Load the model: initialize stores the model text, then
                    // installProgram attaches the program and replays the initial
                    // ready signal if the CLI's startup rdy already arrived (the
                    // race that used to wedge sessions at "Waiting for CLI").
                    program.initialize(modelText);
                    stdioTaskManager.getSessionManager().installProgram(sessionKey, program);

                    logger.info("Created optimisation: {} ({})", optName, sessionKey);

                    if (onSessionStarted != null) {
                        onSessionStarted.accept(sessionKey);
                    }
                })
                .exceptionally(throwable -> {
                    handleError("Failed to start session: " + throwable.getMessage());
                    return null;
                });

        } catch (Exception e) {
            handleError("Error creating optimisation: " + e.getMessage());
            logger.error("Failed to create optimisation", e);
        }
    }

    /**
     * Runs an optimisation with configuration from the specified source.
     *
     * @param optInfo The optimisation info
     * @param configText The configuration text to use
     * @param configValidator Validator for the configuration
     * @return true if started successfully, false otherwise
     */
    public boolean runOptimisation(OptimisationInfo optInfo, String configText,
                                  java.util.function.Predicate<String> configValidator) {
        if (optInfo == null || optInfo.getSession() == null) {
            handleError("No optimisation selected");
            return false;
        }

        SessionManager.KalixSession session = optInfo.getSession();

        // Check if already running
        if (optInfo.hasStartedRunning()) {
            String message = String.format("Optimisation '%s' is already running",
                optInfo.getName());
            if (statusUpdater != null) {
                statusUpdater.accept(message);
            }
            logger.warn(message);
            return false;
        }

        // Validate configuration
        if (configValidator != null && !configValidator.test(configText)) {
            handleError("Invalid configuration. Please check the configuration.");
            return false;
        }

        try {
            // Get the optimisation program from the session
            Object program = session.getActiveProgram();
            if (!(program instanceof OptimisationProgram optProgram)) {
                handleError("Session does not have an OptimisationProgram");
                return false;
            }

            // Update config snapshot
            String sessionKey = session.getSessionKey();
            optInfo.setConfigSnapshot(configText);

            // Start optimisation with config text (not file path!)
            // Note: runOptimisation is void and manages its own async operations
            optProgram.runOptimisation(configText);

            // The completion will be handled through the resultCallback passed during creation

            // Update status
            optInfo.setHasStartedRunning(true);
            if (statusUpdater != null) {
                String optName = sessionToOptName.get(sessionKey);
                if (optName != null) {
                    statusUpdater.accept("Starting optimisation: " + optName);
                }
            }

            logger.info("Started optimisation: {}", sessionKey);
            return true;

        } catch (Exception e) {
            handleError("Error starting optimisation: " + e.getMessage());
            logger.error("Failed to start optimisation", e);
            return false;
        }
    }

    /**
     * Stops an optimisation session.
     *
     * @param sessionKey The session key
     */
    public void stopOptimisation(String sessionKey) {
        if (sessionKey == null) {
            return;
        }

        try {
            // Cooperative stop (protocol stp), not a force-kill: the optimiser breaks at
            // its next generation and returns the best solution found so far, which
            // arrives as a normal result. The session stays alive.
            stdioTaskManager.stopSession(sessionKey);

            if (statusUpdater != null) {
                String optName = sessionToOptName.get(sessionKey);
                if (optName != null) {
                    statusUpdater.accept("Stop requested: " + optName);
                }
            }

            logger.info("Stop requested for optimisation: {}", sessionKey);
        } catch (Exception e) {
            logger.error("Failed to stop optimisation: {}", sessionKey, e);
        }
    }

    /**
     * Removes an optimisation session and its tracking.
     *
     * @param sessionKey The session key
     * @param isActive Whether the session is currently active
     */
    public void removeOptimisation(String sessionKey) {
        if (sessionKey == null) {
            return;
        }

        // Always release the CLI process. Every "New" spawns a real kalixcli session
        // immediately, so even a never-run (CONFIGURING) optimisation owns a live
        // process - the old status==RUNNING guard leaked those until application exit.
        SessionManager.KalixSession session =
            stdioTaskManager.getSessionManager().getActiveSessions().get(sessionKey);
        if (session != null) {
            if (session.isActive()) {
                stdioTaskManager.terminateSession(sessionKey)
                    .thenCompose(v -> stdioTaskManager.removeSession(sessionKey));
            } else {
                stdioTaskManager.removeSession(sessionKey);
            }
        }

        // Get name before removing
        String optName = sessionToOptName.get(sessionKey);

        // Remove from tracking maps - all of them
        sessionToOptName.remove(sessionKey);
        sessionToOptInfo.remove(sessionKey);
        lastKnownStatus.remove(sessionKey);
        optimisationResults.remove(sessionKey);

        if (statusUpdater != null && optName != null) {
            statusUpdater.accept("Removed: " + optName);
        }

        logger.info("Removed optimisation: {} ({})", optName, sessionKey);
    }

    /**
     * Updates the name of an optimisation.
     *
     * @param sessionKey The session key
     * @param newName The new name
     * @return true if renamed successfully, false otherwise
     */
    public boolean renameOptimisation(String sessionKey, String newName) {
        if (sessionKey == null || newName == null) {
            return false;
        }

        String trimmedName = newName.trim();
        if (trimmedName.isEmpty()) {
            return false;
        }

        // Check for duplicate names
        boolean isDuplicate = sessionToOptName.values().stream()
            .anyMatch(name -> name.equals(trimmedName));

        if (isDuplicate) {
            handleError("An optimisation with this name already exists");
            return false;
        }

        String oldName = sessionToOptName.get(sessionKey);
        sessionToOptName.put(sessionKey, trimmedName);

        if (statusUpdater != null) {
            statusUpdater.accept(String.format("Renamed '%s' to '%s'", oldName, trimmedName));
        }

        logger.info("Renamed optimisation: {} -> {}", oldName, trimmedName);
        return true;
    }

    // getTreeNode removed — there is no orphan tree node any more. Callers that
    // need the displayed node should ask OptimisationTreeManager.getNodeForSession.

    /**
     * Gets the optimisation name for a session.
     *
     * @param sessionKey The session key
     * @return The optimisation name, or null if not found
     */
    public String getOptimisationName(String sessionKey) {
        return sessionToOptName.get(sessionKey);
    }

    /**
     * Gets the optimisation result for a session.
     *
     * @param sessionKey The session key
     * @return The optimisation result, or null if not found
     */
    public OptimisationResult getOptimisationResult(String sessionKey) {
        return optimisationResults.get(sessionKey);
    }

    /**
     * Gets the last known status for a session.
     *
     * @param sessionKey The session key
     * @return The status, or null if not found
     */
    public OptimisationStatus getLastKnownStatus(String sessionKey) {
        return lastKnownStatus.get(sessionKey);
    }

    /**
     * Updates the last known status for a session.
     *
     * @param sessionKey The session key
     * @param status The new status
     */
    public void updateStatus(String sessionKey, OptimisationStatus status) {
        if (sessionKey != null && status != null) {
            lastKnownStatus.put(sessionKey, status);
        }
    }

    /**
     * Gets the OptimisationInfo for a session.
     *
     * @param sessionKey The session key
     * @return The OptimisationInfo, or null if not found
     */
    public OptimisationInfo getOptimisationInfo(String sessionKey) {
        return sessionToOptInfo.get(sessionKey);
    }

    /**
     * Checks if a session exists.
     *
     * @param sessionKey The session key
     * @return true if the session exists, false otherwise
     */
    public boolean hasSession(String sessionKey) {
        return sessionToOptName.containsKey(sessionKey);
    }

    /**
     * Gets all session keys.
     *
     * @return Map of session keys to optimisation names
     */
    public Map<String, String> getAllSessions() {
        return new HashMap<>(sessionToOptName);
    }

    /**
     * Generates a unique optimisation name.
     *
     * @return The generated name
     */
    private String generateOptimisationName() {
        String baseName = "Opt " + optCounter++;

        // Check for duplicates and adjust if needed
        while (sessionToOptName.containsValue(baseName)) {
            baseName = "Opt " + optCounter++;
        }

        return baseName;
    }

    /**
     * Handles error messages.
     *
     * @param errorMessage The error message
     */
    private void handleError(String errorMessage) {
        if (onErrorOccurred != null) {
            onErrorOccurred.accept(errorMessage);
        }

        if (statusUpdater != null) {
            statusUpdater.accept("Error: " + errorMessage);
        }

        logger.error("Optimisation error: {}", errorMessage);
    }

    // Setters for callbacks
    public void setStatusUpdater(Consumer<String> statusUpdater) {
        this.statusUpdater = statusUpdater;
    }

    public void setOnOptimisationCreated(Consumer<OptimisationInfo> callback) {
        this.onOptimisationCreated = callback;
    }

    public void setOnSessionStarted(Consumer<String> callback) {
        this.onSessionStarted = callback;
    }

    public void setOnSessionCompleted(Consumer<String> callback) {
        this.onSessionCompleted = callback;
    }

    public void setOnErrorOccurred(Consumer<String> callback) {
        this.onErrorOccurred = callback;
    }

}