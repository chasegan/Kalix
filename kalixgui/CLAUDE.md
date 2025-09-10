# Claude Development Notes

This file documents development work done with Claude to help maintain context and provide guidance for future sessions.

## About Kalix

### Platform Overview
Kalix is a next-generation open source hydrological modelling platform designed to deliver blazing-fast performance through cutting-edge algorithms and modern software architecture. The platform enables hydrologists and water resource engineers to create, simulate, and analyze complex water systems using an intuitive node-link network approach.

### Key Value Propositions
- **Performance-First Design**: Rust-based simulation engine optimized for speed (targeting 10x faster than comparable solutions)
- **Modern Workflow Integration**: Git-compatible text formats and AI-assisted model development
- **Cross-Platform Accessibility**: Native support for Windows, macOS, and Linux
- **Developer-Friendly**: Python bindings for scripting and automation
- **Visual Model Building**: Intuitive GUI with synchronized graphical and text editing

### Target Users
- **Hydrologists & Water Resource Engineers**: Watershed modeling, flood prediction, water resource planning
- **Research Scientists**: Climate impact studies, algorithm development, academic research
- **Consultants & Engineering Firms**: Client projects, regulatory compliance, impact assessments
- **Students & Educators**: Coursework, thesis research, educational demonstrations

### Core Modeling Approach
Kalix uses a **node-link architecture** for directional network-based hydrological modeling, supporting:
- **Node Types**: Rainfall-runoff models (Sacramento, GR4J), storage nodes, loss nodes, routing nodes, confluence nodes, user-defined nodes
- **Flow Simulation**: Accurate water flow calculations through the network
- **Model Calibration**: Advanced global optimization algorithms (Differential Evolution, DREAM, CMA-ES, SCE variants)
- **Data Processing**: Efficient handling of large time-series datasets

### Technology Stack
- **Core Engine**: Rust for memory safety and performance
- **Python Integration**: PyO3-based bindings for complete API access
- **GUI Framework**: Java (this application) with synchronized graphical and text editing
- **Model Formats**: TOML and JSON for human-readable, version-control-friendly model definitions
- **Data Formats**: Flexible CSV support with custom time-series compression

### Vision
To democratize high-performance hydrological modeling by providing an open, fast, and user-friendly platform that integrates seamlessly with modern software development workflows.

## Recent Major Changes

### Text Editor Enhancement (September 2024)

**Objective**: Replace JTextArea with RSyntaxTextArea and simplify the EnhancedTextEditor class.

#### Key Changes Made:

1. **Manager Pattern Implementation**
   - Extracted `TextSearchManager` for find/replace functionality using RSyntaxTextArea's SearchEngine API
   - Extracted `TextNavigationManager` for go-to-line functionality  
   - Extracted `FileDropManager` for drag-and-drop file handling
   - Reduced EnhancedTextEditor from ~759 lines to ~280 lines

2. **RSyntaxTextArea Integration**
   - Replaced `JTextPane` with `RSyntaxTextArea` for syntax highlighting
   - Replaced `JScrollPane` with `RTextScrollPane` for enhanced scrolling
   - Added INI syntax highlighting (`SyntaxConstants.SYNTAX_STYLE_INI`)
   - Enabled mark occurrences functionality (highlights similar text)
   - Added current line highlighting
   - Configured bracket matching (though may need investigation)

3. **Search Functionality**
   - Created custom search dialogs using RSyntaxTextArea's SearchEngine API
   - Added keyboard shortcuts: Ctrl+F (find), Ctrl+H (find & replace)
   - Added menu items in Edit menu with shortcuts displayed
   - Integrated with both toolbar and menu system

4. **Code Cleanup**
   - Removed font customization functionality completely
   - Removed line wrap settings and preferences
   - Cleaned up AppConstants of unused font and line wrap constants
   - Removed FontDialogManager class entirely

#### Files Modified:
- `EnhancedTextEditor.java` - Major refactoring, RSyntaxTextArea integration
- `TextSearchManager.java` - New file, search functionality
- `TextNavigationManager.java` - New file, go-to-line functionality  
- `FileDropManager.java` - New file, drag-and-drop handling
- `MenuBarBuilder.java` - Added find/replace menu items, removed font menu
- `KalixGUI.java` - Updated search integration, removed font functionality
- `AppConstants.java` - Cleaned up unused constants

#### Current Features:
- ✅ INI syntax highlighting
- ✅ Find and replace dialogs with regex support
- ✅ Mark occurrences (highlights matching text)
- ✅ Current line highlighting
- ✅ Go-to-line functionality
- ✅ Undo/redo system
- ✅ Drag and drop file support
- ⚠️ Bracket matching (enabled but may need investigation)

## Build Commands

Standard Gradle commands:
```bash
./gradlew build --no-daemon    # Build project
./gradlew run --no-daemon      # Run application
```

## Development Notes

### RSyntaxTextArea Configuration
The text editor is configured in `EnhancedTextEditor.initializeComponents()`:
- Syntax style: `SYNTAX_STYLE_INI`
- Mark occurrences delay: 300ms
- Current line highlight color: light blue (232, 242, 254)
- Bracket matching enabled (may need debugging)

### Search Integration
- Uses RSyntaxTextArea's `SearchEngine` and `SearchContext` APIs
- Custom dialogs provide find/replace functionality
- Keyboard shortcuts work both in editor and via menu

### Manager Pattern
The editor uses a manager pattern to separate concerns:
- `TextSearchManager` - Find/replace functionality
- `TextNavigationManager` - Go-to-line functionality
- `FileDropManager` - Drag-and-drop file handling

Each manager is initialized in `EnhancedTextEditor.initializeManagers()` and accessible via getter methods.

## Pending Tasks

1. **Bracket Matching Investigation** - RSyntaxTextArea's bracket matching was enabled but doesn't seem to work as expected. May need:
   - Different syntax highlighting mode
   - Custom bracket matching implementation
   - Theme/color configuration

## Dependencies

Key libraries used:
- RSyntaxTextArea (org.fife.ui.rsyntaxtextarea) - Syntax highlighting text area
- RTextArea (org.fife.ui.rtextarea) - Enhanced text area components

## Future Development Considerations

When starting new features:
1. The text editor is now well-structured with the manager pattern
2. RSyntaxTextArea provides many built-in features to leverage
3. Search functionality is fully integrated and extensible
4. The codebase is cleaner with unnecessary font/line-wrap code removed

## KalixCLI Sessions and STDIO Communication

### Overview
The GUI communicates with the kalixcli backend hydrological modeling engine via a JSON-based STDIO protocol. This enables the GUI to initiate modeling tasks, monitor progress, retrieve results, and interrupt long-running operations while keeping computational data resident in kalixcli memory.

### Protocol Specification
The communication protocol is fully documented in `kalixcli-stdio-spec.md` which defines:
- **Stateful Sessions**: kalixcli maintains model state and results in memory throughout the session
- **JSON Message Format**: All communication uses structured JSON over STDIO
- **Session Management**: Each kalixcli process generates a unique session_id for tracking
- **Interruptible Operations**: Long-running tasks can be interrupted by the frontend
- **State Machine**: kalixcli operates in "ready" and "busy" states

### Key Components

#### Session Management Classes
- `SessionManager.java` - Core session lifecycle management
- `JsonSessionManager.java` - JSON protocol implementation 
- `SessionCommunicationLog.java` - Communication logging and debugging
- `CliTaskManager.java` - High-level task coordination
- `SessionsWindow.java` - GUI for managing multiple sessions

#### CLI Integration Classes  
- `KalixCliProtocol.java` - Protocol message definitions and parsing
- `KalixCliLocator.java` - Locates kalixcli executable on system
- `ProcessExecutor.java` - Spawns and manages kalixcli processes
- `CliLogger.java` - Logging framework for CLI operations

#### UI Components
- `SessionStatusPanel.java` - Real-time session status display
- `SessionsWindow.java` - Multi-session management interface

### Message Types
The protocol supports several message types:

#### System Messages (kalixcli → GUI)
- `ready` - kalixcli ready for commands, includes available commands and current state
- `busy` - Command execution started
- `progress` - Task progress updates with percentage and time estimates
- `result` - Command completion with results
- `stopped` - Task interrupted cleanly
- `error` - Error occurred during execution
- `log` - Informational logging

#### Command Messages (GUI → kalixcli)
- `command` - Execute specific kalixcli command with parameters
- `stop` - Interrupt currently executing task
- `query` - Request information about current state
- `terminate` - End session gracefully

### Core Commands
- `load_model_file` - Load hydrological model from file path
- `load_model_string` - Load model from INI string (used by "Run Model" feature)
- `run_simulation` - Execute model simulation (interruptible)
- `test_progress` - Test command for progress and interruption

### Session Lifecycle
1. GUI spawns kalixcli process via `ProcessExecutor`
2. kalixcli generates unique session_id and sends "ready" message
3. GUI receives session_id and available commands
4. GUI sends commands, kalixcli executes and reports progress
5. Long-running tasks can be interrupted via "stop" command
6. Session continues until explicit termination

### Integration Points
- **KalixGUI.runModelFromMemory()** - Loads current editor text as model and runs simulation
- **SessionsWindow** - Displays all active sessions with real-time status
- **CliTaskManager** - Coordinates between GUI actions and session management
- **Status Bar** - Shows current operation status and progress

### Current Features
- ✅ Model loading from editor text
- ✅ Simulation execution with progress tracking
- ✅ Session interruption/cancellation
- ✅ Multi-session management
- ✅ Real-time progress updates
- ✅ Communication logging
- ✅ Error handling and recovery

### Configuration
- kalixcli executable location auto-detection
- Session timeout and retry settings
- Progress update intervals
- Communication logging levels

## Architecture Notes

The application follows a clean separation of concerns:
- `KalixGUI` - Main application coordinator
- `EnhancedTextEditor` - Text editing component with managers
- Manager classes - Specific functionality (search, navigation, file drop)
- Builder classes - UI construction (MenuBarBuilder, ToolBarBuilder)
- CLI Integration - Session management and kalixcli communication
- Protocol Layer - STDIO JSON message handling