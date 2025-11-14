package com.kalix.ide.managers;

import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.diff.DiffWindow;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.windows.MinimalEditorWindow;
import com.kalix.ide.windows.SessionManagerWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages context menus and related actions for RunManager.
 *
 * Responsibilities:
 * - Setting up run tree context menu (rename, remove, save, show model, diff, session manager)
 * - Setting up outputs tree context menu (expand all, collapse all)
 * - Handling all context menu actions
 * - Managing tree expansion/collapse operations
 *
 * Usage:
 * 1. Create manager with required dependencies
 * 2. Call setupRunTreeContextMenu() and setupOutputsTreeContextMenu()
 * 3. Manager handles all menu operations
 */
public class RunContextMenuManager {

    // Dependencies
    private final JFrame parentFrame;
    private final JTree runTree;
    private final JTree outputsTree;
    private final DefaultTreeModel runTreeModel;
    private final StdioTaskManager stdioTaskManager;
    private final Consumer<String> statusUpdater;
    private final Supplier<File> baseDirectorySupplier;
    private final Supplier<String> editorTextSupplier;
    private final Map<String, String> sessionToRunName;

    // Callbacks to RunManager
    private final Runnable refreshRunsCallback;

    /**
     * Represents run status for context menu decisions.
     */
    public enum RunStatus {
        RUNNING,
        DONE,
        ERROR,
        STOPPED
    }

    /**
     * Interface for accessing run information.
     */
    public interface RunInfo {
        String getRunName();
        void setRunName(String newName);
        SessionManager.KalixSession getSession();
        RunStatus getRunStatus();
    }

    /**
     * Creates a new RunContextMenuManager.
     *
     * @param parentFrame Parent frame for dialogs
     * @param runTree The run source tree
     * @param outputsTree The outputs tree
     * @param runTreeModel The run tree model
     * @param stdioTaskManager Task manager for session operations
     * @param statusUpdater Status bar updater
     * @param baseDirectorySupplier Supplier for base directory (file save dialogs)
     * @param editorTextSupplier Supplier for editor text (diff operations)
     * @param sessionToRunName Map of session keys to run names
     * @param refreshRunsCallback Callback to refresh the runs list
     */
    public RunContextMenuManager(
            JFrame parentFrame,
            JTree runTree,
            JTree outputsTree,
            DefaultTreeModel runTreeModel,
            StdioTaskManager stdioTaskManager,
            Consumer<String> statusUpdater,
            Supplier<File> baseDirectorySupplier,
            Supplier<String> editorTextSupplier,
            Map<String, String> sessionToRunName,
            Runnable refreshRunsCallback) {
        this.parentFrame = parentFrame;
        this.runTree = runTree;
        this.outputsTree = outputsTree;
        this.runTreeModel = runTreeModel;
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.baseDirectorySupplier = baseDirectorySupplier;
        this.editorTextSupplier = editorTextSupplier;
        this.sessionToRunName = sessionToRunName;
        this.refreshRunsCallback = refreshRunsCallback;
    }

    /**
     * Sets up the context menu for the run tree.
     */
    public void setupRunTreeContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> renameRun());
        contextMenu.add(renameItem);

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeRun());
        contextMenu.add(removeItem);

        contextMenu.addSeparator();

        JMenuItem saveResultsItem = new JMenuItem("Save results (csv)");
        saveResultsItem.addActionListener(e -> saveResults());
        contextMenu.add(saveResultsItem);

        JMenuItem showModelItem = new JMenuItem("Show Model");
        showModelItem.addActionListener(e -> showModel());
        contextMenu.add(showModelItem);

        JMenuItem diffItem = new JMenuItem("Show Model Changes");
        diffItem.addActionListener(e -> diffModel());
        contextMenu.add(diffItem);

        JMenuItem sessionManagerItem = new JMenuItem("View in KalixCLI Session Manager");
        sessionManagerItem.addActionListener(e -> showInSessionManager());
        contextMenu.add(sessionManagerItem);

        // Add mouse listener for right-click
        runTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            private void showContextMenu(MouseEvent e, JPopupMenu menu) {
                // Get the path at the mouse location
                TreePath path = runTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked
                    runTree.setSelectionPath(path);

                    // Check if it's a run item (not a folder)
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof RunInfo) {
                        menu.show(runTree, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * Sets up the context menu for the outputs tree with expand/collapse operations.
     * These methods delegate to the caller for tree expansion operations.
     */
    public void setupOutputsTreeContextMenu(Runnable expandAllCallback, Runnable collapseAllCallback) {
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem expandAllItem = new JMenuItem("Expand All");
        expandAllItem.addActionListener(e -> expandAllCallback.run());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse All");
        collapseAllItem.addActionListener(e -> collapseAllCallback.run());
        contextMenu.add(collapseAllItem);

        // Add mouse listener for right-click
        outputsTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e, contextMenu);
                }
            }

            private void showContextMenu(MouseEvent e, JPopupMenu menu) {
                // Get the path at the mouse location
                TreePath path = outputsTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    // Select the node that was right-clicked if not already selected
                    if (!outputsTree.isPathSelected(path)) {
                        outputsTree.setSelectionPath(path);
                    }
                    menu.show(outputsTree, e.getX(), e.getY());
                } else {
                    // Right-clicked on empty space - still show menu (applies to root)
                    menu.show(outputsTree, e.getX(), e.getY());
                }
            }
        });
    }

    // ========== Context Menu Actions ==========

    /**
     * Shows a dialog to rename the selected run.
     */
    public void renameRun() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String currentName = runInfo.getRunName();

        // Show input dialog for new name
        String newName = (String) JOptionPane.showInputDialog(
            parentFrame,
            "Enter new name for the run:",
            "Rename Run",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );

        if (newName != null) {
            newName = newName.trim();

            // Validate the new name
            if (newName.isEmpty()) {
                JOptionPane.showMessageDialog(parentFrame,
                    "Run name cannot be empty.",
                    "Invalid Name",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Check if name is already in use by another run
            String sessionKey = runInfo.getSession().getSessionKey();
            for (String existingName : sessionToRunName.values()) {
                if (existingName.equals(newName) && !existingName.equals(currentName)) {
                    JOptionPane.showMessageDialog(parentFrame,
                        "A run with the name '" + newName + "' already exists.\nPlease choose a different name.",
                        "Duplicate Name",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // Update the run name
            runInfo.setRunName(newName);
            sessionToRunName.put(sessionKey, newName);

            // Simple node refresh - just update this one node
            runTreeModel.nodeChanged(selectedNode);

            // Update status
            if (statusUpdater != null) {
                statusUpdater.accept("Renamed run '" + currentName + "' to '" + newName + "'");
            }
        }
    }

    /**
     * Opens the KalixCLI Session Manager window with the selected run's session.
     */
    public void showInSessionManager() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String sessionKey = runInfo.getSession().getSessionKey();
        SessionManagerWindow.showSessionManagerWindow(parentFrame, stdioTaskManager, statusUpdater, sessionKey);
    }

    /**
     * Shows the model INI string for the selected run in a MinimalEditorWindow.
     */
    public void showModel() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        // Get the model text from the RunModelProgram
        if (runInfo.getSession().getActiveProgram() instanceof RunModelProgram program) {
            String modelText = program.getModelText();

            if (modelText != null && !modelText.isEmpty()) {
                // Create and show MinimalEditorWindow with the model text in INI mode
                MinimalEditorWindow editorWindow = new MinimalEditorWindow(modelText, true);
                editorWindow.setTitle(runInfo.getRunName());
                editorWindow.setVisible(true);
            } else {
                JOptionPane.showMessageDialog(
                    parentFrame,
                    "Model text is not available for this run.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        } else {
            JOptionPane.showMessageDialog(
                parentFrame,
                "This run does not contain model information.",
                "Not a Model Run",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    /**
     * Opens a diff window comparing the run's model with the main editor's model.
     */
    public void diffModel() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        // Get the model text from the RunModelProgram
        if (runInfo.getSession().getActiveProgram() instanceof RunModelProgram program) {
            String runModelText = program.getModelText();

            if (runModelText == null || runModelText.isEmpty()) {
                JOptionPane.showMessageDialog(
                    parentFrame,
                    "Model text is not available for this run.",
                    "Model Not Available",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            // Get the reference model text from the main editor
            if (editorTextSupplier == null) {
                JOptionPane.showMessageDialog(
                    parentFrame,
                    "Cannot access main editor text.",
                    "Editor Not Available",
                    JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            String referenceModelText = editorTextSupplier.get();
            if (referenceModelText == null || referenceModelText.isEmpty()) {
                JOptionPane.showMessageDialog(
                    parentFrame,
                    "No model is loaded in the main editor.",
                    "No Reference Model",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }

            // Open diff window (run model vs reference model)
            String title = "Changes: " + runInfo.getRunName() + " vs Reference Model";
            new DiffWindow(runModelText, referenceModelText, title, "Reference Model", runInfo.getRunName());

        } else {
            JOptionPane.showMessageDialog(
                parentFrame,
                "This run does not contain model information.",
                "Not a Model Run",
                JOptionPane.INFORMATION_MESSAGE
            );
        }
    }

    /**
     * Removes a run from the context menu - terminates if active and removes from list.
     */
    public void removeRun() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String sessionKey = runInfo.getSession().getSessionKey();
        boolean isActive = runInfo.getSession().isActive();

        String message = isActive
            ? "Are you sure you want to stop and remove " + runInfo.getRunName() + "?\n\nThis will terminate the running session and remove it from the list."
            : "Are you sure you want to remove " + runInfo.getRunName() + " from the list?";

        if (DialogUtils.showConfirmation(parentFrame, message, "Remove Run")) {
            if (isActive) {
                // First terminate the session, then remove it
                stdioTaskManager.terminateSession(sessionKey)
                    .thenCompose(v -> {
                        // After termination, remove from list
                        return stdioTaskManager.removeSession(sessionKey);
                    })
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        if (statusUpdater != null) {
                            statusUpdater.accept("Stopped and removed run: " + runInfo.getRunName());
                        }
                        sessionToRunName.remove(sessionKey);
                        if (refreshRunsCallback != null) {
                            refreshRunsCallback.run();
                        }
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            if (statusUpdater != null) {
                                statusUpdater.accept("Failed to stop/remove run: " + throwable.getMessage());
                            }
                            DialogUtils.showError(parentFrame,
                                "Failed to stop/remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            } else {
                // Just remove from list (session already terminated)
                stdioTaskManager.removeSession(sessionKey)
                    .thenRun(() -> SwingUtilities.invokeLater(() -> {
                        if (statusUpdater != null) {
                            statusUpdater.accept("Removed run: " + runInfo.getRunName());
                        }
                        sessionToRunName.remove(sessionKey);
                        if (refreshRunsCallback != null) {
                            refreshRunsCallback.run();
                        }
                    }))
                    .exceptionally(throwable -> {
                        SwingUtilities.invokeLater(() -> {
                            if (statusUpdater != null) {
                                statusUpdater.accept("Failed to remove run: " + throwable.getMessage());
                            }
                            DialogUtils.showError(parentFrame,
                                "Failed to remove run: " + throwable.getMessage(),
                                "Remove Run Error");
                        });
                        return null;
                    });
            }
        }
    }

    /**
     * Handles save results action from context menu.
     */
    public void saveResults() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String sessionKey = runInfo.getSession().getSessionKey();
        String kalixcliUid = runInfo.getSession().getKalixcliUid();

        // Check if the run has completed successfully
        RunStatus status = runInfo.getRunStatus();
        if (status != RunStatus.DONE) {
            String statusText = status == RunStatus.ERROR ? "failed" :
                              status == RunStatus.RUNNING ? "still running" : "not completed";
            if (statusUpdater != null) {
                statusUpdater.accept("Cannot save results: run " + runInfo.getRunName() + " has " + statusText);
            }
            return;
        }

        // Generate default filename: {run_name}_{uid}.csv
        String safeRunName = runInfo.getRunName().replaceAll("[^a-zA-Z0-9_-]", "_");
        String defaultFilename = safeRunName + "_" + (kalixcliUid != null ? kalixcliUid : "unknown") + ".csv";

        // Show save dialog
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Results");
        fileChooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        fileChooser.setSelectedFile(new File(defaultFilename));

        // Set initial directory to model directory if available
        if (baseDirectorySupplier != null) {
            File baseDir = baseDirectorySupplier.get();
            if (baseDir != null) {
                fileChooser.setCurrentDirectory(baseDir);
            }
        }

        int result = fileChooser.showSaveDialog(parentFrame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Add .csv extension if not present
            String fileName = selectedFile.getName();
            if (!fileName.toLowerCase().endsWith(".csv")) {
                selectedFile = new File(selectedFile.getParent(), fileName + ".csv");
            }

            // Send save_results command to kalixcli
            String command = String.format(
                "{\"m\":\"cmd\",\"c\":\"save_results\",\"p\":{\"path\":\"%s\",\"format\":\"csv\"}}",
                selectedFile.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"")
            );

            try {
                stdioTaskManager.sendCommand(sessionKey, command);
                if (statusUpdater != null) {
                    statusUpdater.accept("Saving results to: " + selectedFile.getName());
                }
            } catch (Exception e) {
                if (statusUpdater != null) {
                    statusUpdater.accept("Failed to send save command: " + e.getMessage());
                }
                DialogUtils.showError(parentFrame,
                    "Failed to save results: " + e.getMessage(),
                    "Save Results Error");
            }
        }
    }
}
