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

### FlowViz Right-Click Context Menu and CSV Export (September 2025)

**Objective**: Add right-click context menu to FlowViz plot area with CSV export functionality and refactor code for better organization.

#### Key Changes Made:

1. **Right-Click Context Menu Implementation**
   - **PlotPanel.setupContextMenu()** - Creates popup menu with zoom and export options
   - **Context Menu Items**: Zoom In, Zoom Out, Zoom to Fit, Save Data...
   - **Smart Activation**: Menu only appears when right-clicking within plot area
   - **Integrated with Existing Functions**: Reuses existing zoom functionality

2. **CSV Export Functionality**
   - **TimeSeriesCsvExporter Class** - New utility class in `com.kalix.gui.io` package
   - **Multi-column Format**: "Datetime" column + one column per time series
   - **Adaptive DateTime Formatting**:
     - Whole days (midnight): `YYYY-MM-DD` format
     - With time components: `YYYY-MM-DDThh:mm:ss` format
   - **Missing Value Handling**: NaN values represented as empty CSV cells
   - **Proper CSV Escaping**: Handles special characters in series names

3. **Code Refactoring and Organization**
   - **Separated Concerns**: Moved CSV logic from PlotPanel to dedicated exporter class
   - **Improved Maintainability**: ~80 lines of CSV code moved to reusable utility
   - **Better Error Handling**: Enhanced validation and user feedback
   - **Thread-Safe Design**: Stateless utility class suitable for concurrent use

4. **Comprehensive Documentation**
   - **Extensive Javadoc**: Class and method documentation with usage examples
   - **Future Extensibility**: ExportOptions class for potential customization
   - **Code Examples**: Documented CSV format and usage patterns

#### Files Modified:
- `PlotPanel.java` - Added context menu, refactored to use new exporter, enhanced Javadoc
- `TimeSeriesCsvExporter.java` - **New file** - Dedicated CSV export utility class

#### Current Features:
- ✅ Right-click context menu in plot area
- ✅ CSV export with proper datetime formatting
- ✅ All unique timestamps across multiple time series
- ✅ Missing value handling (empty cells for NaN)
- ✅ Proper CSV field escaping
- ✅ File save dialog with CSV filter
- ✅ Success/error user feedback
- ✅ Clean code separation and documentation

#### Technical Implementation:
- **Context Menu**: JPopupMenu triggered on right-click within plot bounds
- **CSV Structure**: Chronologically sorted timestamps with aligned series data
- **Data Collection**: TreeSet for automatic timestamp sorting and deduplication
- **Export Process**: FileWriter with proper resource management
- **Error Handling**: IOException and IllegalArgumentException handling

### Node Text Styling and Click Navigation (September 2025)

**Objective**: Enhance node visualization with themed text styling and implement click-to-navigate functionality between map and editor.

#### Key Changes Made:

1. **NodeTheme Text Styling Enhancement**
   - Added `TextStyle` inner class to `NodeTheme` with configurable properties:
     - Font size, text color, Y offset positioning
     - Background color and alpha transparency 
   - Updated all four themes (Vibrant, Earth, Ocean, Sunset) with unique text styling
   - Each theme now has distinct visual characteristics for node labels

2. **Enhanced MapPanel Text Rendering**
   - Modified `drawNodeText()` to use theme-specific styling instead of hardcoded values
   - Removed `TEXT_OFFSET_Y` constant in favor of dynamic theme-based positioning
   - Text properties now sourced from `nodeTheme.getCurrentTextStyle()`

3. **Node Click Navigation Implementation**
   - **TextCoordinateUpdater.scrollToNode()** - Finds and scrolls to node definitions in editor
   - **MapInteractionManager.handleNodeClick()** - Delegates navigation to text updater
   - **Enhanced mouse handling** - Smart click detection (≤5px tolerance) vs drag operations
   - **Deferred drag start** - Only begins drag on actual mouse movement, preserving click detection

4. **Fixed Mouse Event Logic**
   - **Root Issue**: `startDrag()` called immediately in `mousePressed()` prevented click navigation
   - **Solution**: Moved drag initiation to `mouseDragged()` event for actual movement detection
   - **Result**: Clean separation between clicks (navigate) and drags (move nodes)

#### Files Modified:
- `NodeTheme.java` - Added TextStyle class and updated theme definitions with text styling
- `MapPanel.java` - Enhanced text rendering and implemented click navigation logic
- `TextCoordinateUpdater.java` - Added scrollToNode() method for editor navigation
- `MapInteractionManager.java` - Added handleNodeClick() for delegated navigation

#### Current Features:
- ✅ Theme-specific text styling (font size, color, positioning, background)
- ✅ Node click navigation to editor definitions
- ✅ Smart click vs drag detection (5-pixel tolerance)
- ✅ Preserved existing node selection and dragging behavior
- ✅ Editor focus management and caret positioning

#### Text Styling Configuration:
- **VIBRANT**: Black text, 10px, white bg (180α), 15px offset
- **EARTH**: Brown text, 11px, beige bg (200α), 16px offset  
- **OCEAN**: Navy text, 10px, alice blue bg (190α), 14px offset
- **SUNSET**: Saddle brown text, 11px, cornsilk bg (210α), 17px offset

### Text Editor Enhancement (September 2025)

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

2. **Eliminate File Loading Flicker** - Currently when loading files, there's a momentary flicker where nodes appear at default zoom before zoom-to-fit is applied. Would require refactoring the model update/repaint sequence to batch operations or suppress intermediate repaints.

3. **Add a separator before 'Exit' in the File menu** - The Exit menu item should have a separator before it to properly group it as the final action in the File menu. This involves modifying the proxy menu logic in `MenuBarBuilder.java` without creating extra separators between recent file items.

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

### Session Management Details

#### Session Persistence Strategy
Sessions are kept alive after model completion to preserve results in kalixcli memory for subsequent queries. This allows users to:
- Retrieve different result datasets (flows, water balance, convergence metrics)
- Query simulation results without re-running models
- Maintain multiple completed models simultaneously for comparison

#### Session ID Generation
- Each model run creates a unique session with auto-generated IDs: `session-1`, `session-2`, etc.
- Session IDs are generated by `SessionManager.generateSessionId()` using an incrementing counter
- Previously used fixed `"new-session"` ID causing session collisions (fixed September 2025)

#### Thread Pool Management
- **Critical Fix (September 2025)**: All session operations now use dedicated thread pool from `ProcessExecutor`
- Previously used common ForkJoinPool causing thread exhaustion after 3-4 model runs
- Each session creates 2 monitoring threads (stdout/stderr) plus command execution threads
- Dedicated `newCachedThreadPool` prevents resource conflicts between sessions

#### Session States and Lifecycle
1. **STARTING** - Process launching
2. **RUNNING** - Executing commands (load model, run simulation)  
3. **READY** - Completed tasks, ready for queries
4. **ERROR** - Encountered errors
5. **TERMINATED** - Explicitly closed

#### Key Implementation Notes
- Sessions remain in READY state after successful completion (not terminated)
- RunModelProgram handles JSON protocol messages during model execution
- READY messages after simulation completion are handled by SessionManager generically
- Each session maintains communication logs for debugging
- Sessions accumulate but processes are lightweight

### Configuration
- kalixcli executable location auto-detection
- Session timeout and retry settings
- Progress update intervals
- Communication logging levels

### Custom Theme Development (September 2025)

**Objective**: Expand theme variety with custom properties-based themes and enhance theme system with custom component theming.

#### Key Themes Implemented:

1. **Obsidian Theme** (Dark)
   - **Deep blacks**: `#1e1e1e`, `#1a1a1a`, `#0d1117` for maximum contrast
   - **Purple accents**: `#8b5cf6`, `#7c3aed` for focus states and buttons
   - **High contrast text**: `#e6e6e6`, `#f0f6fc` for excellent readability
   - **Selection colors**: `#3d2a5c` for subtle purple selection
   - **Split pane dividers**: `#30363d` properly themed for dark backgrounds

2. **Keylime Theme** (Light)
   - **Clean whites**: `#ffffff`, `#fafafa` with subtle light grays
   - **Lime green accents**: `#65a30d`, `#84cc16` for fresh, energetic feel
   - **Selection colors**: `#d4ff8f`, `#ecfccb` in lime green tones
   - **Professional appearance**: Clean and bright for productivity

3. **Lapland Theme** (Light)
   - **Nordic blue base**: `#f1f5f9`, `#f8fafc` for crisp, cool appearance
   - **Custom backgrounds**: Subtle pale blue editor (`#f8fbff`), very pale pink map (`#fefbfa`)
   - **Arctic blue accents**: `#2563eb`, `#3b82f6` for Scandinavian aesthetic
   - **Ultra-subtle colors**: Barely perceptible but distinctive

4. **Nemo Theme** (Light)
   - **Ocean blues**: `#e6f3ff`, `#cce7ff`, `#b3e5fc` for underwater atmosphere
   - **Clownfish orange**: `#ff6f00`, `#ffcc80` for vibrant coral reef accents
   - **Sandy backgrounds**: `#fff8e1` editor, `#fff3e0` map for ocean floor feel
   - **Deep sea text**: `#0d3d56`, `#1a4a5c` for excellent readability
   - **Custom gridlines**: `#d0d0d0` for better visibility in ocean theme

#### Technical Implementation:

1. **FlatPropertiesLaf Integration**
   - Used `FlatPropertiesLaf` for clean, maintainable custom theme implementation
   - Properties files in `/src/main/resources/themes/` directory
   - Proper error handling with fallback to standard themes
   - Theme-specific comments and organization

2. **Custom Component Properties**
   - **MapPanel.background**: Custom map background colors per theme
   - **MapPanel.gridlineColor**: Theme-specific gridline colors
   - **Component.splitPaneDividerColor**: Proper split pane divider theming
   - Enhanced MapPanel to check UIManager for custom properties first

3. **Theme Organization**
   - **Light themes**: Light → Keylime → Lapland → Nemo (grouped together)
   - **Dark themes**: Dracula → One Dark → Obsidian (streamlined selection)
   - **Removed themes**: "Dark" and "Carbon" for cleaner, more distinctive options

#### Files Modified:
- **Theme Properties**: `obsidian-theme.properties`, `keylime-theme.properties`, `lapland-theme.properties`, `finding-nemo-theme.properties`
- **ThemeManager.java**: Added FlatPropertiesLaf support and custom theme loading
- **MapPanel.java**: Enhanced to support custom background and gridline colors
- **AppConstants.java**: Updated AVAILABLE_THEMES array with new themes

#### Current Theme Features:
- ✅ **7 distinctive themes**: 4 light themes, 3 dark themes
- ✅ **Properties-based custom themes**: Clean, maintainable approach
- ✅ **Custom component theming**: Map backgrounds, gridlines, split panes
- ✅ **Error handling**: Graceful fallback to standard themes
- ✅ **Theme integration**: Works with existing theme-aware components
- ✅ **Streamlined selection**: Removed redundant themes for better UX

#### Theme Color Palettes:
- **Obsidian**: Deep blacks with purple highlights
- **Keylime**: Clean whites with lime green energy
- **Lapland**: Nordic blues with subtle warm/cool background contrast
- **Nemo**: Underwater ocean blues with coral orange accents

## Interactive Map Implementation (September 2025)

**Objective**: Implement complete interactive map functionality with node selection, dragging, and bidirectional text synchronization.

### Key Features Implemented

#### Phase 1: Node Selection System
- **Visual Selection**: Selected nodes display blue 3px borders vs black 1px for unselected
- **Multi-Selection**: Ctrl+click to add/remove nodes from selection
- **Hit Testing**: Accurate detection of nodes under mouse cursor (20px constant screen size)
- **Selection Management**: Click empty space to clear all selections
- **Model Integration**: Selection state tracked in `HydrologicalModel` with change notifications

#### Phase 2: Node Dragging System  
- **Single Node Dragging**: Click and drag individual nodes
- **Multi-Node Dragging**: Drag multiple selected nodes simultaneously maintaining relative positions
- **Improved UX**: Smart selection preservation - clicking selected nodes preserves selection instead of clearing
- **Real-time Feedback**: Node positions update live during drag operations
- **Coordinate Transformation**: Proper screen-to-world coordinate conversion accounting for zoom/pan

#### Phase 3: Bidirectional Text Synchronization
- **Map-to-Text**: Dragging nodes automatically updates `loc = x, y` values in text editor
- **Text-to-Map**: Editing text coordinates updates node positions on map
- **Format Preservation**: Regex-based replacement preserves INI formatting and comments
- **Loop Prevention**: Prevents infinite update cycles between text and model changes
- **Precision Control**: Coordinates formatted to 2 decimal places

### Technical Architecture

#### New Classes Created
- **`MapInteractionManager`** - Handles drag state, coordinate transformations, multi-node updates
- **`TextCoordinateUpdater`** - Regex-based INI coordinate replacement with format preservation
- **Model Enhancements** - Added selection tracking, coordinate update methods, new event types

#### Key Implementation Details
- **Selection Logic**: Preserves selection when clicking already-selected nodes (no accidental deselection)
- **Drag State Management**: Tracks original positions, calculates delta movements, commits changes on release
- **Text Synchronization**: Uses regex pattern `(\[node\.name\][^\[]*?loc\s*=\s*)([0-9.-]+)\s*,\s*([0-9.-]+)` to locate and replace coordinates
- **Thread Safety**: Proper Swing EDT handling for all UI updates
- **Error Handling**: Graceful handling of parsing errors and coordinate update failures

#### Files Modified
- `MapPanel.java` - Added interaction handling, hit testing, selection mouse handlers
- `HydrologicalModel.java` - Added selection tracking, coordinate update methods, new event types  
- `ModelChangeEvent.java` - Added NODE_SELECTED, NODE_DESELECTED, SELECTION_CLEARED event types
- `KalixGUI.java` - Integrated text synchronization setup

### User Interaction Flow
1. **Load Model**: Text editor content automatically updates map visualization
2. **Select Nodes**: Left-click to select single node, Ctrl+click for multi-select
3. **Drag Nodes**: Click any selected node and drag - all selected nodes move together
4. **Text Sync**: Coordinate changes automatically update `loc = x, y` in text editor
5. **Seamless Integration**: Works with existing zoom, pan, auto-zoom-to-fit functionality

### Current Features
- ✅ Interactive node selection with visual feedback
- ✅ Multi-node dragging with improved UX
- ✅ Bidirectional text synchronization 
- ✅ Mouse wheel zoom-at-cursor
- ✅ Map panning via drag
- ✅ Auto zoom-to-fit on file load and 0→>0 node transitions
- ✅ Real-time model statistics in status bar
- ✅ Complete preservation of INI formatting during coordinate updates

## Architecture Notes

The application follows a clean separation of concerns:
- `KalixGUI` - Main application coordinator
- `EnhancedTextEditor` - Text editing component with managers
- Manager classes - Specific functionality (search, navigation, file drop)
- Builder classes - UI construction (MenuBarBuilder, ToolBarBuilder)
- CLI Integration - Session management and kalixcli communication
- Protocol Layer - STDIO JSON message handling