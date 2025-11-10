# OptimisationWindow Integration Instructions

## Overview
This document provides instructions for completing the integration of newly extracted manager classes into OptimisationWindow.java. The extraction phase is complete (9 managers + 3 data classes created), but OptimisationWindow still contains the old inline code that needs to be removed and replaced with manager delegations.

## Current State

### Original Problem
- **File**: `/Users/chas/github/Kalix/kalixide/src/main/java/com/kalix/ide/windows/OptimisationWindow.java`
- **Size**: 2006 lines (largest file in codebase)
- **Issue**: Violates Single Responsibility Principle with 12+ different responsibilities

### Completed Work (Extraction Phase)
Successfully extracted 12 new files totaling ~2,830 lines:

#### Data Model Classes (3 files)
1. **OptimisationInfo.java** - Session and configuration tracking
2. **OptimisationResult.java** - Results with convergence history
3. **OptimisationStatus.java** - Status enum with UI colors

#### Manager Classes (9 files)
1. **OptimisationTreeManager.java** (350 lines) - Tree operations and context menus
2. **OptimisationConfigManager.java** (387 lines) - Configuration editing/validation
3. **OptimisationProgressManager.java** (344 lines) - Progress tracking and timing
4. **OptimisationResultsManager.java** (398 lines) - Results display and export
5. **OptimisationPlotManager.java** (333 lines) - Convergence plot visualization
6. **OptimisationSessionManager.java** (530 lines) - Session lifecycle management
7. **OptimisationTreeCellRenderer.java** (105 lines) - Tree cell rendering

**All files compile successfully** - No build errors present.

## Required Integration Work

### Goal
Refactor OptimisationWindow.java to:
- Use the extracted managers instead of inline code
- Reduce from 2006 lines to approximately 400-500 lines
- Maintain all existing functionality

### Step 1: Add Manager Instance Variables
Add these to OptimisationWindow class:
```java
// Manager instances
private OptimisationTreeManager treeManager;
private OptimisationConfigManager configManager;
private OptimisationProgressManager progressManager;
private OptimisationResultsManager resultsManager;
private OptimisationPlotManager plotManager;
private OptimisationSessionManager sessionManager;
```

### Step 2: Initialize Managers in Constructor
```java
private OptimisationWindow(StdioTaskManager stdioTaskManager, /* other params */) {
    // ... existing setup ...

    // Initialize managers
    this.sessionManager = new OptimisationSessionManager(
        stdioTaskManager,
        workingDirectorySupplier,
        modelTextSupplier
    );

    this.treeManager = new OptimisationTreeManager();
    this.configManager = new OptimisationConfigManager(
        workingDirectorySupplier,
        modelTextSupplier
    );
    this.progressManager = new OptimisationProgressManager(progressBar);
    this.resultsManager = new OptimisationResultsManager();
    this.plotManager = new OptimisationPlotManager();

    // Set up callbacks
    setupManagerCallbacks();
}
```

### Step 3: Wire Manager Callbacks
```java
private void setupManagerCallbacks() {
    // Session manager callbacks
    sessionManager.setStatusUpdater(statusUpdater);
    sessionManager.setOnOptimisationCreated(optInfo -> {
        treeManager.addOptimisation(optInfo);
        SwingUtilities.invokeLater(() -> {
            selectOptimisation(optInfo);
        });
    });
    sessionManager.setOnSessionCompleted(sessionKey -> {
        updateUIForCompletion(sessionKey);
    });

    // Tree manager callbacks
    treeManager.setOnSelectionChanged(this::onOptimisationSelected);
    treeManager.setOnRenameRequested((optInfo, newName) -> {
        sessionManager.renameOptimisation(optInfo.getSessionKey(), newName);
    });
    treeManager.setOnRemoveRequested(optInfo -> {
        sessionManager.removeOptimisation(optInfo.getSessionKey(),
            optInfo.getStatus() == OptimisationStatus.RUNNING);
    });

    // Config manager callbacks
    configManager.setStatusUpdater(statusUpdater);

    // Results manager callbacks
    resultsManager.setWorkingDirectorySupplier(workingDirectorySupplier);
    resultsManager.setOriginalModelSupplier(modelTextSupplier);
    resultsManager.setStatusUpdater(statusUpdater);
}
```

### Step 4: Replace Method Implementations

#### Replace createNewOptimisation()
```java
private void createNewOptimisation() {
    String configText = configManager.generateConfigFromGui();

    sessionManager.createOptimisation(
        configText,
        progressInfo -> progressManager.updateProgress(currentOptInfo, progressInfo),
        parameters -> handleOptimisableParameters(parameters),
        result -> handleOptimisationResult(result)
    );
}
```

#### Replace runOptimisation()
```java
private void runOptimisation() {
    if (currentlyDisplayedNode == null) return;

    Object userObject = currentlyDisplayedNode.getUserObject();
    if (!(userObject instanceof OptimisationInfo optInfo)) return;

    // Get config from appropriate source
    String configText = mainTabbedPane.getSelectedIndex() == 0 ?
        configManager.generateConfigFromGui() :
        configManager.getCurrentConfig();

    // Update config snapshot
    optInfo.setConfigSnapshot(configText);

    // Run optimisation
    boolean started = sessionManager.runOptimisation(optInfo);

    if (started) {
        // Switch to results tab
        mainTabbedPane.setSelectedIndex(2);
        progressManager.startProgress(optInfo);
    }
}
```

#### Replace tree selection handling
```java
private void onOptimisationSelected(OptimisationInfo optInfo) {
    if (optInfo == null) {
        showNoSelectionMessage();
        return;
    }

    // Update displays
    configManager.loadConfiguration(optInfo);
    resultsManager.displayResults(optInfo);
    plotManager.updatePlot(optInfo.getResult());
    progressManager.setCurrentOptimisation(optInfo);

    // Show optimisation panel
    rightPanelLayout.show(rightPanel, "optimisation");
    currentlyDisplayedNode = treeManager.getNodeForOptimisation(optInfo);
}
```

### Step 5: Update UI Component Creation

Replace UI creation with manager components:
```java
private void setupUI() {
    // Left panel - use tree manager
    JTree tree = treeManager.getTree();
    JScrollPane treeScroll = new JScrollPane(tree);

    // Config tab - use config manager
    JPanel configTab = new JPanel(new BorderLayout());
    configTab.add(configManager.getGuiBuilder().getPanel(), BorderLayout.CENTER);

    // Config INI tab - use config manager
    JPanel configIniTab = new JPanel(new BorderLayout());
    configIniTab.add(configManager.getConfigScrollPane(), BorderLayout.CENTER);

    // Results tab - use results manager
    JPanel resultsTab = new JPanel(new BorderLayout());
    resultsTab.add(resultsManager.getScrollPane(), BorderLayout.CENTER);

    // Plot panel - use plot manager
    JPanel plotPanel = plotManager.createPlotPanelWithLabels();

    // Progress labels - use progress manager
    JPanel progressPanel = new JPanel();
    progressPanel.add(progressManager.getStartTimeLabel());
    progressPanel.add(progressManager.getElapsedTimeLabel());
    progressPanel.add(progressManager.getEvaluationProgressLabel());
    progressPanel.add(progressManager.getBestObjectiveLabel());
}
```

### Step 6: Remove Old Code

Delete these sections from OptimisationWindow:
1. **Lines 1700-2000**: Inner classes (OptimisationInfo, OptimisationStatus, OptimisationResult)
2. **Lines 449-541**: Old createNewOptimisation implementation
3. **Lines 962-1051**: Old runOptimisation implementation
4. **Lines 681-745**: Context menu setup (now in TreeManager)
5. **Lines 1053-1228**: Progress handling methods
6. **Lines 1229-1485**: Tree update methods
7. **Lines 1486-1698**: Display update methods

### Step 7: Clean Up Imports

Remove unused imports and add new ones:
```java
// Remove
import com.kalix.ide.flowviz.data.TimeSeriesData;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
// ... other unused imports

// Add
import com.kalix.ide.managers.optimisation.*;
import com.kalix.ide.models.optimisation.*;
import com.kalix.ide.renderers.OptimisationTreeCellRenderer;
```

## Verification Steps

### 1. Compilation
```bash
./gradlew compileJava --no-daemon
```
Should compile with no errors.

### 2. Functionality Testing
Test these key workflows:
- Create new optimisation
- Load configuration from file
- Switch between Config/Config INI tabs
- Run optimisation
- View results and convergence plot
- Tree operations (rename, remove, select)
- Context menu actions

### 3. Code Metrics
Verify size reduction:
```bash
wc -l src/main/java/com/kalix/ide/windows/OptimisationWindow.java
```
Target: ~400-500 lines (from 2006)

## Code Patterns to Follow

### Manager Delegation Pattern
```java
// Instead of inline logic:
if (configEditor.getText().isEmpty()) {
    JOptionPane.showMessageDialog(...);
    return;
}

// Delegate to manager:
if (!configManager.validateConfiguration()) {
    return;
}
```

### Callback Pattern
```java
// Managers communicate via callbacks, not direct coupling
treeManager.setOnSelectionChanged(optInfo -> {
    // Handle selection without TreeManager knowing about OptimisationWindow
});
```

### Separation of Concerns
- **OptimisationWindow**: Coordination only
- **Managers**: Business logic and operations
- **Data Models**: State representation

## Common Pitfalls to Avoid

1. **Don't access manager internals directly** - Use public methods only
2. **Don't let managers reference OptimisationWindow** - Use callbacks
3. **Don't duplicate state** - Single source of truth in managers
4. **Don't forget null checks** - Managers should handle edge cases

## Expected Outcome

After integration:
- OptimisationWindow reduced to ~400-500 lines
- Clear separation of concerns
- Improved testability (managers can be unit tested)
- Better maintainability
- Consistent with RunManager refactoring pattern

## Additional Notes

- The extracted managers follow the same pattern successfully used in RunManager refactoring
- All managers already compile successfully
- The OptimisationSessionManager may need minor adjustments for the actual KalixSession API
- Consider adding unit tests for individual managers after integration

## References

- Original issue: OptimisationWindow identified as worst offender in code quality review
- Pattern example: RunManager.java (successfully refactored using same approach)
- Architecture: Manager pattern with callback-based communication