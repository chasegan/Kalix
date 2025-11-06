# OptimisationWindow Refactoring Context

## Background
This refactoring was initiated after fixing an invisible plot lines bug in RunManager. A comprehensive code quality review identified OptimisationWindow.java as the worst offender in the codebase at 2006 lines.

## Refactoring Timeline

### Phase 1: Code Quality Review
- Analyzed entire KalixIDE codebase
- Identified OptimisationWindow.java (2006 lines) as highest priority
- Found 87 instances of System.out in production code
- Created 6-8 week improvement roadmap

### Phase 2: Planning
- Created detailed refactoring plan for OptimisationWindow
- Designed architecture: 9 managers + 3 data classes
- Followed successful RunManager refactoring pattern

### Phase 3: Extraction (COMPLETED)
Successfully extracted 12 new classes:
1. OptimisationInfo - Session tracking
2. OptimisationResult - Results with convergence history
3. OptimisationStatus - Status enum
4. OptimisationTreeManager - Tree operations
5. OptimisationConfigManager - Configuration editing
6. OptimisationProgressManager - Progress tracking
7. OptimisationResultsManager - Results display
8. OptimisationPlotManager - Convergence plots
9. OptimisationSessionManager - Session lifecycle
10. OptimisationTreeCellRenderer - Tree rendering

### Phase 4: Integration (TO BE COMPLETED)
- Wire managers into OptimisationWindow
- Remove old inline code
- Reduce from 2006 to ~450 lines

## Architecture Pattern

### Manager Pattern with Callbacks
```java
// Managers don't know about OptimisationWindow
manager.setOnEventOccurred(data -> {
    // OptimisationWindow handles coordination
});
```

### Single Responsibility
- Each manager handles ONE domain
- Average manager size: ~340 lines
- Clear interfaces and separation

### Consistent with RunManager
- Same successful pattern
- Proven 50-75% size reduction
- Improved testability

## Key Decisions Made

1. **Data Model Extraction First** - Created clean data structures before managers
2. **Session Management Complexity** - OptimisationSessionManager handles complex STDIO protocol
3. **GUI Builder Integration** - Kept OptimisationGuiBuilder separate, referenced by ConfigManager
4. **Plot Management** - Dedicated PlotManager for convergence visualization
5. **Progress Tracking** - Separate manager for timing and progress updates

## Technical Challenges Resolved

1. **API Compatibility** - Adapted to SessionManager.KalixSession API
2. **Async Operations** - Proper CompletableFuture handling in SessionManager
3. **State Management** - Clean separation between session state and UI state
4. **Callback Design** - Avoided circular dependencies through careful callback design

## Testing Considerations

After integration is complete:
1. Test optimisation creation workflow
2. Test configuration loading/saving
3. Test optimisation execution
4. Test results visualization
5. Test tree operations (rename, remove)
6. Test progress updates
7. Test plot updates

## Success Metrics

- ✅ All managers compile successfully
- ⏳ OptimisationWindow reduced from 2006 to ~450 lines
- ⏳ Clean separation of concerns
- ⏳ No circular dependencies
- ⏳ Consistent with codebase patterns

## Next Steps After Integration

1. Add unit tests for managers
2. Consider extracting UIConstants for magic numbers
3. Replace System.out with SLF4J logging
4. Apply pattern to other large files:
   - FlowVizWindow.java (817 lines)
   - PlotPanel.java (910 lines)
   - EnhancedTextEditor.java (759 lines)