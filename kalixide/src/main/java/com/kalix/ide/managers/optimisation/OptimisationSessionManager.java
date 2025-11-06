package com.kalix.ide.managers.optimisation;

import com.kalix.ide.cli.OptimisationProgram;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.cli.ProgressParser;
import com.kalix.ide.managers.StdioTaskManager;
import com.kalix.ide.models.optimisation.OptimisationInfo;
import com.kalix.ide.models.optimisation.OptimisationResult;
import com.kalix.ide.models.optimisation.OptimisationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
    private final Supplier<String> modelTextSupplier;

    // Session tracking
    private final Map<String, String> sessionToOptName = new HashMap<>();
    private final Map<String, DefaultMutableTreeNode> sessionToTreeNode = new HashMap<>();
    private final Map<String, OptimisationStatus> lastKnownStatus = new HashMap<>();
    private final Map<String, OptimisationResult> optimisationResults = new HashMap<>();
    private final Map<String, String> sessionToModelText = new HashMap<>();
    private final Map<String, String> sessionToConfigText = new HashMap<>();
    private final Map<String, String> sessionToConfigFile = new HashMap<>();

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
     * @param stdioTaskManager The STDIO task manager
     * @param workingDirectorySupplier Supplier for the working directory
     * @param modelTextSupplier Supplier for the model text
     */
    public OptimisationSessionManager(StdioTaskManager stdioTaskManager,
                                     Supplier<File> workingDirectorySupplier,
                                     Supplier<String> modelTextSupplier) {
        this.stdioTaskManager = stdioTaskManager;
        this.workingDirectorySupplier = workingDirectorySupplier;
        this.modelTextSupplier = modelTextSupplier;
    }

    /**
     * Creates a new optimisation session.
     *
     * @param configText The configuration text
     * @param progressCallback Callback for progress updates
     * @param parametersCallback Callback for parameters
     * @param resultCallback Callback for results
     */
    public void createOptimisation(String configText,
                                  Consumer<ProgressParser.ProgressInfo> progressCallback,
                                  Consumer<List<String>> parametersCallback,
                                  Consumer<String> resultCallback) {
        String modelText = modelTextSupplier != null ? modelTextSupplier.get() : null;
        if (modelText == null || modelText.trim().isEmpty()) {
            handleError("No model loaded. Please load a model first.");
            return;
        }

        // Look for kalixcli in parent IDE working directory
        File cliLocation = workingDirectorySupplier != null ?
            new File(workingDirectorySupplier.get(), "kalixcli") : null;
        if (cliLocation == null || !cliLocation.exists()) {
            // Try fallback locations
            cliLocation = new File("./kalixcli");
            if (!cliLocation.exists()) {
                cliLocation = new File("../kalixcli");
            }
        }

        if (!cliLocation.exists()) {
            handleError("kalixcli not found");
            return;
        }

        try {
            // Generate optimisation name
            String optName = generateOptimisationName();

            // Create temp files for model and config
            File tempDir = Files.createTempDirectory("kalix_opt_").toFile();
            File modelFile = new File(tempDir, "model.ini");
            File configFile = new File(tempDir, "config.ini");

            Files.writeString(modelFile.toPath(), modelText);
            Files.writeString(configFile.toPath(), configText);

            // Configure session
            SessionManager.SessionConfig config = new SessionManager.SessionConfig("optimisation");

            // Set working directory if available
            File workingDir = workingDirectorySupplier.get();
            if (workingDir != null) {
                config.workingDirectory(workingDir.toPath());
            }

            // Start session
            stdioTaskManager.getSessionManager().startSession(cliLocation.toPath(), config)
                .thenAccept(sessionKey -> {
                    // Create optimisation program
                    OptimisationProgram program = new OptimisationProgram(
                        sessionKey,
                        stdioTaskManager.getSessionManager(),
                        statusUpdater != null ? msg -> statusUpdater.accept(msg) : msg -> {},
                        progressCallback,
                        parametersCallback,
                        resultCallback
                    );

                    // Get the session
                    SessionManager.KalixSession session = stdioTaskManager.getSessionManager()
                        .getActiveSessions().get(sessionKey);

                    if (session == null) {
                        handleError("Failed to get session after creation");
                        return;
                    }

                    // Set the active program
                    session.setActiveProgram(program);

                    // Store model and config for later use
                    sessionToModelText.put(sessionKey, modelText);
                    sessionToConfigText.put(sessionKey, configText);
                    sessionToConfigFile.put(sessionKey, configFile.getAbsolutePath());

                    // Create optimisation info
                    OptimisationInfo optInfo = new OptimisationInfo(optName, session);
                    optInfo.setConfigSnapshot(configText);

                    // Create result for tracking
                    OptimisationResult result = new OptimisationResult();
                    optInfo.setResult(result);

                    // Add to tracking maps
                    sessionToOptName.put(sessionKey, optName);
                    optimisationResults.put(sessionKey, result);

                    // Create tree node
                    DefaultMutableTreeNode optNode = new DefaultMutableTreeNode(optInfo);
                    sessionToTreeNode.put(sessionKey, optNode);

                    // Notify callback
                    if (onOptimisationCreated != null) {
                        onOptimisationCreated.accept(optInfo);
                    }

                    // Load the model
                    program.initialize(modelText);

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
     * Runs an optimisation.
     *
     * @param optInfo The optimisation info
     * @return true if started successfully, false otherwise
     */
    public boolean runOptimisation(OptimisationInfo optInfo) {
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

        try {
            // Get the optimisation program from the session
            Object program = session.getActiveProgram();
            if (!(program instanceof OptimisationProgram)) {
                handleError("Session does not have an OptimisationProgram");
                return false;
            }

            OptimisationProgram optProgram = (OptimisationProgram) program;

            // Get config file path from session
            String sessionKey = session.getSessionKey();
            String configFile = sessionToConfigFile.get(sessionKey);
            if (configFile == null) {
                handleError("Configuration file not found in session");
                return false;
            }

            // Start optimisation with config file
            // Note: runOptimisation is void and manages its own async operations
            optProgram.runOptimisation(configFile);

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
            stdioTaskManager.terminateSession(sessionKey);

            // Update status
            lastKnownStatus.put(sessionKey, OptimisationStatus.STOPPED);

            if (statusUpdater != null) {
                String optName = sessionToOptName.get(sessionKey);
                if (optName != null) {
                    statusUpdater.accept("Stopped: " + optName);
                }
            }

            logger.info("Stopped optimisation: {}", sessionKey);
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
    public void removeOptimisation(String sessionKey, boolean isActive) {
        if (sessionKey == null) {
            return;
        }

        // Terminate session if active
        if (isActive) {
            stopOptimisation(sessionKey);
        }

        // Get name before removing
        String optName = sessionToOptName.get(sessionKey);

        // Remove from tracking maps
        sessionToOptName.remove(sessionKey);
        sessionToTreeNode.remove(sessionKey);
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

    /**
     * Gets the tree node for a session.
     *
     * @param sessionKey The session key
     * @return The tree node, or null if not found
     */
    public DefaultMutableTreeNode getTreeNode(String sessionKey) {
        return sessionToTreeNode.get(sessionKey);
    }

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
        String baseName = "Optimisation " + optCounter++;

        // Check for duplicates and adjust if needed
        while (sessionToOptName.values().contains(baseName)) {
            baseName = "Optimisation " + optCounter++;
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

    /**
     * Sets the counter for generated optimisation names.
     *
     * @param counter The counter value
     */
    public void setOptimisationCounter(int counter) {
        this.optCounter = counter;
    }

    /**
     * Clears all sessions and tracking data.
     */
    public void clearAll() {
        sessionToOptName.clear();
        sessionToTreeNode.clear();
        lastKnownStatus.clear();
        optimisationResults.clear();
        sessionToModelText.clear();
        sessionToConfigText.clear();
        sessionToConfigFile.clear();
        optCounter = 1;
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