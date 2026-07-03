# Claude Code Guide for KalixCLI - One-Shot Commands

This document provides Claude Code with essential information for using KalixCLI's direct command-line interface for simple hydrological modeling tasks.

## Quick Reference

**Binary:** `kalixcli`
**Model Format:** INI files
**Output Format:** CSV files
**Usage:** Direct command execution (no sessions)

## Core Commands

### Run Simulation

#### From Model File
```bash
kalixcli sim model.ini --output-file results.csv
```

#### From STDIN (Pipe Model Content)
```bash
echo "[MODEL]\nname=test_model\n[NODES]\nnode1=10,20" | kalixcli sim --output-file results.csv
```

#### Without Output File (Results to Console)
```bash
kalixcli sim model.ini
```

**Parameters:**
- `model_file` (optional): Path to INI model file. If omitted, reads from STDIN
- `--output-file <path>`: CSV output file path (optional)

### Performance Testing

#### Run Benchmarks
```bash
kalixcli test
```

#### Timed Simulation Test
```bash
kalixcli test --sim-duration-seconds 30
```
Shows progress from 0% to 100% over specified duration.

### API Discovery

#### Get Complete API Specification
```bash
kalixcli get-api
```
Returns JSON description of all commands and parameters.

#### Format API Output
```bash
kalixcli get-api | jq '.'
```

### Calibration (Placeholder)

#### Basic Calibration
```bash
kalixcli calibrate
```

#### With Configuration
```bash
kalixcli calibrate --config calibration.conf --iterations 5000
```

**Note:** Calibration is currently a placeholder and returns a "not yet implemented" message.

## Model Format (INI)

### Basic Structure
```ini
[MODEL]
name=watershed_model
description=Simple watershed model

[NODES]
# Format: node_name=x_coordinate,y_coordinate
catchment1=100,200
outlet=150,100

[LINKS]
# Format: from_node=to_node
catchment1=outlet

[PARAMETERS]
# Model-specific parameters
area_catchment1=25.5
curve_number_catchment1=70

[TIMESERIES]
# Input data file references
rainfall=rainfall_data.csv
evaporation=evap_data.csv
```

### Example Minimal Model
```ini
[MODEL]
name=simple_test

[NODES]
inlet=0,0
outlet=100,0

[LINKS]
inlet=outlet

[PARAMETERS]
flow_rate=10.0
```

## File I/O

### Input Files
- **Model files:** `.ini` format (human-readable configuration)
- **Data files:** `.csv` format (timeseries data referenced in model)

### Output Files
- **Results:** `.csv` format (timeseries simulation results)
- **Format:** Timestamp, timestep, value columns

## Error Handling

### Common Issues
- **Invalid model file:** Check INI syntax and required sections
- **Missing data files:** Ensure CSV files referenced in `[TIMESERIES]` exist
- **File permissions:** Verify read/write access to input/output paths
- **Invalid parameters:** Use `kalixcli --help` or `kalixcli <command> --help`

### Exit Codes
- `0`: Success
- `1`: Error (details printed to stderr)

## Usage Examples

### Complete Modeling Workflow
```bash
# 1. Test the system
kalixcli test

# 2. Run simulation from file
kalixcli sim watershed_model.ini --output-file simulation_results.csv

# 3. Check API for available options
kalixcli get-api | jq '.subcommands[] | select(.name == "sim")'
```

### Programmatic Model Generation
```bash
# Generate model content and pipe to simulation
cat << EOF | kalixcli sim --output-file test_results.csv
[MODEL]
name=generated_model

[NODES]
source=0,0
sink=100,100

[LINKS]
source=sink

[PARAMETERS]
flow_rate=15.5
EOF
```

### Batch Processing
```bash
# Process multiple models
for model in models/*.ini; do
    output="results/$(basename "$model" .ini)_results.csv"
    kalixcli sim "$model" --output-file "$output"
done
```

## Integration Tips

1. **File-based workflow:** Use file I/O for reproducible results
2. **STDIN for dynamic models:** Generate models programmatically and pipe to `kalixcli sim`
3. **Error checking:** Always check exit codes (`$?` in bash)
4. **Output validation:** Verify CSV output files exist and contain expected data
5. **Performance testing:** Use `kalixcli test` to verify system functionality

## Limitations

- **No session state:** Each command runs independently
- **No progress feedback:** Long simulations run without progress updates
- **No result querying:** Must write results to files (cannot query specific timeseries)
- **Calibration incomplete:** Calibration commands are placeholder only

For interactive modeling, complex workflows, or progress monitoring, use the session mode documented in `claude-kalix.md`.