# Claude Code Guide for KalixCLI

This document provides Claude Code with the essential information needed to effectively use KalixCLI for hydrological modeling tasks.

## Quick Reference

**Binary:** `kalixcli`
**Primary Mode:** Interactive JSON session via `kalixcli new-session`
**Model Format:** INI files or INI strings
**Output Format:** CSV timeseries data

## Core Usage Pattern

### 1. Start Interactive Session
```bash
kalixcli new-session
```
This enters a JSON-based STDIO protocol where you send line-delimited JSON commands and receive responses.

### 2. Session Communication
All messages are JSON objects with this structure:
```json
{
  "type": "command|stop|terminate",
  "data": { /* command-specific payload */ }
}
```

### 3. Essential Commands

#### Load Model from String
```json
{
  "type": "command",
  "data": {
    "command": "load_model_string",
    "parameters": {
      "model_ini": "[MODEL]\nname=test_model\n[NODES]\nnode1=10,20\n..."
    }
  }
}
```

#### Load Model from File
```json
{
  "type": "command",
  "data": {
    "command": "load_model_file",
    "parameters": {
      "model_path": "/path/to/model.ini"
    }
  }
}
```

#### Run Simulation
```json
{
  "type": "command",
  "data": {
    "command": "run_simulation",
    "parameters": {}
  }
}
```

#### Get Results
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

## Response Types

### Ready Message
Indicates the system is ready for the next command:
```json
{
  "type": "ready",
  "kalixcli_uid": "sess_...",
  "data": {
    "commands": [...],
    "state": {
      "model_loaded": true,
      "data_loaded": true
    }
  }
}
```

### Progress Updates
For long-running operations like `run_simulation`:
```json
{
  "type": "progress",
  "data": {
    "command": "run_simulation",
    "progress": {
      "percent_complete": 45.2,
      "current_step": "Running simulation - Processing timestep 452 of 1000"
    }
  }
}
```

### Results
Command completion:
```json
{
  "type": "result",
  "data": {
    "command": "get_result",
    "status": "success",
    "result": {
      "series_name": "rainfall_station_01",
      "format": "csv",
      "data": "2023-01-01T00:00:00Z,3600,1.2,2.1,0.8,1.5,..."
    }
  }
}
```

### Errors
```json
{
  "type": "error",
  "data": {
    "command": "get_result",
    "error": {
      "code": "RESULT_NOT_FOUND",
      "message": "Timeseries 'invalid_name' not found in model results"
    }
  }
}
```

## Model Format (INI)

Kalix models use INI format with these key sections:

```ini
[MODEL]
name=watershed_model
description=Example watershed model

[NODES]
# Node definitions: name=x,y
catchment1=100,200
outlet=150,100

[LINKS]
# Connections: from_node=to_node
catchment1=outlet

[PARAMETERS]
# Model parameters
area_catchment1=25.5
curve_number_catchment1=70

[TIMESERIES]
# Input data references
rainfall=rainfall_data.csv
```

## Alternative: Direct CLI Commands

For simple one-shot operations:

### Run Simulation from File
```bash
kalixcli sim model.ini --output-file results.csv
```

### Run Simulation from STDIN
```bash
echo "[MODEL]\nname=test" | kalixcli sim --output-file results.csv
```

### Performance Tests
```bash
kalixcli test --sim-duration-seconds 10
```

### API Discovery
```bash
kalixcli get-api
```

## Error Handling

Common error codes:
- `INVALID_PARAMETERS` - Missing/invalid parameters
- `MODEL_NOT_LOADED` - Operation requires loaded model
- `RESULT_NOT_FOUND` - Requested timeseries not found
- `EXECUTION_ERROR` - General execution failure
- `IO_ERROR` - File system error

## Integration Tips for Claude Code

1. **Use Sessions for Complex Workflows:** Start with `kalixcli new-session` for multi-step modeling tasks
2. **Monitor Progress:** Watch for `progress` messages during long simulations
3. **Handle Interruptions:** Send `{"type": "stop"}` to interrupt running commands
4. **Validate Models:** Load models first to check for errors before running simulations
5. **Graceful Termination:** Send `{"type": "terminate"}` or kill process when done

## Typical Workflow

1. Start session: `kalixcli new-session`
2. Load model: `load_model_string` or `load_model_file`
3. Run simulation: `run_simulation` (monitor progress)
4. Retrieve results: `get_result` for each desired timeseries
5. Terminate: `{"type": "terminate"}`

This covers the essential information needed to programmatically control KalixCLI for hydrological modeling tasks.