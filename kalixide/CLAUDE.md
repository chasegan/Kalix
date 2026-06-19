# Claude Development Notes

This file documents key implementation details and architectural decisions for the KalixIDE Java application.

## About Kalix

Kalix is an open source hydrological modeling platform with a Rust simulation engine and Java IDE. Key features:

- **Node-link architecture** for directional network-based hydrological modeling
- **Performance-first design** targeting 10x speed improvements over existing solutions
- **Visual model building** with synchronized graphical and text editing
- **Git-compatible formats** (TOML/JSON models, CSV data)
- **Cross-platform** support (Windows, macOS, Linux)

### Core Components
- **Simulation Engine**: Rust backend for performance and memory safety
- **IDE Application**: Java Swing with manager-based architecture
- **Model Formats**: Human-readable TOML/JSON with version control support
- **Communication**: JSON-based STDIO protocol between IDE and CLI

## Key Architecture Components

### Preference System (September 2025)
Hybrid preference system with portable configuration:
- **File-based preferences** (`kalix_prefs.json`): Shareable team settings
- **OS-based preferences**: Machine-specific UI state (window positions, etc.)
- **Pure Java JSON implementation**: No external dependencies
- **Cross-platform folder access**: System → Locate Preference File menu

Key files: `PreferenceManager.java`, `PreferenceKeys.java`

### Terminal Launcher (System → Terminal)
Cross-platform "open a terminal at this folder" feature, designed to be call-site agnostic so it can be driven from the System menu (current model's folder) or the file-tree context menu (any clicked path).
- **`TerminalLauncher`**: Swing-free, no app state — a pure function of its `File` argument. `openTerminalAt(File)` resolves the path via `resolveFolder` (file→parent, null→home) and dispatches on the `Platform` enum, returning the folder actually opened.
- **`TerminalActions.launchAsync(parent, pathOrFolder, status)`**: the reusable UI wrapper — runs the launcher off the EDT (`SwingWorker`), then reports status / shows an error dialog. Every call site uses this.
- **Availability**: `isOnPath` scans `$PATH` directly (no `which`/`where`/`--help` subprocess) — eliminates pipe-buffer deadlock and accidental GUI launches by construction.
- **macOS**: `osascript` with `on run argv` + `quoted form of` — the path/activation are passed as argv items, never interpolated into the script, so apostrophes/spaces are safe. Honors `FILE_MACOS_TERMINAL_APP` (Terminal/iTerm scripted; others via `open -a`).
- **Activation command**: per-platform `FILE_TERMINAL_ACTIVATION_{WINDOWS,MACOS,LINUX}` keys (e.g. `conda activate env`), resolved by `getActivationCommand()`. Windows falls back to the legacy `FILE_PYTHON_TERMINAL_COMMAND` (`extractLegacyActivation`) when unset. Linux `exec "${SHELL:-bash}"` respects the user's shell.

Key files: `TerminalLauncher.java`, `TerminalActions.java`; tests in `TerminalLauncherTest.java`

### Pixie Format (.pxt/.pxb)
Custom binary format using Gorilla compression:
- **Binary format (.pxb)**: Gorilla-compressed time series data
- **Metadata format (.pxt)**: Human-readable CSV with series info
- **FlowViz integration**: Drag-and-drop support, format auto-detection
- **Critical fix**: Fixed decompression bugs with proper data point counting

Key files: `PixieWriter.java`, `PixieReader.java`, `GorillaCompressor.java`

### Source Result CSV Format (.res.csv)
eWater Source result export: an extended header before a normal date-indexed CSV body, split by marker lines `EOM` / `EOC` (then series count + one attribute row per series) / `EOH` (data follows).
- **Marker-driven** (preamble varies by version); per-file `Missing data value` → NaN; header buffered, data streamed.
- **Double-extension trap**: test `.res.csv` *before* `.csv` everywhere (dispatch, file filters, `InputDataRegistry` reader order).
- **Write** is wired into the plot "Save Data" only; the Run Manager "Save Results" delegates to the kalixcli engine, so it can't emit `.res.csv`.

Key files: `SourceResCsvImporter/Exporter/Header`, `SourceResCsvHeaderReader`, `ResCsvSegmenter`, `SourceResCsvFormat`.

### Dataset Series Naming & Tree Hierarchy
`OutputsTreeBuilder` splits series names on `.` into tree levels. Two separated responsibilities:
- **Segmentation (per-format)**: importers emit raw hierarchy segments on `NamedSeries.path()` — flat formats use `NamedSeries.dotted()` (split on `.`); res.csv uses `ResCsvSegmenter` (`dedupeConsecutive([WaterFeatureType, Site] + Structure.split("@"))`).
- **Sanitise + join (generic)**: `DatasetLoaderManager.composeDatasetSeriesName` sanitises each segment (`[^a-zA-Z0-9]→_`) and joins with `.`; segments are opaque (no re-split).

Loaded names carry no `file.<filename>` prefix, so they collate with runs by name; the file stays distinct via `DatasetSeries.datasetId` (absolute path). Note: run output names are *not* sanitised (engine keys off them) but dataset names are — so collation matches for identifier-style names, not for node names with spaces/punctuation.

### Manager Pattern Architecture
Comprehensive refactoring using manager pattern:

**Code Reductions:**
- PlotPanel: 910 → 246 lines (73% reduction)
- FlowVizWindow: 817 → 412 lines (50% reduction)

**Manager Classes:**
- `CoordinateDisplayManager` - Mouse hover display with binary search optimization
- `PlotInteractionManager` - Mouse interactions, zooming, panning, context menus
- `FlowVizMenuManager` - Menu system and keyboard shortcuts
- `FlowVizDataManager` - CSV/Pixie import/export with progress tracking

**Architecture Benefits:**
- Callback-based communication for loose coupling
- Performance optimizations (60 FPS throttling, O(log n) lookups)
- Enhanced testability through separation of concerns

### Text Editor Component Hierarchy
All text editor components extend a base class hierarchy for consistent behavior:

```
RSyntaxTextArea (library)
    └── KalixTextArea (abstract base)
            ├── KalixIniTextArea (INI file editing)
            └── KalixPlainTextArea (plain text/logs)
```

**KalixTextArea** (base class) provides:
- Monospace font configuration via FontManager
- Windows cursor alignment fix via `addNotify()` override
- Common settings (anti-aliasing, tab handling)

**KalixIniTextArea** adds:
- Kalix INI syntax highlighting
- Syntax theme support
- Global preference update methods

**KalixPlainTextArea** adds:
- No syntax highlighting (SYNTAX_STYLE_NONE)
- Suitable for logs and plain text display

**Usage:**
- For INI editing: Use `KalixIniTextArea` or `KalixIniTextArea.createReadOnly()`
- For plain text: Use `KalixPlainTextArea` or `KalixPlainTextArea.createReadOnly()`
- Never instantiate raw `RSyntaxTextArea` directly

Key files: `KalixTextArea.java`, `KalixIniTextArea.java`, `KalixPlainTextArea.java`

### Text Editor with RSyntaxTextArea
Enhanced text editor using manager pattern:
- **Uses KalixIniTextArea**: Inherits font and cursor alignment fixes
- **Manager classes**: `TextSearchManager`, `TextNavigationManager`, `FileDropManager`
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
JSON-based STDIO protocol for communicating with Rust backend. For detailed protocol specifications, message formats, and examples, see `docs/kalixcli-stdio-spec.md`.

### Key Components
- `SessionManager.java` - Core session lifecycle management
- `JsonSessionManager.java` - JSON protocol implementation
- `ProcessExecutor.java` - Process spawning and management
- `RunManager.java` - Multi-session IDE management

## Custom Themes

### Theme System
Unified theme architecture using `UnifiedThemeDefinition` with exact color mappings:

**Available Themes:**
- **Light**: Light, Keylime (lime green), Lapland (nordic blue), Nemo (ocean blue/orange), Sunset Warmth
- **Dark**: Dracula, One Dark, Obsidian (purple accents), Sanne, Botanical

### Technical Implementation
- **Unified architecture**: Java-based color definitions in `LightThemeDefinitions.java` and `DarkThemeDefinitions.java`
- **Exact color mappings**: Migrated from algorithmic generation to precise color specifications
- **Platform-aware**: Cross-platform title bar handling with `Platform` enum (macOS, Windows, Linux)
- **Custom properties**: MapPanel.background, MapPanel.gridlineColor, TitlePane.background
- **FlatPropertiesLaf integration**: Converted to properties via `ThemeCompatibilityAdapter`

Key files: `ThemeManager.java`, `UnifiedThemeDefinition.java`, `Platform.java`, `PlatformUtils.java`

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
- `KalixIDE` - Application coordination
- `EnhancedTextEditor` - Text editing with manager pattern
- Manager classes - Specific functionality domains
- Builder classes - UI construction
- CLI Integration - Session management and STDIO communication