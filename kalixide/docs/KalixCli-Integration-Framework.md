# KalixCli Integration Framework

## Overview

This document outlines the framework for integrating the Java Swing Kalix IDE with the Rust-based kalixcli command-line tool. The framework provides dynamic API discovery, real-time stream monitoring, and cross-platform process execution.

## Architecture Overview

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   KalixIDE      │────│ KalixCliManager │────│   kalixcli      │
│                 │    │                 │    │   (Rust)        │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                               │
                               ├── ApiDiscovery
                               ├── ProcessExecutor  
                               ├── StreamMonitor
                               ├── CommandBuilder
                               └── ResultParser
```

## Core Components

### 1. KalixCliManager
- **Purpose**: Main orchestrator and public API
- **Responsibilities**:
  - Coordinate between IDE and CLI components
  - Manage CLI lifecycle and configuration
  - Provide simple interface for IDE operations
  - Handle error recovery and fallbacks

### 2. ProcessExecutor
- **Purpose**: Cross-platform process execution with stream handling
- **Responsibilities**:
  - Launch kalixcli processes on Windows, Mac, Linux
  - Manage process lifecycle (start, monitor, terminate)
  - Handle process environment and working directory
  - Provide cancellation and timeout mechanisms

### 3. ApiDiscovery
- **Purpose**: Dynamic command discovery via `kalixcli get-api`
- **Responsibilities**:
  - Call `kalixcli get-api` to retrieve available commands
  - Parse API response (likely JSON) into command registry
  - Cache API information with refresh mechanisms
  - Handle API versioning and compatibility

### 4. StreamMonitor
- **Purpose**: Real-time STDOUT/STDERR monitoring with callbacks
- **Responsibilities**:
  - Monitor output streams in real-time
  - Parse progress indicators and status updates
  - Provide callback mechanisms for IDE updates
  - Handle different output formats (JSON, plain text, structured data)

### 5. CommandBuilder
- **Purpose**: Dynamic command construction from discovered API
- **Responsibilities**:
  - Build CLI commands from API definitions
  - Validate parameters against API specifications
  - Handle different parameter types and formats
  - Provide fluent interface for command construction

### 6. ResultParser
- **Purpose**: Parse CLI output into structured Java objects
- **Responsibilities**:
  - Parse different output formats (JSON, CSV, plain text)
  - Convert CLI results to Java data structures
  - Handle error responses and status codes
  - Provide type-safe result objects

### 7. CliConfiguration
- **Purpose**: Cross-platform CLI detection and configuration
- **Responsibilities**:
  - Auto-detect kalixcli executable location
  - Handle different installation paths per platform
  - Validate CLI version compatibility
  - Provide configuration UI for manual CLI path selection

## Development Plan

### Phase 1: Foundation (Process Execution)
**Objective**: Establish basic process execution and stream monitoring

- [ ] Create `ProcessExecutor` with cross-platform support
- [ ] Implement `StreamMonitor` for real-time STDOUT/STDERR capture
- [ ] Add progress callbacks and cancellation support
- [ ] Cross-platform path resolution for kalixcli executable
- [ ] Basic error handling and logging

**Deliverables**:
- Working process execution on all platforms
- Real-time stream monitoring with callbacks
- Basic CLI detection and validation

### Phase 2: API Discovery
**Objective**: Dynamic discovery of CLI capabilities

- [ ] Implement `ApiDiscovery` to call `kalixcli get-api`
- [ ] Parse API response into command registry
- [ ] Create `KalixCommand` data structures for available commands
- [ ] Add API caching and refresh mechanisms
- [ ] Handle API versioning and backward compatibility

**Deliverables**:
- Dynamic command discovery system
- Cached API registry with refresh capabilities
- Version compatibility handling

### Phase 3: Dynamic Command Execution
**Objective**: Execute CLI commands based on discovered API

- [ ] Build `CommandBuilder` for constructing CLI calls from API
- [ ] Implement parameter validation based on discovered API
- [ ] Add result parsing for different command types
- [ ] Handle streaming vs. one-shot commands
- [ ] Implement comprehensive error handling

**Deliverables**:
- Dynamic command construction system
- Type-safe parameter validation
- Robust result parsing and error handling

### Phase 4: IDE Integration
**Objective**: Integrate CLI framework with existing IDE

- [ ] Create `KalixCliManager` as main interface
- [ ] Integrate with existing `MenuBarCallbacks` system
- [ ] Add progress indicators for long-running operations
- [ ] Implement the "Run Model" placeholder with actual CLI calls
- [ ] Create status updates and user feedback mechanisms

**Deliverables**:
- Fully integrated CLI manager
- Working "Run Model" functionality
- Progress indicators and status updates

### Phase 5: Error Handling & Configuration
**Objective**: Polish and production readiness

- [ ] Add robust error handling for CLI not found, version mismatches
- [ ] Implement CLI auto-discovery on different platforms
- [ ] Add configuration UI for CLI path selection
- [ ] Create comprehensive logging and debugging
- [ ] Add unit and integration tests
- [ ] Performance optimization and memory management

**Deliverables**:
- Production-ready error handling
- Configuration management UI
- Comprehensive test suite

## Key Benefits

### ✅ Dynamic
- IDE automatically adapts to new CLI features
- No need to update IDE code when CLI adds new commands
- Future-proof architecture

### ✅ Cross-platform
- Works seamlessly on Windows, Mac, and Linux
- Handles platform-specific path conventions
- Consistent behavior across operating systems

### ✅ Responsive
- Real-time progress updates via stream monitoring
- Non-blocking UI during long-running operations
- Cancellation support for user control

### ✅ Robust
- Comprehensive error handling and recovery
- Graceful fallbacks when CLI is unavailable
- Version compatibility management

### ✅ Maintainable
- Clean separation of concerns
- Modular architecture for easy testing
- Well-defined interfaces between components

## Technical Considerations

### Stream Handling
- Use `ProcessBuilder` for cross-platform process execution
- Implement separate threads for STDOUT/STDERR monitoring
- Use `BufferedReader` with callbacks for real-time updates
- Handle different line endings and character encodings

### API Format
- Assume `kalixcli get-api` returns JSON format
- Design extensible parsing for future API changes
- Handle partial API responses and error cases
- Cache API responses to reduce CLI calls

### Error Recovery
- Detect when CLI is not installed or outdated
- Provide clear error messages and resolution steps
- Fallback to basic functionality when API discovery fails
- Implement retry mechanisms for transient failures

### Performance
- Cache CLI results where appropriate
- Use background threads for long-running operations
- Minimize CLI process creation overhead
- Implement connection pooling if needed

## Integration Points

### Existing IDE Components
- **MenuBarCallbacks**: Implement actual CLI calls for runModel()
- **FileOperationsManager**: Integrate model validation with CLI
- **Status Updates**: Show CLI progress and results
- **Error Handling**: Display CLI errors in IDE

### New IDE Features
- **CLI Configuration Dialog**: Select CLI path and version
- **Progress Indicators**: Show long-running CLI operations
- **Results Display**: Show CLI output in appropriate format
- **Command History**: Track CLI commands and results

## Risk Mitigation

### CLI Availability
- **Risk**: kalixcli not installed or not in PATH
- **Mitigation**: Auto-discovery, configuration UI, clear error messages

### Version Compatibility
- **Risk**: IDE incompatible with CLI version
- **Mitigation**: Version checking, backward compatibility, graceful degradation

### Performance
- **Risk**: Slow CLI operations blocking IDE
- **Mitigation**: Background processing, progress indicators, cancellation

### Platform Differences
- **Risk**: Different behavior on Windows vs. Unix
- **Mitigation**: Extensive cross-platform testing, platform-specific handling

## Future Enhancements

- **Plugin System**: Allow third-party CLI integrations
- **Command Scripting**: Save and replay command sequences
- **Remote CLI**: Execute commands on remote machines
- **CLI Proxy**: Cache and optimize CLI calls
- **Advanced Parsing**: Support for complex output formats