# KalixCLI STDIO Protocol Specification

## Overview

This document specifies a STDIO protocol for kalixcli to enable robust session management and interactive communication with the Kalix GUI. The protocol defines standardized termination messages that allow the GUI to properly manage CLI sessions, distinguish between different command types, and handle various termination scenarios.

## Problem Statement

The current CLI integration faces several challenges:
- **Race conditions**: Process termination timing vs. output reading
- **Ambiguous states**: Cannot distinguish session types from exit codes alone
- **Missed output**: Final messages may be lost when processes terminate
- **Error handling**: Difficult to differentiate error types and recovery options

## Solution: STDIO Termination Protocol

### Core Principle
Each kalixcli command/session should emit a **standardized termination message** to STDOUT before termination, providing clear state information to the GUI.

## Message Format

```
KALIX_<TYPE>_<STATUS>[ : <additional_info>]
```

### Message Components
- **`KALIX_`**: Fixed prefix for easy parsing
- **`<TYPE>`**: Category of operation (SESSION, COMMAND, ERROR)
- **`<STATUS>`**: Specific state (READY, COMPLETE, ENDING, etc.)
- **`<additional_info>`**: Optional context (error details, session ID, etc.)

## Message Types

### 1. Session Messages (Long-Running Processes)

#### `KALIX_SESSION_READY`
- **When**: Model run completes successfully, session remains active for queries
- **Behavior**: Process stays alive, ready to accept query commands
- **GUI Action**: Enable query buttons, show "Ready for analysis" status

```bash
# Example output:
Running simulation...
Progress: 100%
Simulation completed!
KALIX_SESSION_READY
Ready for queries. Session ID: sim_20240315_143022
```

#### `KALIX_SESSION_ENDING`
- **When**: Session is about to terminate gracefully
- **Behavior**: Process will exit with code 0 shortly after this message
- **GUI Action**: Clean up session resources, update UI state

```bash
# Example output:
Saving session state...
Cleanup complete.
KALIX_SESSION_ENDING
```

### 2. Command Messages (One-Shot Operations)

#### `KALIX_COMMAND_COMPLETE`
- **When**: One-shot command finishes successfully
- **Behavior**: Process will terminate normally
- **GUI Action**: Hide progress bar, show completion status

```bash
# Example output:
CLI version: 2.1.4
Build date: 2024-01-15
KALIX_COMMAND_COMPLETE
```

### 3. Error Messages

#### `KALIX_ERROR_FATAL`
- **When**: Unrecoverable error occurs
- **Behavior**: Process cannot continue, will terminate
- **GUI Action**: Show error dialog, clean up resources

```bash
# Example output:
Error: Invalid model syntax on line 45
KALIX_ERROR_FATAL: Cannot continue session
```

#### `KALIX_ERROR_RECOVERABLE`
- **When**: Warning or non-fatal error
- **Behavior**: Session/command continues with degraded functionality
- **GUI Action**: Show warning, log issue, continue operation

```bash
# Example output:
Warning: Parameter 'alpha' not specified, using default value 0.5
KALIX_ERROR_RECOVERABLE: Using default parameters
```

## Implementation Guidelines

### 1. Message Placement
- Messages should be the **last output** before process termination/state change
- Always write to **STDOUT** (not STDERR) for consistent parsing
- Ensure message is **flushed** immediately after writing

### 2. Timing Requirements
- For `KALIX_SESSION_ENDING`: Process should terminate within **5 seconds**
- For `KALIX_COMMAND_COMPLETE`: Process should terminate within **2 seconds**
- For `KALIX_ERROR_FATAL`: Process should terminate within **3 seconds**

### 3. Backward Compatibility
- These messages are **additive** - existing output format preserved
- GUI will use messages as primary signal, exit codes as fallback
- Non-supporting versions will work with exit code detection

## Command-Specific Implementations

### Model Run Commands
```bash
kalixcli run model.toml
# Output:
# Loading model: model.toml
# Running simulation...
# Progress: 0%
# Progress: 1%
# ...
# Progress: 100%
# Simulation completed!
# KALIX_SESSION_READY
# Ready for queries. Session ID: run_20240315_143022
```

### Test/Simulation Commands
```bash
kalixcli test --sim-duration-seconds=8
# Output:
# Running simulation for 8 seconds...
# Progress: 0%
# ...
# Progress: 100%
# Simulation completed!
# KALIX_COMMAND_COMPLETE
```

### Version/Info Commands
```bash
kalixcli --version
# Output:
# kalixcli version 2.1.4
# KALIX_COMMAND_COMPLETE
```

### API Discovery Commands
```bash
kalixcli get-api
# Output:
# {
#   "commands": [...],
#   "version": "2.1.4"
# }
# KALIX_COMMAND_COMPLETE
```

## Error Scenarios

### Fatal Model Error
```bash
kalixcli run invalid_model.toml
# Output:
# Loading model: invalid_model.toml
# Error: Syntax error on line 15: Missing closing bracket
# KALIX_ERROR_FATAL: Model validation failed
```

### Resource Exhaustion
```bash
kalixcli run large_model.toml
# Output:
# Loading model: large_model.toml
# Initializing simulation...
# Error: Insufficient memory for simulation matrix
# KALIX_ERROR_FATAL: Out of memory
```

### Recoverable Warning
```bash
kalixcli run model_with_warnings.toml
# Output:
# Loading model: model_with_warnings.toml
# Warning: Parameter 'max_iterations' not specified, using default: 1000
# Running simulation...
# Progress: 100%
# KALIX_ERROR_RECOVERABLE: Completed with warnings
# KALIX_SESSION_READY
```

## GUI Implementation Benefits

### 1. Robust Session Management
```java
public class SessionManager {
    public void processOutput(String line, String sessionId) {
        if (line.startsWith("KALIX_SESSION_READY")) {
            markSessionReady(sessionId);
            enableQueryInterface(sessionId);
        } else if (line.startsWith("KALIX_COMMAND_COMPLETE")) {
            markCommandComplete(sessionId);
            scheduleCleanup(sessionId, 2000); // 2 second cleanup delay
        } else if (line.startsWith("KALIX_ERROR_FATAL")) {
            handleFatalError(sessionId, extractErrorInfo(line));
        }
    }
}
```

### 2. Predictable Resource Management
- **No race conditions**: Know exactly when processes will terminate
- **Clean shutdown**: Proper resource cleanup timing
- **Error recovery**: Distinguish between fatal and recoverable errors

### 3. Better User Experience
- **Clear status messages**: "Model ready for analysis" vs "Command completed"
- **Appropriate UI states**: Enable/disable controls based on session state
- **Error handling**: Show specific error messages and recovery options

## Testing Strategy

### Unit Tests
- Verify correct message format generation
- Test message parsing logic
- Validate timing requirements

### Integration Tests
- Test full command lifecycle with message protocol
- Verify GUI state transitions based on messages
- Test error scenario handling

### Regression Tests
- Ensure backward compatibility with existing output
- Test fallback to exit code detection when messages absent

## Migration Plan

### Phase 1: Core Implementation
1. Add message generation to kalixcli command completion points
2. Implement message parsing in GUI
3. Test with existing commands

### Phase 2: Enhanced Error Handling
1. Add detailed error categorization
2. Implement recoverable error handling
3. Add session ID tracking

### Phase 3: Advanced Features
1. Add session metadata in messages
2. Implement query result formatting
3. Add progress estimation improvements

## Examples for Common Operations

### Interactive Model Development Workflow
```bash
# User clicks "Run Model" in GUI with unsaved changes
kalixcli run -
# (GUI sends model text via STDIN)
# Loading model from stdin...
# Model validation successful
# Running simulation...
# Progress: 100%
# Simulation completed!
# KALIX_SESSION_READY: Ready for analysis queries
# (Session remains active for result queries)
```

### Background Analysis Session
```bash
# Long-running analysis session
kalixcli analyze --background
# Starting background analysis...
# KALIX_SESSION_READY: Analysis session active
# (GUI can now send query commands to this session)
```

### Session Cleanup
```bash
# User closes GUI or ends session
kalixcli session --end abc123
# Ending session abc123...
# Saving intermediate results...
# KALIX_SESSION_ENDING: Session terminated by user
```

This protocol provides a robust foundation for interactive CLI session management while maintaining backward compatibility and enabling rich GUI integration.