# Claude Development Notes

This file documents key implementation details and architectural decisions for the KalixGUI Java application.

## About Kalix

Kalix is an open source hydrological modeling platform with a Rust simulation engine and Java GUI. Key features:

- **Node-link architecture** for directional network-based hydrological modeling
- **Performance-first design** targeting 10x speed improvements over existing solutions
- **Visual model building** with synchronized graphical and text editing
- **Git-compatible formats** (TOML/JSON models, CSV data)
- **Cross-platform** support (Windows, macOS, Linux)

### Core Components
- **Simulation Engine**: Rust backend for performance and memory safety
- **GUI Application**: Java Swing with manager-based architecture
- **Model Formats**: Human-readable TOML/JSON with version control support
- **Communication**: JSON-based STDIO protocol between GUI and CLI

## Key Architecture Components

### Preference System (September 2025)
Hybrid preference system with portable configuration:
- **File-based preferences** (`kalix_prefs.json`): Shareable team settings
- **OS-based preferences**: Machine-specific UI state (window positions, etc.)
- **Pure Java JSON implementation**: No external dependencies
- **Cross-platform folder access**: System → Locate Preference File menu

Key files: `PreferenceManager.java`, `PreferenceKeys.java`

### Compressed Timeseries Format (.kaz/.kai)
Custom binary format using Gorilla compression:
- **Binary format (.kaz)**: Gorilla-compressed time series data
- **Metadata format (.kai)**: Human-readable CSV with series info
- **FlowViz integration**: Drag-and-drop support, format auto-detection
- **Critical fix**: Fixed decompression bugs with proper data point counting

Key files: `KalixTimeSeriesWriter.java`, `KalixTimeSeriesReader.java`, `GorillaCompressor.java`

### Manager Pattern Architecture
Comprehensive refactoring using manager pattern:

**Code Reductions:**
- PlotPanel: 910 → 246 lines (73% reduction)
- FlowVizWindow: 817 → 412 lines (50% reduction)

**Manager Classes:**
- `CoordinateDisplayManager` - Mouse hover display with binary search optimization
- `PlotInteractionManager` - Mouse interactions, zooming, panning, context menus
- `FlowVizMenuManager` - Menu system and keyboard shortcuts
- `FlowVizDataManager` - CSV/KAI import/export with progress tracking

**Architecture Benefits:**
- Callback-based communication for loose coupling
- Performance optimizations (60 FPS throttling, O(log n) lookups)
- Enhanced testability through separation of concerns

### Text Editor with RSyntaxTextArea
Enhanced text editor using manager pattern:
- **RSyntaxTextArea integration**: INI syntax highlighting, find/replace, go-to-line
- **Manager classes**: `TextSearchManager`, `TextNavigationManager`, `FileDropManager`
- **Code reduction**: EnhancedTextEditor reduced from 759 → 280 lines
- **Features**: Mark occurrences, current line highlighting, Ctrl+F/Ctrl+H shortcuts

### Node Styling and Navigation
Enhanced map visualization:
- **Theme-specific text styling**: Font size, colors, positioning per theme
- **Click-to-navigate**: Click nodes to scroll to definitions in editor
- **Smart interaction**: 5px tolerance for click vs drag detection
- **Mouse event fix**: Proper separation of click navigation and drag operations

Key files: `NodeTheme.java`, `TextCoordinateUpdater.java`

### Code Quality Improvements
Systematic refactoring addressing code smells:

**Key Improvements:**
- **MapRenderer extraction**: Separated rendering from interaction logic (348 lines)
- **UIConstants class**: Centralized magic numbers with documentation
- **SLF4J logging**: Replaced System.out with structured logging framework
- **Import organization**: Specific imports replacing wildcards
- **FlowVizActionManager**: Extracted action delegation logic (219 lines)

**Files Created:**
- `MapRenderer.java` - Stateless rendering operations
- `UIConstants.java` - Centralized constants with nested organization
- `FlowVizActionManager.java` - Action delegation and view management
- `logback.xml` - Logging configuration

## Build Commands

```bash
./gradlew build --no-daemon    # Build project
./gradlew run --no-daemon      # Run application
```

## Development Notes

### Manager Pattern
Key architectural pattern throughout the application:
- **Separation of concerns**: Each manager handles specific functionality
- **Callback communication**: Loose coupling through function-based interfaces
- **Enhanced testability**: Isolated components for better testing

### Text Editor Configuration
- **Syntax highlighting**: INI format with RSyntaxTextArea
- **Search integration**: Custom dialogs using SearchEngine API
- **Manager structure**: TextSearchManager, TextNavigationManager, FileDropManager

## Pending Tasks

1. **Bracket Matching Investigation** - RSyntaxTextArea bracket matching needs debugging
2. **File Loading Flicker** - Eliminate zoom flicker during file load operations
3. **Menu Separator** - Add separator before Exit in File menu

## Dependencies

- **RSyntaxTextArea**: Syntax highlighting and enhanced text editing
- **SLF4J + Logback**: Structured logging framework
- **FlatLaf**: Look and feel themes

## KalixCLI Communication

### Overview
JSON-based STDIO protocol for communicating with Rust backend. For detailed protocol specifications, message formats, and examples, see `KALIXCLI_DOCUMENTATION.md`.

### Key Components
- `SessionManager.java` - Core session lifecycle management
- `JsonSessionManager.java` - JSON protocol implementation
- `ProcessExecutor.java` - Process spawning and management
- `RunManager.java` - Multi-session GUI management

## Custom Themes

### Theme System
FlatPropertiesLaf-based custom themes with component-specific styling:

**Available Themes:**
- **Light**: Keylime (lime green), Lapland (nordic blue), Nemo (ocean blue/orange)
- **Dark**: Dracula, One Dark, Obsidian (purple accents)

### Technical Implementation
- **Properties files**: `/src/main/resources/themes/` directory
- **Custom properties**: MapPanel.background, MapPanel.gridlineColor, splitPaneDividerColor
- **Graceful fallback**: Error handling for corrupted theme files

Key files: `ThemeManager.java`, theme properties files

## Interactive Map Features

### Node Interaction System
Complete interactive map with bidirectional text synchronization:

**Key Features:**
- **Node selection**: Visual feedback with blue borders, Ctrl+click multi-select
- **Node dragging**: Single and multi-node dragging with position preservation
- **Text synchronization**: Map changes update editor coordinates, editor changes update map
- **Smart interaction**: 5px click tolerance, preserved selection on already-selected nodes

**Implementation Classes:**
- `MapInteractionManager` - Drag state and coordinate transformations
- `TextCoordinateUpdater` - Regex-based INI coordinate replacement
- Enhanced `HydrologicalModel` - Selection tracking and change notifications

**User Flow:**
1. Load model → map displays nodes
2. Select nodes → visual feedback
3. Drag nodes → coordinates update in editor
4. Edit coordinates in text → map updates automatically

## Architecture Overview

**Clean separation of concerns:**
- `KalixGUI` - Application coordination
- `EnhancedTextEditor` - Text editing with manager pattern
- Manager classes - Specific functionality domains
- Builder classes - UI construction
- CLI Integration - Session management and STDIO communication