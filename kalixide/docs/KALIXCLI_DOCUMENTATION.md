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

When `kalixcli new-session` is invoked, it enters an interactive mode using a compact JSON-based protocol over STDIN/STDOUT. This is primarily used by KalixIDE for persistent sessions.

### Protocol Overview

- **Format**: Line-delimited compact JSON messages
- **Transport**: STDIN (IDE → kalixcli) and STDOUT (kalixcli → IDE)
- **Session Management**: Stateful with unique session IDs
- **Size Optimization**: 94-97% reduction in message size through compact structure

### Message Structure

All messages use a flattened structure with short field names:

```json
{
  "m": "message_type",
  "uid": "session_identifier",
  ...additional_fields
}
```

### Outgoing Messages (kalixcli → IDE)

#### 1. Ready Message (`rdy`)
Sent when session starts and after each command completes.

```json
{"m":"rdy","uid":"X2vB3yCbrVqw","rc":0}
```

**Fields:**
- `m`: Message type ("rdy")
- `uid`: Session identifier
- `rc`: Return code (0=success, 1=error, 2=interrupted)

#### 2. Busy Message (`bsy`)
Sent when command execution starts.

```json
{"m":"bsy","uid":"X2vB3yCbrVqw","cmd":"run_simulation","int":true}
```

**Fields:**
- `m`: Message type ("bsy")
- `uid`: Session identifier
- `cmd`: Command being executed
- `int`: Whether command is interruptible

#### 3. Progress Message (`prg`)
Sent during long-running operations.

```json
{"m":"prg","uid":"X2vB3yCbrVqw","i":500,"n":1000,"t":"sim"}
```

**Fields:**
- `m`: Message type ("prg")
- `uid`: Session identifier
- `i`: Current progress count
- `n`: Total count for completion
- `t`: Task type ("sim", "cal", "load", "proc", "build")

#### 4. Result Message (`res`)
Sent when command execution completes successfully.

```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":1250.75,"ok":true,"r":{"ts":{"len":48824,"start":"1889-01-01","end":"2022-09-04","o":["timeseries_data","summary_statistics"],"outputs":["node.output1","node.output2"]}}}
```

**Fields:**
- `m`: Message type ("res")
- `uid`: Session identifier
- `cmd`: Command that completed
- `exec_ms`: Execution time in milliseconds
- `ok`: Success flag
- `r`: Result data object

#### 5. Error Message (`err`)
Sent when command execution fails.

```json
{"m":"err","uid":"X2vB3yCbrVqw","cmd":"load_model_file","msg":"File not found: /path/to/model.ini"}
```

**Fields:**
- `m`: Message type ("err")
- `uid`: Session identifier
- `cmd`: Command that failed (optional)
- `msg`: Error message

#### 6. Stopped Message (`stp`)
Sent when command execution is interrupted.

```json
{"m":"stp","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":750.25}
```

**Fields:**
- `m`: Message type ("stp")
- `uid`: Session identifier
- `cmd`: Command that was stopped
- `exec_ms`: Partial execution time

### Incoming Messages (IDE → kalixcli)

#### 1. Command Message (`cmd`)
Execute a specific command.

```json
{"m":"cmd","c":"echo","p":{"string":"Hello World"}}
```

**Fields:**
- `m`: Message type ("cmd")
- `c`: Command name
- `p`: Parameters object

#### 2. Stop Message (`stp`)
Interrupt currently executing command.

```json
{"m":"stp","reason":"User cancel"}
```

**Fields:**
- `m`: Message type ("stp")
- `reason`: Optional reason for stopping

#### 3. Query Message (`query`)
Request information from kalixcli.

```json
{"m":"query","q":"get_state"}
```

**Fields:**
- `m`: Message type ("query")
- `q`: Query type ("get_state", "get_session_id")

#### 4. Terminate Message (`term`)
End the session gracefully.

```json
{"m":"term"}
```

### Available Session Commands

#### Basic Commands

##### `get_version`
Returns version information.

**Parameters:** None

**Response:**
```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"get_version","exec_ms":5.2,"ok":true,"r":{"version":"1.0.0","build":"abc123"}}
```

##### `echo`
Echo back a provided string.

**Parameters:**
- `string` (required): String to echo back

**Example:**
```json
{"m":"cmd","c":"echo","p":{"string":"Hello World"}}
```

**Response:**
```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"echo","exec_ms":1.2,"ok":true,"r":{"echoed":"Hello World"}}
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
- Can be interrupted with stop message
- Stores results in session for later retrieval

#### Result Retrieval Commands

##### `get_result`
Retrieve timeseries result data from completed simulation.

**Parameters:**
- `series_name` (required): Name of the timeseries to retrieve
- `format` (required): Output format ("csv")

**Example:**
```json
{"m":"cmd","c":"get_result","p":{"series_name":"node.output1","format":"csv"}}
```

**Response:**
```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"get_result","exec_ms":23.1,"ok":true,"r":{"series_name":"node.output1","data":"631152000,86400,10.5,11.2,9.8"}}
```

### Session Lifecycle

1. **Startup**
   - IDE: `kalixcli new-session`
   - kalixcli: Generates unique session ID
   - kalixcli: Sends `rdy` message

2. **Command Execution**
   - IDE: Sends `cmd` message
   - kalixcli: Sends `bsy` message
   - kalixcli: Executes command with optional `prg` messages
   - kalixcli: Sends `res`, `err`, or `stp` message
   - kalixcli: Sends `rdy` message for next command

3. **Termination**
   - IDE: Sends `term` message OR kills process
   - kalixcli: Cleanup and exit

### Implementation Examples

#### Starting a Session (IDE Side)
```java
ProcessBuilder pb = new ProcessBuilder("kalixcli", "new-session");
Process process = pb.start();

// Read compact JSON messages from process.getInputStream()
// Send compact JSON messages to process.getOutputStream()
```

#### Command Execution Flow
```
IDE → kalixcli: {"m":"cmd","c":"echo","p":{"string":"test"}}
kalixcli → IDE: {"m":"bsy","uid":"X2vB3yCbrVqw","cmd":"echo","int":false}
kalixcli → IDE: {"m":"res","uid":"X2vB3yCbrVqw","cmd":"echo","exec_ms":1.2,"ok":true,"r":{"echoed":"test"}}
kalixcli → IDE: {"m":"rdy","uid":"X2vB3yCbrVqw","rc":0}
```

### Performance Benefits

The compact protocol provides significant performance improvements:
- **Progress messages**: 94% size reduction (433 → 26 characters)
- **Ready messages**: 97% size reduction (1,437 → 37 characters)
- **Overall bandwidth**: Reduced by 94-97% for high-frequency messages
- **Parsing efficiency**: Flattened structure improves JSON parsing performance

For detailed protocol specifications and migration information, see `docs/kalixcli-stdio-spec.md`.