# get_result Command Design

## Overview
The `get_result` command retrieves timeseries results from a loaded and executed model. It supports CSV format output for frontend consumption.

## Command: `get_result`

### Request Format:
```json
{"m":"cmd","c":"get_result","p":{"series_name":"rainfall_station_01","format":"csv"}}
```

### Success Response (CSV Format):
```json
{"m":"res","uid":"X2vB3yCbrVqw","cmd":"get_result","exec_ms":23.1,"ok":true,"r":{"series_name":"rainfall_station_01","format":"csv","metadata":{"start_timestamp":"2023-01-01T00:00:00Z","timestep_seconds":3600,"total_points":8760,"units":"unknown"},"data":"2023-01-01T00:00:00Z,3600,1.2,2.1,0.8,1.5,..."}}
```

### Error Response (Series Not Found):
```json
{"m":"err","uid":"X2vB3yCbrVqw","cmd":"get_result","msg":"Timeseries 'invalid_series_name' not found in model results"}
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