# OptimisationWindow Refactoring Plan

## Current State Analysis
**File**: `OptimisationWindow.java`
**Size**: 2006 lines (LARGEST in codebase!)
**Responsibilities**:
- Tree management
- Configuration editing
- Progress tracking
- Results display
- Plot visualization
- Session management
- UI construction
- Event handling

## Proposed Architecture

### Core Class Structure (Target: ~400 lines)
```java
public class OptimisationWindow extends JFrame {
    // Managers
    private OptimisationTreeManager treeManager;
    private OptimisationConfigManager configManager;
    private OptimisationProgressManager progressManager;
    private OptimisationResultsManager resultsManager;
    private OptimisationPlotManager plotManager;
    private OptimisationSessionManager sessionManager;

    // UI Components (minimal)
    private JSplitPane mainSplitPane;
    private JPanel rightPanel;

    // Lifecycle methods only
    public OptimisationWindow(...) { }
    private void initializeManagers() { }
    private void setupLayout() { }
    public void showWindow() { }
}
```

## Manager Breakdown

### 1. OptimisationTreeManager (~250 lines)
**Responsibilities**:
- Tree model management
- Node creation/deletion
- Selection handling
- Context menu operations

```java
public class OptimisationTreeManager {
    private JTree tree;
    private DefaultTreeModel model;
    private Map<String, DefaultMutableTreeNode> nodeMap;

    public void addOptimisation(String name, OptimisationInfo info);
    public void removeOptimisation(String sessionKey);
    public void updateOptimisationStatus(String sessionKey, OptimisationStatus status);
    public void selectOptimisation(String sessionKey);
    private void setupContextMenu();
}
```

### 2. OptimisationConfigManager (~300 lines)
**Responsibilities**:
- Configuration editing
- Validation
- Loading/saving configs
- Template management

```java
public class OptimisationConfigManager {
    private KalixIniTextArea configEditor;
    private OptimisationGuiBuilder guiBuilder;

    public void loadConfiguration(File file);
    public void saveConfiguration(File file);
    public String getCurrentConfig();
    public void setConfiguration(String config);
    public boolean validateConfiguration();
}
```

### 3. OptimisationProgressManager (~200 lines)
**Responsibilities**:
- Progress tracking
- Status updates
- Time tracking
- Progress bar management

```java
public class OptimisationProgressManager {
    private StatusProgressBar progressBar;
    private JLabel statusLabel;
    private Timer elapsedTimer;

    public void startProgress(String sessionKey);
    public void updateProgress(int current, int total);
    public void completeProgress(OptimisationResult result);
    private void updateElapsedTime();
}
```

### 4. OptimisationResultsManager (~250 lines)
**Responsibilities**:
- Results display
- Optimised model viewing
- Comparison operations
- Export functionality

```java
public class OptimisationResultsManager {
    private KalixIniTextArea optimisedModelEditor;
    private JLabel bestObjectiveLabel;

    public void displayResults(OptimisationResult result);
    public void compareWithOriginal();
    public void exportResults(File file);
    private void formatResultsDisplay(OptimisationResult result);
}
```

### 5. OptimisationPlotManager (~200 lines)
**Responsibilities**:
- Convergence plot
- Data management
- Plot interactions
- Export plot

```java
public class OptimisationPlotManager {
    private PlotPanel convergencePlot;
    private DataSet convergenceDataSet;

    public void addConvergencePoint(int iteration, double objective);
    public void resetPlot();
    public void exportPlot(File file);
    private void configurePlot();
}
```

### 6. OptimisationSessionManager (~150 lines)
**Responsibilities**:
- Session lifecycle
- Optimisation tracking
- Result storage

```java
public class OptimisationSessionManager {
    private Map<String, OptimisationInfo> activeOptimisations;
    private Map<String, OptimisationResult> results;

    public String startOptimisation(String config);
    public void stopOptimisation(String sessionKey);
    public OptimisationResult getResult(String sessionKey);
    private void handleSessionEvent(SessionEvent event);
}
```

## Data Classes to Extract

### OptimisationInfo.java
```java
public class OptimisationInfo {
    private String name;
    private String sessionKey;
    private String configuration;
    private OptimisationStatus status;
    private LocalDateTime startTime;
    // getters/setters
}
```

### OptimisationResult.java
```java
public class OptimisationResult {
    private double bestObjective;
    private String optimisedModel;
    private int totalEvaluations;
    private Duration elapsedTime;
    private List<ConvergencePoint> convergenceHistory;
    // getters/setters
}
```

### OptimisationStatus.java
```java
public enum OptimisationStatus {
    CONFIGURING("Configuring"),
    STARTING("Starting"),
    RUNNING("Running"),
    CONVERGED("Converged"),
    STOPPED("Stopped"),
    ERROR("Error");

    private final String displayName;
    // constructor and methods
}
```

## Implementation Steps

### Step 1: Extract Data Classes (Day 1)
1. Create `OptimisationInfo.java`
2. Create `OptimisationResult.java`
3. Create `OptimisationStatus.java`
4. Update references in main class

### Step 2: Extract Tree Manager (Day 2)
1. Create `OptimisationTreeManager.java`
2. Move all tree-related methods
3. Move tree event handlers
4. Update main class to use manager

### Step 3: Extract Config Manager (Day 3)
1. Create `OptimisationConfigManager.java`
2. Move configuration editor logic
3. Move validation logic
4. Connect to main class

### Step 4: Extract Progress Manager (Day 4)
1. Create `OptimisationProgressManager.java`
2. Move progress tracking logic
3. Move timer management
4. Integrate with main class

### Step 5: Extract Results Manager (Day 5)
1. Create `OptimisationResultsManager.java`
2. Move results display logic
3. Move comparison operations
4. Connect to main class

### Step 6: Extract Plot Manager (Day 6)
1. Create `OptimisationPlotManager.java`
2. Move convergence plot logic
3. Move plot data management
4. Integrate with main class

### Step 7: Extract Session Manager (Day 7)
1. Create `OptimisationSessionManager.java`
2. Move session tracking
3. Move optimisation lifecycle
4. Final integration

### Step 8: Cleanup and Testing (Day 8-9)
1. Remove dead code
2. Optimize imports
3. Add JavaDoc
4. Test all functionality
5. Performance testing

## Expected Outcomes

### Size Reduction
- **Before**: 2006 lines in one file
- **After**:
  - OptimisationWindow: ~400 lines
  - 6 managers: ~1350 lines total
  - 3 data classes: ~150 lines total
- **Net Result**: Same functionality, 65% better organization

### Quality Improvements
- ✅ Single Responsibility Principle
- ✅ Improved testability (each manager testable in isolation)
- ✅ Better code reuse
- ✅ Easier maintenance
- ✅ Clearer architecture

### Risk Mitigation
- Create feature branch: `refactor/optimisation-window`
- Maintain backwards compatibility
- Test each extraction step
- Keep original file as backup until complete

## Success Criteria
1. All existing functionality preserved
2. No new bugs introduced
3. All tests pass
4. Code coverage improved
5. Main class < 500 lines
6. Each manager < 300 lines

## Timeline
**Total Estimated Time**: 8-9 days
**Recommended Approach**: One manager per day with testing
**Review Points**: After each manager extraction