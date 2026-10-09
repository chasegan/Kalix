package com.kalix.ide.managers;

import com.kalix.ide.cli.RunModelProgram;
import com.kalix.ide.components.JCheckboxTree;
import com.kalix.ide.cli.SessionManager;
import com.kalix.ide.diff.DiffWindow;
import com.kalix.ide.filedialog.FileDialogFilter;
import com.kalix.ide.filedialog.KalixFileDialog;
import com.kalix.ide.utils.DialogUtils;
import com.kalix.ide.utils.StatusReporter;
import com.kalix.ide.windows.MassBalanceReportWindow;
import com.kalix.ide.windows.MinimalEditorWindow;
import com.kalix.ide.windows.SessionManagerWindow;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages context menus and related actions for RunManager.
 *
 * Responsibilities:
 * - Setting up run tree context menu (rename, remove, save, show model, diff, session manager)
 * - Setting up outputs tree context menu (expand/collapse, show checked/selected)
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
    private final JCheckboxTree outputsTree;
    private final DefaultTreeModel runTreeModel;
    private final StdioTaskManager stdioTaskManager;
    private final StatusReporter statusUpdater;
    private final Supplier<File> baseDirectorySupplier;
    private final Supplier<String> modelTextSupplier;
    private final Supplier<String> editorTextSupplier;
    private final Map<String, String> sessionToRunName;

    // Callbacks to RunManager
    private final Runnable refreshRunsCallback;
    // Rename delegate: applied with (runInfo, newName). Returns null on success, or a
    // user-facing error string. Owns all validation and propagation.
    private final BiFunction<RunInfo, String, String> renameRunDelegate;
    // Removes a loaded dataset and cleans up the shared pool, slot assignment, and
    // every plot/stats tab that referenced its series.
    private final Consumer<DatasetLoaderManager.LoadedDatasetInfo> removeDatasetDelegate;

    // Top-level category nodes that support "Remove all" on right-click (e.g. "Current runs",
    // "Run library", "Loaded datasets"). Set by the owner via setRemovableCategories.
    private final Set<DefaultMutableTreeNode> removableCategories = new HashSet<>();

    // Menus for node kinds this manager doesn't know (e.g. derived series): user object -> menu,
    // or null for none. Set by the owner via setNodeMenuProvider.
    private Function<Object, JPopupMenu> nodeMenuProvider = userObject -> null;

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
     * Interface for accessing run information. Implementations are immutable post-
     * construction; renaming is performed via the owner (RunManager), which constructs
     * a fresh instance and propagates the change to all dependent state.
     */
    public interface RunInfo {
        String getRunName();
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
     * @param modelTextSupplier Supplier for the active model's text (model diff)
     * @param editorTextSupplier Supplier for the active document's text, whatever its kind
     * @param sessionToRunName Map of session keys to run names
     * @param refreshRunsCallback Callback to refresh the runs list
     * @param renameRunDelegate Delegate that validates and applies a run rename
     * @param removeDatasetDelegate Delegate that removes a loaded dataset
     */
    public RunContextMenuManager(
            JFrame parentFrame,
            JTree runTree,
            JCheckboxTree outputsTree,
            DefaultTreeModel runTreeModel,
            StdioTaskManager stdioTaskManager,
            StatusReporter statusUpdater,
            Supplier<File> baseDirectorySupplier,
            Supplier<String> modelTextSupplier,
            Supplier<String> editorTextSupplier,
            Map<String, String> sessionToRunName,
            Runnable refreshRunsCallback,
            BiFunction<RunInfo, String, String> renameRunDelegate,
            Consumer<DatasetLoaderManager.LoadedDatasetInfo> removeDatasetDelegate) {
        this.parentFrame = parentFrame;
        this.runTree = runTree;
        this.outputsTree = outputsTree;
        this.runTreeModel = runTreeModel;
        this.stdioTaskManager = stdioTaskManager;
        this.statusUpdater = statusUpdater;
        this.baseDirectorySupplier = baseDirectorySupplier;
        this.modelTextSupplier = modelTextSupplier;
        this.editorTextSupplier = editorTextSupplier;
        this.sessionToRunName = sessionToRunName;
        this.refreshRunsCallback = refreshRunsCallback;
        this.renameRunDelegate = renameRunDelegate;
        this.removeDatasetDelegate = removeDatasetDelegate;
    }

    /**
     * Sets up the context menu for the run tree.
     */
    public void setupRunTreeContextMenu() {
        // Skeleton order (ADR-0002 §1): view actions first, navigation next,
        // modify + the destructive "Remove" last. "Remove" unlists the run (it is not "Delete"),
        // so it takes no trash icon (§2.5).
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem showModelItem = new JMenuItem("Show model");
        showModelItem.addActionListener(e -> showModel());
        contextMenu.add(showModelItem);

        JMenuItem diffItem = new JMenuItem("Show model changes");
        diffItem.addActionListener(e -> diffModel());
        diffItem.setToolTipText("Compare this run's model with the model in the main editor.");
        contextMenu.add(diffItem);

        JMenuItem mbReportItem = new JMenuItem("Show mass balance report");
        mbReportItem.addActionListener(e -> showMassBalanceReport());
        contextMenu.add(mbReportItem);

        JMenuItem mbValidateItem = new JMenuItem("Validate mass balance");
        mbValidateItem.addActionListener(e -> validateMassBalance());
        mbValidateItem.setToolTipText("Compare this run's mass balance with the mass balance file in the main editor.");
        contextMenu.add(mbValidateItem);

        JMenuItem saveResultsItem = new JMenuItem("Save results");
        saveResultsItem.addActionListener(e -> saveResults());
        contextMenu.add(saveResultsItem);

        contextMenu.addSeparator();

        JMenuItem sessionManagerItem = new JMenuItem("View in KalixCLI Session Manager");
        sessionManagerItem.addActionListener(e -> showInSessionManager());
        contextMenu.add(sessionManagerItem);

        contextMenu.addSeparator();

        JMenuItem renameItem = new JMenuItem("Rename");
        renameItem.addActionListener(e -> renameRun());
        contextMenu.add(renameItem);

        // Cooperative stop of a running simulation (protocol stp): the engine finishes
        // its current timestep and the session stays alive. Enabled only while running.
        JMenuItem stopItem = new JMenuItem("Stop");
        stopItem.addActionListener(e -> stopRun());
        contextMenu.add(stopItem);

        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeRun());
        contextMenu.add(removeItem);

        // A separate, smaller menu shown when a loaded-dataset node is right-clicked.
        JPopupMenu datasetMenu = new JPopupMenu();
        JMenuItem removeDatasetItem = new JMenuItem("Remove");
        removeDatasetItem.addActionListener(e -> removeDataset());
        datasetMenu.add(removeDatasetItem);

        // Menu shown when a top-level category node (e.g. "Current runs") is right-clicked,
        // for removing every child in one action. The item is disabled when the category is empty.
        JPopupMenu categoryMenu = new JPopupMenu();
        JMenuItem removeAllItem = new JMenuItem("Remove all");
        removeAllItem.addActionListener(e -> removeAllInSelectedCategory());
        categoryMenu.add(removeAllItem);

        // Add mouse listener for right-click
        runTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            private void showContextMenu(MouseEvent e) {
                // Get the path at the mouse location
                TreePath path = runTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) {
                    return;
                }
                // Select the node that was right-clicked
                runTree.setSelectionPath(path);

                // Pick the menu by node type — runs and loaded datasets share the tree
                // but get different actions.
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                Object userObject = node.getUserObject();
                if (userObject instanceof RunInfo runInfo) {
                    stopItem.setEnabled(runInfo.getSession().getState()
                        == SessionManager.SessionState.RUNNING);
                    // Mass balance needs a finished run (ADR-0002 §4): greyed while the
                    // run is still going, hidden once it can never apply.
                    RunStatus status = runInfo.getRunStatus();
                    boolean mayFinish = status == RunStatus.DONE || status == RunStatus.RUNNING;
                    for (JMenuItem item : List.of(mbReportItem, mbValidateItem)) {
                        item.setVisible(mayFinish);
                        item.setEnabled(status == RunStatus.DONE);
                    }
                    contextMenu.show(runTree, e.getX(), e.getY());
                } else if (userObject instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                    datasetMenu.show(runTree, e.getX(), e.getY());
                } else if (nodeMenuProvider.apply(userObject) instanceof JPopupMenu menu) {
                    menu.show(runTree, e.getX(), e.getY());
                } else if (removableCategories.contains(node)) {
                    removeAllItem.setEnabled(countRemovableChildren(node) > 0);
                    categoryMenu.show(runTree, e.getX(), e.getY());
                }
            }
        });
    }

    /** A menu item enabled only while {@code enabledWhen} holds; it stays visible, greyed, so the user learns it exists (ADR-0002 §4). */
    public record ConditionalItem(String label, Runnable action, BooleanSupplier enabledWhen) {
    }

    /**
     * Adds {@code items} and a separator after them; returns what refreshes their
     * enabled state when the menu opens.
     */
    private static Runnable addConditionalBlock(JPopupMenu menu, List<ConditionalItem> items) {
        List<JMenuItem> menuItems = new ArrayList<>();
        for (ConditionalItem item : items) {
            JMenuItem menuItem = new JMenuItem(item.label());
            menuItem.addActionListener(e -> item.action().run());
            menu.add(menuItem);
            menuItems.add(menuItem);
        }
        menu.add(new JSeparator());
        return () -> {
            for (int i = 0; i < items.size(); i++) {
                menuItems.get(i).setEnabled(items.get(i).enabledWhen().getAsBoolean());
            }
        };
    }

    public void setNodeMenuProvider(Function<Object, JPopupMenu> provider) {
        this.nodeMenuProvider = provider;
    }

    /**
     * Registers the top-level category nodes whose right-click menu offers "Remove all"
     * (e.g. "Current runs", "Run library", "Loaded datasets", "Last run"). For "Last run" the
     * single child is an alias to the most-recent run's session, so "Remove all" removes that
     * one run — identical to its existing single "Remove".
     */
    public void setRemovableCategories(DefaultMutableTreeNode... categories) {
        removableCategories.clear();
        for (DefaultMutableTreeNode category : categories) {
            if (category != null) {
                removableCategories.add(category);
            }
        }
    }

    /** Counts a category's children that this manager knows how to remove. */
    private int countRemovableChildren(DefaultMutableTreeNode category) {
        int count = 0;
        for (int i = 0; i < category.getChildCount(); i++) {
            Object child = ((DefaultMutableTreeNode) category.getChildAt(i)).getUserObject();
            if (child instanceof RunInfo || child instanceof DatasetLoaderManager.LoadedDatasetInfo) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sets up the context menu for the outputs tree: the {@code createItems} block, then
     * the view/state block (ADR-0002 §1). Create items stay visible and are greyed when
     * they don't apply, so the user learns they exist (§4). All items delegate to their
     * callbacks.
     */

    public void setupOutputsTreeContextMenu(List<ConditionalItem> createItems,
                                            Runnable expandAllCallback,
                                            Runnable collapseAllCallback,
                                            Runnable showCheckedCallback,
                                            Runnable showSelectedCallback) {
        JPopupMenu contextMenu = new JPopupMenu();
        Runnable refreshCreateItems = addConditionalBlock(contextMenu, createItems);
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshCreateItems.run();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        JMenuItem expandAllItem = new JMenuItem("Expand all");
        expandAllItem.addActionListener(e -> expandAllCallback.run());
        contextMenu.add(expandAllItem);

        JMenuItem collapseAllItem = new JMenuItem("Collapse all");
        collapseAllItem.addActionListener(e -> collapseAllCallback.run());
        contextMenu.add(collapseAllItem);

        JMenuItem showCheckedItem = new JMenuItem("Show checked");
        showCheckedItem.addActionListener(e -> showCheckedCallback.run());
        contextMenu.add(showCheckedItem);

        JMenuItem showSelectedItem = new JMenuItem("Show selected");
        showSelectedItem.addActionListener(e -> showSelectedCallback.run());
        contextMenu.add(showSelectedItem);

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
                // Get the path at the mouse location (full-width hit test)
                int row = outputsTree.getClosestRowForLocation(0, e.getY());
                Rectangle bounds = row < 0 ? null : outputsTree.getRowBounds(row);
                boolean onRow = bounds != null
                    && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height;
                if (!onRow) {
                    // Empty space below the last row: the menu acts on the root.
                    outputsTree.clearSelection();
                } else if (!outputsTree.isRowSelected(row)) {
                    outputsTree.setSelectionRow(row);
                }
                // Enable/disable and hide/show menu items based on context (ADR-0002 §4).
                // "Show selected" cannot apply to an empty-space click, which just cleared the selection
                showSelectedItem.setVisible(onRow);
                // "Show checked" stays visible but greyed, so the user learns it exists.
                showCheckedItem.setEnabled(outputsTree.getCheckedPaths().length > 0);

                menu.show(outputsTree, e.getX(), e.getY());
            }
        });
    }

    // ========== Context Menu Actions ==========

    /**
     * Shows a dialog to rename the selected run. Validation and propagation are owned by
     * the rename delegate (RunManager); this method handles only the input dialog and
     * displays any rejection message back to the user.
     */
    public void renameRun() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String currentName = runInfo.getRunName();

        String newName = (String) JOptionPane.showInputDialog(
            parentFrame,
            "Enter new name for the run:",
            "Rename Run",
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            currentName
        );

        if (newName == null) return; // cancelled
        newName = newName.trim();

        String error = renameRunDelegate.apply(runInfo, newName);
        if (error != null) {
            JOptionPane.showMessageDialog(parentFrame, error, "Invalid Name",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (statusUpdater != null && !newName.equals(currentName)) {
            statusUpdater.accept("Renamed run '" + currentName + "' to '" + newName + "'");
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
            if (modelTextSupplier == null) {
                DialogUtils.showError(
                    parentFrame,
                    "Cannot access main editor text.",
                    "Editor Not Available");
                return;
            }

            String referenceModelText = modelTextSupplier.get();
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
     * The selected run, if it has finished. Otherwise null: silently when no run is
     * selected, and with a status bar line saying why {@code action} cannot be done
     * when the run has not finished.
     */
    private RunInfo selectedFinishedRun(String action) {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return null;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return null;

        RunStatus status = runInfo.getRunStatus();
        if (status != RunStatus.DONE) {
            String statusText = status == RunStatus.ERROR ? "failed" :
                              status == RunStatus.RUNNING ? "still running" : "not completed";
            if (statusUpdater != null) {
                statusUpdater.error("Cannot " + action + ": run " + runInfo.getRunName() + " has " + statusText);
            }
            return null;
        }
        return runInfo;
    }

    /**
     * Fetches the selected run's mass balance report and hands it to
     * {@code onReportReceived} on the EDT. The reply is asynchronous, so nothing is
     * returned. {@code onReportReceived} is not called if no finished run is selected
     * or the request fails; the user is told why.
     */
    private void getMassBalanceReport(BiConsumer<RunInfo, String> onReportReceived) {
        RunInfo runInfo = selectedFinishedRun("get mass balance");
        if (runInfo == null) return;
        String sessionKey = runInfo.getSession().getSessionKey();

        // The future completes on the session's reader thread.
        stdioTaskManager.requestMassBalanceReport(sessionKey).whenComplete(
            (report, throwable) -> SwingUtilities.invokeLater(
                () -> {
                    if (throwable != null) {
                        if (statusUpdater != null) {
                            statusUpdater
                                    .accept("Failed to get mass balance report: " + throwable.getMessage());
                        }
                        DialogUtils.showError(parentFrame,
                                "Failed to get mass balance report: " + throwable.getMessage(),
                                "Mass Balance Report Error");
                        return;
                    }
                    onReportReceived.accept(runInfo, report);
                }));
    }

    /**
     * Shows a mass balance report for the selected run. Verification is
     * available via the "Validate mass balance" menu item (against the open
     * editor tab), or the "Validate..." button in the popup window.
     */
    public void showMassBalanceReport() {
        getMassBalanceReport((runInfo, report) -> {
            new MassBalanceReportWindow(
                runInfo.getRunName(), report, baseDirectorySupplier,
                (window, reference) ->
                    massBalanceDiffOrOK(window, runInfo.getRunName(), report, reference)
            ).setVisible(true);
        });
    }

    /**
     * Validate the mass balance of the selected run against the mass balance
     * file in the main editor window.
     */
    private void validateMassBalance() {
        getMassBalanceReport((runInfo, report) -> {
            String referenceReport = editorTextSupplier.get();
            if (referenceReport == null || referenceReport.isEmpty()) {
                JOptionPane.showMessageDialog(
                    parentFrame,
                    "No mass balance file is loaded in the main editor.",
                    "No Reference Mass Balance",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }
            massBalanceDiffOrOK(parentFrame, runInfo.getRunName(), report, referenceReport);
        });
    }

    // A reference more than this many times the report's length is not compared. The
    // comparison and the diff run on the EDT, and the reference can be whatever the
    // active editor tab holds, a large data file included. The figure is arbitrary.
    private static final int MAX_REFERENCE_TO_REPORT_LENGTH = 10;

    /**
     * Compares a run's mass balance report with a reference report: an info box over
     * {@code parent} if they match, a diff window if they do not. A reference far
     * larger than the report is refused, with a message.
     */
    private void massBalanceDiffOrOK(Component parent, String runName, String report,
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

    /**
     * Removes a run from the context menu - terminates if active and removes from list.
     */
    /**
     * Sends a cooperative stop (protocol stp) to the selected run's session. The run
     * transitions to stopped when the engine acknowledges at its next timestep; the
     * session itself stays alive.
     */
    private void stopRun() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof RunInfo runInfo)) return;

        String sessionKey = runInfo.getSession().getSessionKey();
        stdioTaskManager.stopSession(sessionKey).exceptionally(throwable -> {
            SwingUtilities.invokeLater(() -> {
                if (statusUpdater != null) {
                    statusUpdater.error("Failed to stop run: " + throwable.getMessage());
                }
            });
            return null;
        });
    }

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
            removeRunInfoAsync(runInfo).thenRun(() -> SwingUtilities.invokeLater(() -> {
                if (refreshRunsCallback != null) {
                    refreshRunsCallback.run();
                }
            }));
        }
    }

    /**
     * Removes a single run without confirmation — terminating its session first if active —
     * and returns a future that completes once the removal finishes. The owning tree is not
     * refreshed here; the caller refreshes (once) after the returned future(s) complete. Shared
     * by single-run removal and the category "Remove all".
     */
    private CompletableFuture<Void> removeRunInfoAsync(RunInfo runInfo) {
        String sessionKey = runInfo.getSession().getSessionKey();
        boolean isActive = runInfo.getSession().isActive();

        CompletableFuture<?> removal = isActive
            ? stdioTaskManager.terminateSession(sessionKey)
                .thenCompose(v -> stdioTaskManager.removeSession(sessionKey))
            : stdioTaskManager.removeSession(sessionKey);

        return removal.thenRun(() -> SwingUtilities.invokeLater(() -> {
            if (statusUpdater != null) {
                statusUpdater.accept((isActive ? "Stopped and removed run: " : "Removed run: ")
                    + runInfo.getRunName());
            }
            sessionToRunName.remove(sessionKey);
        })).exceptionally(throwable -> {
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

    /**
     * Removes every removable child of the right-clicked top-level category, after a single
     * confirmation. Runs and loaded datasets are handled by their respective paths; the tree
     * is refreshed once the run removals complete.
     */
    public void removeAllInSelectedCategory() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode category = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!removableCategories.contains(category)) return;

        List<RunInfo> runs = new ArrayList<>();
        List<DatasetLoaderManager.LoadedDatasetInfo> datasets = new ArrayList<>();
        for (int i = 0; i < category.getChildCount(); i++) {
            Object child = ((DefaultMutableTreeNode) category.getChildAt(i)).getUserObject();
            if (child instanceof RunInfo runInfo) {
                runs.add(runInfo);
            } else if (child instanceof DatasetLoaderManager.LoadedDatasetInfo datasetInfo) {
                datasets.add(datasetInfo);
            }
        }

        int total = runs.size() + datasets.size();
        if (total == 0) return;

        String label = String.valueOf(category.getUserObject());
        String message = "Remove all " + total + (total == 1 ? " item" : " items")
            + " under \"" + label + "\"?";
        if (!DialogUtils.showConfirmation(parentFrame, message, "Remove All")) return;

        // Datasets remove synchronously (delegate handles pool/cache/tab + tree cleanup).
        for (DatasetLoaderManager.LoadedDatasetInfo datasetInfo : datasets) {
            if (removeDatasetDelegate != null) {
                removeDatasetDelegate.accept(datasetInfo);
            }
        }

        // Runs remove asynchronously; refresh the tree once after all have completed.
        if (runs.isEmpty()) return;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (RunInfo runInfo : runs) {
            futures.add(removeRunInfoAsync(runInfo));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                if (refreshRunsCallback != null) {
                    refreshRunsCallback.run();
                }
            }));
    }

    /**
     * Removes a loaded dataset from the source tree after confirmation, delegating
     * pool/cache/tab cleanup to the owner ({@link #removeDatasetDelegate}).
     */
    public void removeDataset() {
        TreePath selectedPath = runTree.getSelectionPath();
        if (selectedPath == null) return;

        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        if (!(selectedNode.getUserObject() instanceof DatasetLoaderManager.LoadedDatasetInfo info)) return;

        String message = "Remove the dataset \"" + info.fileName + "\"?\n\n"
            + "Any plots currently showing its series will lose them.";

        if (DialogUtils.showConfirmation(parentFrame, message, "Remove Dataset")
                && removeDatasetDelegate != null) {
            removeDatasetDelegate.accept(info);
        }
    }

    /**
     * Handles save results action from context menu.
     */
    public void saveResults() {
        RunInfo runInfo = selectedFinishedRun("save results");
        if (runInfo == null) return;

        String sessionKey = runInfo.getSession().getSessionKey();
        String kalixcliUid = runInfo.getSession().getKalixcliUid();

        // Generate default filename: {run_name}_{uid}.csv (CSV is the default format)
        String safeRunName = runInfo.getRunName().replaceAll("[^a-zA-Z0-9_-]", "_");
        String baseFilename = safeRunName + "_" + (kalixcliUid != null ? kalixcliUid : "unknown");

        // Show save dialog with all engine-supported formats. Matches the plot tab's
        // "Save Data" dialog: CSV (the suggested default) and Pixie (.pxt + .pxb sibling).
        java.util.Optional<File> chosen = KalixFileDialog.saveFile(parentFrame)
            .title("Save Results")
            .startIn(baseDirectorySupplier != null ? baseDirectorySupplier.get() : null)
            .suggestedName(baseFilename + ".csv")
            .filters(
                FileDialogFilter.of("CSV Files (*.csv)", "csv"),
                FileDialogFilter.of("Zipped CSV (*.csv.zip)", "csv.zip"),
                FileDialogFilter.of("Pixie Files (*.pxt)", "pxt"))
            .show();
        if (chosen.isPresent()) {
            File selectedFile = chosen.get();
            String lowerName = selectedFile.getName().toLowerCase();

            // The format IS the extension the user settled on. The type combo only ever
            // sets that extension, so there is no second opinion to reconcile against.
            boolean pixie = lowerName.endsWith(".pxt");
            boolean csvZip = com.kalix.ide.io.CsvZipFormat.isCsvZip(lowerName);
            String format = pixie ? "pixie" : (csvZip ? "csv.zip" : "csv");
            String ext = pixie ? ".pxt" : (csvZip ? ".csv.zip" : ".csv");

            // Append the format's extension unless the user already typed it.
            if (!lowerName.endsWith(ext)) {
                selectedFile = new File(selectedFile.getParent(), selectedFile.getName() + ext);
            }

            // Send save_results command to kalixcli with the chosen format. For pixie the
            // engine strips the .pxt and writes the .pxt/.pxb pair from that base path.
            String command = String.format(
                "{\"m\":\"cmd\",\"c\":\"save_results\",\"p\":{\"path\":\"%s\",\"format\":\"%s\"}}",
                selectedFile.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\""),
                format
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
