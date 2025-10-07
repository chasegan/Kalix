# KalixCLI JSON Protocol Specification

## 1. Overview

This specification defines the JSON-based communication protocol over STDIO for interaction between a frontend application and the kalixcli hydrological modeling engine. The protocol enables the frontend to initiate modeling tasks, monitor progress, retrieve results, and interrupt long-running operations while keeping computational data resident in kalixcli memory.

## 2. Design Principles

### 2.1 Core Principles
- **Stateful Session**: kalixcli maintains model state, data, and results in memory throughout the session
- **Single Task Execution**: kalixcli processes one command at a time, executing tasks sequentially
- **Interruptible Operations**: Long-running tasks can be interrupted by the frontend
- **JSON Message Format**: All communication uses structured JSON for clarity and extensibility
- **Line-Delimited**: Each message is a complete JSON object on a single line
- **Compact Structure**: Minimized field names for efficient communication
- **Flattened Design**: Message data is directly embedded without nested structures

### 2.2 Communication Flow
1. Frontend spawns kalixcli process
2. kalixcli generates a unique session UID and sends ready signal
3. Frontend receives session UID for reference (logging/debugging)
4. Frontend sends command
5. kalixcli sends busy signal indicating task has started
6. kalixcli executes command, sending progress updates while listening for interrupts
7. kalixcli sends result or error (or stopped if interrupted)
8. kalixcli sends ready signal for next command
9. Session continues until explicit termination

## 3. Message Structure

### 3.1 Base Message Format
All messages follow this structure with flattened fields:

```json
{
  "m": "message_type",
  "uid": "session_identifier",
  ...additional_fields
}
```

**Core Fields:**
- `m` (string, required): Message type identifier
- `uid` (string, optional): Session identifier for kalixcli→frontend messages

### 3.2 Message Types

**From kalixcli → frontend:**
- `rdy` - Ready for commands
- `bsy` - Busy executing command
- `prg` - Progress update
- `res` - Command result
- `stp` - Command stopped/interrupted
- `err` - Error occurred

**From frontend → kalixcli:**
- `cmd` - Execute command
- `stp` - Stop current operation
- `query` - Query system state
- `term` - Terminate session

## 4. Message Formats

### 4.1 Ready Message (kalixcli → frontend)
Sent when kalixcli starts and after each command completion/interruption.

```json
{
  "m": "rdy",
  "uid": "sess_20250908_103000_a7b9",
  "rc": 0
}
```

**Fields:**
- `rc` (integer): Return code (0=success, 1=error, 2=interrupted)

### 4.2 Busy Message (kalixcli → frontend)
Sent when a command begins execution.

```json
{
  "m": "bsy",
  "uid": "sess_20250908_103000_a7b9",
  "cmd": "run_simulation",
  "int": true
}
```

**Fields:**
- `cmd` (string): Command being executed
- `int` (boolean): Whether command is interruptible

### 4.3 Command Message (frontend → kalixcli)
Requests execution of a specific command.

```json
{
  "m": "cmd",
  "c": "load_model_file",
  "p": {
    "model_path": "/path/to/model.ini"
  }
}
```

**Fields:**
- `c` (string): Command name
- `p` (object): Command parameters

### 4.4 Stop Message (frontend → kalixcli)
Requests interruption of the current operation.

```json
{
  "m": "stp",
  "reason": "User requested cancellation"
}
```

**Fields:**
- `reason` (string, optional): Reason for stopping

### 4.5 Progress Message (kalixcli → frontend)
Progress updates during long-running operations.

```json
{
  "m": "prg",
  "uid": "sess_20250908_103000_a7b9",
  "i": 500,
  "n": 1000,
  "t": "sim"
}
```

**Fields:**
- `i` (integer): Current progress count
- `n` (integer): Total count for completion
- `t` (string): Task type ("sim", "cal", "load", "proc", "build")

### 4.6 Result Message (kalixcli → frontend)
Command execution result.

```json
{
  "m": "res",
  "uid": "sess_20250908_103000_a7b9",
  "cmd": "run_simulation",
  "exec_ms": 1250.75,
  "ok": true,
  "r": {
    "ts": {
      "len": 48824,
      "start": "1889-01-01",
      "end": "2022-09-04",
      "o": ["timeseries_data", "summary_statistics"],
      "outputs": ["node.output1", "node.output2"]
    }
  }
}
```

**Fields:**
- `cmd` (string): Command that was executed
- `exec_ms` (number): Execution time in milliseconds
- `ok` (boolean): Whether command succeeded
- `r` (object): Command result data

### 4.7 Stopped Message (kalixcli → frontend)
Sent when a command is interrupted.

```json
{
  "m": "stp",
  "uid": "sess_20250908_103000_a7b9",
  "cmd": "run_simulation",
  "exec_ms": 750.25
}
```

**Fields:**
- `cmd` (string): Command that was stopped
- `exec_ms` (number): Execution time before stopping

### 4.8 Error Message (kalixcli → frontend)
Error during command execution or system operation.

```json
{
  "m": "err",
  "uid": "sess_20250908_103000_a7b9",
  "cmd": "load_model_file",
  "msg": "File not found: /invalid/path/model.ini"
}
```

**Fields:**
- `cmd` (string, optional): Command that caused the error
- `msg` (string): Error message

### 4.9 Query Message (frontend → kalixcli)
Request system state information.

```json
{
  "m": "query",
  "q": "get_state"
}
```

**Fields:**
- `q` (string): Query type ("get_state", "get_session_id")

### 4.10 Terminate Message (frontend → kalixcli)
End the session and exit.

```json
{
  "m": "term"
}
```

## 5. Available Commands

### 5.1 Core Commands

**load_model_file**
- Description: Load a hydrological model from a file path
- Parameters: `model_path` (string, required)

**load_model_string**
- Description: Load a hydrological model from an INI string
- Parameters: `model_ini` (string, required)

**run_simulation**
- Description: Execute simulation with loaded model and data
- Parameters: None

**get_result**
- Description: Retrieve timeseries result data
- Parameters: `series_name` (string, required), `format` (string, default "csv")

**get_version**
- Description: Get kalixcli version information
- Parameters: None

**echo**
- Description: Echo back provided text (testing/debugging)
- Parameters: `string` (string, required)

### 5.2 Utility Commands

**test_progress**
- Description: Generate test progress updates
- Parameters: `duration_seconds` (integer, optional)

## 6. Session Management

### 6.1 Session Lifecycle
- kalixcli generates unique session IDs when it starts using timestamp + random component
- Session ID is included in all kalixcli → frontend messages
- Frontend does not need to include session ID in outgoing messages
- Session ID remains constant for the entire lifetime of the kalixcli process
- State machine: ready ↔ busy

### 6.2 Error Handling
- Malformed JSON triggers error response
- Unknown commands trigger error response
- Commands sent while busy are rejected (except stop/query)
- Error messages include context and suggested fixes where possible

### 6.3 Interruption Handling
- Frontend can send stop message during command execution
- Only interruptible commands respond to stop messages
- Stopped commands send stopped message with partial execution time
- System returns to ready state after interruption

## 7. Implementation Notes

### 7.1 Message Parsing
- Each message is a complete JSON object on a single line
- Messages must be parsed as they arrive (line-buffered)
- Invalid JSON should be logged but not crash the session
- Flattened structure eliminates nested data parsing

### 7.2 Progress Reporting
- Progress uses integer counters instead of percentages
- Calculation: `percentage = (i / n) * 100`
- Progress messages have no guaranteed frequency
- Frontend should implement progress smoothing if needed

### 7.3 Result Formats
- Results are command-specific structured data
- Simulation results use compact timeseries format in `r.ts`
- Large data results may reference external files
- Binary data is base64 encoded within JSON

### 7.4 Performance Considerations
- Compact field names reduce message size by ~95%
- Flattened structure improves parsing performance
- No timestamps reduce overhead for high-frequency progress updates
- Single-line JSON enables efficient streaming

## 8. Example Session

```json
// 1. kalixcli starts and sends ready
{"m":"rdy","uid":"sess_20250908_103000_a7b9","rc":0}

// 2. Frontend loads model
{"m":"cmd","c":"load_model_string","p":{"model_ini":"[model configuration...]"}}

// 3. kalixcli acknowledges and processes
{"m":"bsy","uid":"sess_20250908_103000_a7b9","cmd":"load_model_string","int":false}
{"m":"res","uid":"sess_20250908_103000_a7b9","cmd":"load_model_string","exec_ms":45.2,"ok":true,"r":{"status":"loaded"}}
{"m":"rdy","uid":"sess_20250908_103000_a7b9","rc":0}

// 4. Frontend runs simulation
{"m":"cmd","c":"run_simulation","p":{}}

// 5. kalixcli executes with progress updates
{"m":"bsy","uid":"sess_20250908_103000_a7b9","cmd":"run_simulation","int":true}
{"m":"prg","uid":"sess_20250908_103000_a7b9","i":250,"n":1000,"t":"sim"}
{"m":"prg","uid":"sess_20250908_103000_a7b9","i":500,"n":1000,"t":"sim"}
{"m":"prg","uid":"sess_20250908_103000_a7b9","i":1000,"n":1000,"t":"sim"}
{"m":"res","uid":"sess_20250908_103000_a7b9","cmd":"run_simulation","exec_ms":1250.75,"ok":true,"r":{"ts":{"len":48824,"outputs":["node.output1"]}}}
{"m":"rdy","uid":"sess_20250908_103000_a7b9","rc":0}

// 6. Frontend retrieves results
{"m":"cmd","c":"get_result","p":{"series_name":"node.output1","format":"csv"}}
{"m":"res","uid":"sess_20250908_103000_a7b9","cmd":"get_result","exec_ms":23.1,"ok":true,"r":{"series_name":"node.output1","data":"631152000,86400,10.5,11.2,9.8"}}

// 7. Frontend terminates session
{"m":"term"}
```

This protocol provides efficient, reliable communication for hydrological modeling workflows while maintaining simplicity and extensibility.