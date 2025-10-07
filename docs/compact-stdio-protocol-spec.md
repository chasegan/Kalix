# Compact STDIO Protocol Implementation Guide

## Migration Overview

This specification defines the target compact JSON protocol to replace the current verbose STDIO communication between kalixide (Java) and kalixcli (Rust). This guide provides the specific mappings and implementation details needed for migration.

## Implementation Strategy

**Recommended approach**: Implement compact protocol alongside existing verbose protocol, with runtime switching based on capability negotiation.

**Key files to modify**:
- **Rust**: `src/apis/stdio/messages.rs`, `src/apis/stdio/transport.rs`, `src/apis/stdio/handlers.rs`
- **Java**: `JsonStdioProtocol.java`, `JsonMessage.java`, `JsonStdioTypes.java`

## Field Mapping Reference

**Core field transformations**:
- `"type"` → `"m"` (message type)
- `"kalixcli_uid"` → `"uid"` (session identifier)
- `"timestamp"` → *removed* (except where critical)
- `"data"` wrapper → *flattened to top level*
- `"command"` → `"c"` or `"cmd"`
- `"parameters"` → `"p"`

## Message Type Coding System

All messages use short, readable string codes for the message type field (`m`).

### System Messages (kalixcli → IDE)

| Code | Full Name | Description | Frequency |
|------|-----------|-------------|-----------|
| `rdy` | ready | Session ready for commands | Low |
| `bsy` | busy | Command execution started | Medium |
| `prg` | progress | Progress update during execution | **Very High** |
| `res` | result | Command completed with results | Medium |
| `stp` | stopped | Command was interrupted/stopped | Low |
| `err` | error | Error occurred during execution | Low |
| `log` | log | Informational logging message | Low |

### Command Messages (IDE → kalixcli)

| Code | Full Name | Description | Frequency |
|------|-----------|-------------|-----------|
| `cmd` | command | Execute a command | Medium |
| `stp` | stop | Interrupt current operation | Low |
| `query` | query | Request information | Low |
| `term` | terminate | End session | Very Low |

## Message Specifications

### Progress Message (prg)

**Purpose**: Report progress during long-running operations
**Frequency**: Very High (potentially hundreds per operation)
**Size**: 26 characters (94% reduction from original 433 characters)

```json
{"m":"prg","i":1,"n":48824,"t":"sim"}
```

**Fields**:
- `m`: Message type ("prg")
- `i`: Integer counter (current item/step/iteration)
- `n`: Total count (total items/steps/iterations)
- `t`: Task type identifying the operation

**Task Types**:
- `"sim"`: Simulation execution
- `"cal"`: Model calibration
- `"load"`: Model/data loading
- `"proc"`: Data processing
- `"build"`: Model building

**Usage Examples**:
```json
// Simulation progress: timestep 1,000 of 48,824
{"m":"prg","i":1000,"n":48824,"t":"sim"}

// Calibration progress: iteration 50 of 200
{"m":"prg","i":50,"n":200,"t":"cal"}

// File loading: 1.2MB of 5.8MB processed
{"m":"prg","i":1200000,"n":5800000,"t":"load"}
```

**Client Calculations**:
- Percentage complete: `(i / n) * 100`
- Progress ratio: `i / n`
- Estimated time remaining: Based on elapsed time and progress rate

### Ready Message (rdy)

**Purpose**: Signal that kalixcli is ready to accept commands
**Frequency**: Medium (sent after each command completion)
**Size**: 37 characters (97.4% reduction from original 1,437 characters)

```json
{"m":"rdy","uid":"X2vB3yCbrVqw","rc":0}
```

**Fields**:
- `m`: Message type ("rdy")
- `uid`: kalixcli session identifier (for logging/debugging)
- `rc`: Return code from previous command

**Return Codes**:
- `0`: Success
- `1`: Unspecified error
- `2+`: Specific error types (to be defined)

**Usage Examples**:
```json
// Ready after successful command
{"m":"rdy","uid":"X2vB3yCbrVqw","rc":0}

// Ready after command failed with unspecified error
{"m":"rdy","uid":"X2vB3yCbrVqw","rc":1}

// Ready after command failed with specific error type
{"m":"rdy","uid":"X2vB3yCbrVqw","rc":5}
```

**Implementation Notes**:
- Replace `ReadyData` struct with simple fields
- Remove `available_commands` array (use query mechanism instead)
- Map `current_state.{model_loaded,data_loaded,last_simulation}` to `rc` field

### Busy Message (bsy)

**Purpose**: Signal that kalixcli has started executing a command
**Frequency**: Medium (sent at start of each command)
**Size**: 47 characters (68% reduction from original 149 characters)

```json
{"m":"bsy","uid":"X2vB3yCbrVqw","cmd":"run_simulation","int":true}
```

**Fields**:
- `m`: Message type ("bsy")
- `uid`: kalixcli session identifier
- `cmd`: Command being executed
- `int`: Interruptible flag (boolean)

**Usage Examples**:
```json
// Interruptible simulation started
{"m":"bsy","uid":"X2vB3yCbrVqw","cmd":"run_simulation","int":true}

// Non-interruptible model loading
{"m":"bsy","uid":"X2vB3yCbrVqw","cmd":"load_model_file","int":false}
```

**Implementation Notes**:
- Replace `BusyData.status` with message type check
- Map `BusyData.executing_command` → `cmd`
- Map `BusyData.interruptible` → `int`

### Command Message (cmd)

**Purpose**: Execute a command on kalixcli (sent from IDE to kalixcli)
**Frequency**: Medium (sent for each user action)
**Size**: 36-74 characters (61-75% reduction from original 145-188 characters)

```json
{"m":"cmd","c":"run_simulation","p":{}}
```

**Fields**:
- `m`: Message type ("cmd")
- `c`: Command name
- `p`: Parameters object

**Usage Examples**:
```json
// Command with no parameters
{"m":"cmd","c":"run_simulation","p":{}}

// Command with single parameter
{"m":"cmd","c":"load_model_file","p":{"model_path":"/path/to/model.ini"}}

// Real example: get_result command (74 chars vs original 188 chars)
{"m":"cmd","c":"get_result","p":{"series_name":"node.node10_gauge.ds_1","format":"csv"}}

// Real example: load_model_string with large model (3,121 chars vs original 3,254 chars)
{"m":"cmd","c":"load_model_string","p":{"model_ini":"[attributes]\nini_version = 0.0.1\n\n[inputs]\n/Users/chas/github/Kalix/src/tests/example_models//1/flows.csv\n/Users/chas/github/Kalix/src/tests/example_models/4/rex_mpot.csv\n...[full model definition]..."}}
```

**Implementation Notes**:
- Remove `kalixcli_uid` from frontend messages
- Flatten `data.command` → `c`, `data.parameters` → `p`
- Keep parameter structure unchanged for backward compatibility
- Update `JsonStdioProtocol.createCommandMessage()` method

### Result Message (res)

**Purpose**: Report command completion with results (sent from kalixcli to IDE)
**Frequency**: Medium (sent after each command completion)
**Size**: Variable (significant reduction through structure optimization)

```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":1.2e3,"ok":true,"r":{"ts":{"len":48824,"start":"1889-01-01","end":"2022-09-04","o":["timeseries_data","summary_statistics"],"outputs":["node.node11_loss.dsflow","node.node6_gr4j.usflow",...]}}}
```

**Fields**:
- `m`: Message type ("res")
- `uid`: kalixcli session identifier
- `cmd`: Command that completed
- `exec_ms`: Execution time in milliseconds (scientific notation)
- `ok`: Success flag (boolean)
- `r`: Result data object
  - `ts`: Timeseries information object (for simulation results)
    - `len`: Number of timesteps processed
    - `start`: Simulation start date
    - `end`: Simulation end date
    - `o`: Available result types
    - `outputs`: List of generated output series

**Usage Examples**:
```json
// Successful simulation result
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":1.2e3,"ok":true,"r":{"ts":{"len":48824,"start":"1889-01-01","end":"2022-09-04","o":["timeseries_data","summary_statistics"],"outputs":["node.node11_loss.dsflow","node.node6_gr4j.usflow"]}}}

// Simple command result (no timeseries data)
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"get_version","exec_ms":5.2e1,"ok":true,"r":{"version":"1.0.0","build":"abc123"}}
```

**Implementation Notes**:
- Replace `ResultData.status` string with `ok` boolean
- Convert `execution_time` string to `exec_ms` scientific notation
- Map `result.timesteps_processed` → `r.ts.len`
- Map `result.simulation_period` → `r.ts.start` and `r.ts.end`
- Map `result.available_results` → `r.ts.o`
- Map `result.outputs_generated` → `r.ts.outputs`
- Adapt `r` structure based on command type (simulation vs others)

### Error Message (err)

**Purpose**: Report command execution errors (sent from kalixcli to IDE)
**Frequency**: Low (sent when commands fail)
**Size**: Variable (47% reduction from original through structure optimization)

```json
{"m":"err","uid":"X2vB3yCbrVqw","cmd":"load_model_string","msg":"Command execution error: Failed to parse model: Could not load timeseries input. /Users/chas/github/Kalix/src/tests/example_models/1/constantss.csv"}
```

**Fields**:
- `m`: Message type ("err")
- `uid`: kalixcli session identifier
- `cmd`: Command that failed
- `msg`: Error message (contains all debugging information)

**Usage Examples**:
```json
// File not found error
{"m":"err","uid":"X2vB3yCbrVqw","cmd":"load_model_file","msg":"Model file not found: /path/to/missing.ini"}

// Parse error (real example - note typo in filename)
{"m":"err","uid":"X2vB3yCbrVqw","cmd":"load_model_string","msg":"Command execution error: Failed to parse model: Could not load timeseries input. /Users/chas/github/Kalix/src/tests/example_models/1/constantss.csv"}
```

**Implementation Notes**:
- Remove `ErrorData.error.code` field
- Flatten `ErrorData.error.message` → `msg`
- Remove `ErrorData.error.details` (include in message text)

### Stop Command (stp)

**Purpose**: Interrupt currently running command (sent from IDE to kalixcli)
**Frequency**: Low (sent when user cancels operations)
**Size**: ~35 characters

```json
{"m":"stp","reason":"User cancel"}
```

**Fields**:
- `m`: Message type ("stp")
- `reason`: Optional reason for stopping

**Usage Examples**:
```json
// User clicked cancel button
{"m":"stp","reason":"User cancel"}

// Timeout or other programmatic stop
{"m":"stp","reason":"Timeout"}
```

### Stopped Response (stp)

**Purpose**: Confirm command was interrupted (sent from kalixcli to IDE)
**Frequency**: Low (sent in response to stop commands)
**Size**: ~65 characters

```json
{"m":"stp","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":2.1e3}
```

**Fields**:
- `m`: Message type ("stp")
- `uid`: kalixcli session identifier
- `cmd`: Command that was stopped
- `exec_ms`: Partial execution time in milliseconds

**Usage Examples**:
```json
// Simulation stopped after 2.1 seconds
{"m":"stp","uid":"X2vB3yCbrVqw","cmd":"run_simulation","exec_ms":2.1e3}

// Model loading interrupted
{"m":"stp","uid":"X2vB3yCbrVqw","cmd":"load_model_file","exec_ms":1.5e2}
```

### Query Command (query)

**Purpose**: Request information from kalixcli (sent from IDE to kalixcli)
**Frequency**: Low (sent when IDE needs session info)
**Size**: ~25 characters

```json
{"m":"query","q":"get_state"}
```

**Fields**:
- `m`: Message type ("query")
- `q`: Query type

**Supported Query Types**:
- `"get_state"`: Get current session state
- `"get_session_id"`: Get session identifier

**Usage Examples**:
```json
// Request current state
{"m":"query","q":"get_state"}

// Request session ID
{"m":"query","q":"get_session_id"}
```

**Response**: Query responses are sent as regular `res` messages with the query type as the command.

### Terminate Command (term)

**Purpose**: End the session and kill kalixcli process (sent from IDE to kalixcli)
**Frequency**: Very Low (sent on IDE shutdown)
**Size**: 12 characters

```json
{"m":"term"}
```

**Fields**:
- `m`: Message type ("term")

**Usage Examples**:
```json
// IDE shutting down - kill kalixcli
{"m":"term"}
```

## Implementation Guidance

### Migration Steps

1. **Phase 1: Rust Backend (kalixcli)**
   - Add compact message structs in `messages.rs`
   - Update `create_*_message` functions to support both formats
   - Add protocol negotiation in session handshake
   - Modify `handlers.rs` to parse both formats

2. **Phase 2: Java Frontend (kalixide)**
   - Update `JsonMessage` classes with compact field mappings
   - Modify `JsonStdioProtocol` to generate compact messages
   - Add protocol detection in message parsing
   - Update UI components to handle new field names

3. **Phase 3: Protocol Negotiation**
   - Send protocol version in initial `rdy` message
   - IDE detects compact support and switches mode
   - Maintain backward compatibility during transition

### Key Implementation Details

**Rust (kalixcli) Changes:**
```rust
// Add to messages.rs
#[derive(Serialize, Deserialize)]
pub struct CompactMessage {
    pub m: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uid: Option<String>,
    #[serde(flatten)]
    pub fields: serde_json::Value,
}
```

**Java (kalixide) Changes:**
```java
// Update JsonMessage.java
public static class CompactMessage {
    @JsonProperty("m") private String messageType;
    @JsonProperty("uid") private String sessionId;
    // Additional fields as needed
}
```

### Protocol Detection Strategy

**Detection Logic:**
- Check for `"type"` field → verbose protocol
- Check for `"m"` field → compact protocol
- Default to verbose for unknown formats

### Testing Strategy

1. **Unit Tests**: Message parsing/generation for both protocols
2. **Integration Tests**: Full request/response cycles
3. **Performance Tests**: Measure bandwidth reduction
4. **Compatibility Tests**: Mixed protocol scenarios

### Backward Compatibility

- Keep both protocols active during migration
- Remove verbose protocol only after IDE update deployment
- Add logging for protocol usage metrics

### Error Handling

- Invalid compact messages should fall back to error responses
- Malformed messages should not crash the session
- Log protocol parsing failures for debugging