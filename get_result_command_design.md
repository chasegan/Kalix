# get_result Command Design

## Overview
The `get_result` command retrieves timeseries results from a loaded and executed model. It supports CSV format output for frontend consumption.

## Command: `get_result`

### Request Format:
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

### Success Response (CSV Format):
```json
{
  "type": "command_result",
  "data": {
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

### Error Response (Series Not Found):
```json
{
  "type": "error",
  "timestamp": "2025-09-21T10:32:00Z",
  "session_id": "sess_20250921_103000_a7b9",
  "data": {
    "command": "get_result",
    "error": {
      "code": "RESULT_NOT_FOUND",
      "message": "Timeseries 'invalid_series_name' not found in model results"
    }
  }
}
```

## Implementation Notes
- Timeseries results are accessed from `model.data_cache` property using `get_existing_series_idx()`
- CSV format: `start_timestamp,timestep_seconds,value1,value2,value3,...`
- Start timestamp in ISO 8601 format (RFC3339)
- Timestep in seconds between values
- Values are comma-delimited series data
- Command follows existing Command trait pattern
- Integrates with current command registry system
- Error handling uses `CommandError::ResultNotFound` for missing series
- Units field currently returns "unknown" (TODO: add units to timeseries struct)
- Only CSV format is supported (validates against other formats)