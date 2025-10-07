# KalixCLI Documentation

## Overview

KalixCLI is the command-line interface for the Kalix hydrological modeling system. It provides two main modes of operation:

1. **Direct CLI Mode**: One-shot commands that execute and exit
2. **Session Mode**: Interactive STDIO-based protocol for IDE communication

---

## Part 1: Command-Line Interface (CLI)

### Synopsis

```bash
kalixcli <COMMAND> [OPTIONS]
```

### Available Commands

#### `new-session`
Starts an interactive JSON-based STDIO session for IDE communication.

**Usage:**
```bash
kalixcli new-session
```

**Behavior:**
- Enters interactive mode using JSON messages over STDIN/STDOUT
- Maintains session state and model data in memory
- Supports multiple concurrent operations
- See "Part 2: STDIO Protocol" below for communication details

---

#### `test`
Run performance tests or simulation tests.

**Usage:**
```bash
kalixcli test [--sim-duration-seconds <SECONDS>] [--new-session <VALUE>]
```

**Options:**
- `--sim-duration-seconds <SECONDS>`: Run a test simulation for specified duration
- `--new-session <VALUE>`: Start a legacy session mode (deprecated)

**Examples:**
```bash
# Run performance benchmarks
kalixcli test

# Run 10-second test simulation
kalixcli test --sim-duration-seconds 10

# Legacy session mode (deprecated)
kalixcli test --new-session 1
```

---

#### `sim`
Run a hydrological simulation.

**Usage:**
```bash
kalixcli sim [MODEL_FILE] [--output-file <FILE>]
```

**Arguments:**
- `MODEL_FILE` (optional): Path to model file. If not provided, reads model from STDIN

**Options:**
- `--output-file <FILE>`: Path for output results

**Examples:**
```bash
# Run simulation from file
kalixcli sim model.ini --output-file results.csv

# Run simulation from STDIN
echo "model content" | kalixcli sim --output-file results.csv

# Run simulation without output file
kalixcli sim model.ini
```

---

#### `calibrate`
Run model calibration (placeholder - not yet implemented).

**Usage:**
```bash
kalixcli calibrate [--config <FILE>] [--iterations <NUMBER>]
```

**Options:**
- `--config <FILE>`: Path to calibration configuration file
- `--iterations <NUMBER>`: Number of iterations (default: 1000)

**Examples:**
```bash
# Run calibration with defaults
kalixcli calibrate

# Run calibration with config file
kalixcli calibrate --config calib.toml --iterations 5000
```

---

#### `get-api`
Output CLI API specification as JSON.

**Usage:**
```bash
kalixcli get-api
```

**Output:**
Returns JSON description of all available commands and their parameters.

**Example:**
```bash
kalixcli get-api | jq '.'
```

---

### Global Options

- `--help`: Show help information
- `--version`: Show version information

---

## Part 2: STDIO Communication Protocol

When `kalixcli new-session` is invoked, it enters an interactive mode using a JSON-based protocol over STDIN/STDOUT. This is primarily used by KalixIDE for persistent sessions.

### Protocol Overview

- **Format**: Line-delimited JSON messages
- **Transport**: STDIN (IDE → kalixcli) and STDOUT (kalixcli → IDE)
- **Session Management**: Stateful with unique session IDs
- **Concurrency**: Supports multiple concurrent sessions

### Message Structure

All messages follow this structure:

```json
{
  "type": "message_type",
  "timestamp": "2025-09-22T10:30:00Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": { /* message-specific payload */ }
}
```

### Outgoing Messages (kalixcli → IDE)

#### 1. Ready Message
Sent when session starts and after each command completes.

```json
{
  "type": "ready",
  "timestamp": "2025-09-22T10:30:00Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "commands": [
      {
        "name": "echo",
        "description": "Echo back the provided string",
        "parameters": [
          {
            "name": "string",
            "type": "string",
            "required": true
          }
        ]
      }
    ],
    "state": {
      "model_loaded": false,
      "data_loaded": false
    }
  }
}
```

#### 2. Busy Message
Sent when command execution starts.

```json
{
  "type": "busy",
  "timestamp": "2025-09-22T10:30:05Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "command": "run_simulation"
  }
}
```

#### 3. Progress Message
Sent during long-running operations.

```json
{
  "type": "progress",
  "timestamp": "2025-09-22T10:30:10Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "command": "run_simulation",
    "progress": {
      "percent_complete": 45.2,
      "current_step": "Running simulation - Processing timestep 452 of 1000",
      "details": {
        "current_timestep": 452,
        "total_timesteps": 1000,
        "simulation_progress": "45.2%"
      }
    }
  }
}
```

#### 4. Result Message
Sent when command execution completes successfully.

```json
{
  "type": "result",
  "timestamp": "2025-09-22T10:30:15Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "command": "echo",
    "status": "success",
    "execution_time": "00:00:00.001",
    "result": {
      "echoed": "Hello World"
    }
  }
}
```

#### 5. Error Message
Sent when command execution fails.

```json
{
  "type": "error",
  "timestamp": "2025-09-22T10:30:15Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "command": "get_result",
    "error": {
      "code": "RESULT_NOT_FOUND",
      "message": "Timeseries 'invalid_name' not found in model results"
    }
  }
}
```

#### 6. Stopped Message
Sent when command execution is interrupted.

```json
{
  "type": "stopped",
  "timestamp": "2025-09-22T10:30:15Z",
  "kalixcli_uid": "sess_20250922_103000_a7b9",
  "data": {
    "command": "run_simulation",
    "status": "stopped",
    "execution_time": "00:01:30"
  }
}
```

### Incoming Messages (IDE → kalixcli)

#### 1. Command Message
Execute a specific command.

```json
{
  "type": "command",
  "data": {
    "command": "echo",
    "parameters": {
      "string": "Hello World"
    }
  }
}
```

#### 2. Stop Message
Interrupt currently executing command.

```json
{
  "type": "stop",
  "data": {
    "reason": "User cancellation"
  }
}
```

#### 3. Terminate Message
End the session gracefully.

```json
{
  "type": "terminate",
  "data": {}
}
```

### Available Session Commands

#### Basic Commands

##### `get_version`
Returns version information.

**Parameters:** None

**Response:**
```json
{
  "result": {
    "version": "0.1.0",
    "build_date": "2025-09-08",
    "features": ["stdio", "modeling", "calibration"]
  }
}
```

##### `get_state`
Returns current session state information.

**Parameters:** None

##### `echo`
Echo back a provided string.

**Parameters:**
- `string` (required): String to echo back

**Example:**
```json
{
  "type": "command",
  "data": {
    "command": "echo",
    "parameters": {
      "string": "Hello World"
    }
  }
}
```

**Response:**
```json
{
  "result": {
    "echoed": "Hello World"
  }
}
```

#### Model Management Commands

##### `load_model_file`
Load a model from a file path.

**Parameters:**
- `model_path` (required): Path to the model file

##### `load_model_string`
Load a model from INI string content.

**Parameters:**
- `model_ini` (required): INI content as string

##### `run_simulation`
Execute model simulation (interruptible).

**Parameters:** None

**Behavior:**
- Long-running operation with progress updates
- Sends initial progress message (0%) before starting simulation
- Can be interrupted with `stop` message
- Stores results in session for later retrieval

**Progress Updates:**
- Initial: 0% progress, timestep 1 of N
- During simulation: 0-100% progress based on timestep completion
- Final: 100% progress, timestep N of N
- Details include current_timestep (0-based), total_timesteps, and simulation_progress

#### Result Retrieval Commands

##### `get_result`
Retrieve timeseries result data from completed simulation.

**Parameters:**
- `series_name` (required): Name of the timeseries to retrieve
- `format` (required): Output format ("csv")

**Example:**
```json
{
  "type": "command",
  "data": {
    "command": "get_result",
    "parameters": {
      "series_name": "rainfall_station_01",
      "format": "csv"
    }
  }
}
```

**Response:**
```json
{
  "result": {
    "series_name": "rainfall_station_01",
    "format": "csv",
    "metadata": {
      "start_timestamp": "2023-01-01T00:00:00Z",
      "timestep_seconds": 3600,
      "total_points": 8760,
      "units": "unknown"
    },
    "data": "2023-01-01T00:00:00Z,3600,1.2,2.1,0.8,1.5,..."
  }
}
```

### Error Codes

| Code | Description |
|------|-------------|
| `INVALID_PARAMETERS` | Required parameter missing or invalid |
| `MODEL_NOT_LOADED` | Operation requires a loaded model |
| `DATA_NOT_LOADED` | Operation requires loaded input data |
| `RESULT_NOT_FOUND` | Requested timeseries result not found |
| `EXECUTION_ERROR` | General command execution error |
| `IO_ERROR` | File system or I/O related error |

### Session Lifecycle

1. **Startup**
   - IDE: `kalixcli new-session`
   - kalixcli: Generates unique kalixcli_uid
   - kalixcli: Sends `ready` message with available commands

2. **Command Execution**
   - IDE: Sends `command` message
   - kalixcli: Sends `busy` message
   - kalixcli: Executes command with optional `progress` messages
   - kalixcli: Sends `result`, `error`, or `stopped` message
   - kalixcli: Sends `ready` message for next command

3. **Termination**
   - IDE: Sends `terminate` message OR kills process
   - kalixcli: Cleanup and exit

### Implementation Examples

#### Starting a Session (IDE Side)
```java
ProcessBuilder pb = new ProcessBuilder("kalixcli", "new-session");
Process process = pb.start();

// Read JSON messages from process.getInputStream()
// Send JSON messages to process.getOutputStream()
```

#### Command Execution Flow
```
IDE → kalixcli: {"type": "command", "data": {"command": "echo", "parameters": {"string": "test"}}}
kalixcli → IDE: {"type": "busy", "data": {"command": "echo"}}
kalixcli → IDE: {"type": "result", "data": {"command": "echo", "result": {"echoed": "test"}}}
kalixcli → IDE: {"type": "ready", "data": {"commands": [...], "state": {...}}}
```

### Version History

- **Current**: JSON-based STDIO protocol with session management
- **Legacy**: `KALIX_*` prefix protocol (deprecated, see KALIX_CLI_STDIO_PROTOCOL_LEGACY.md)