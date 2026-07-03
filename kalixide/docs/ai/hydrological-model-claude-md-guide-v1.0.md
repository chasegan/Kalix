# Hydrological Model Claude.md Guide v1.0

This document provides recommendations for creating an effective `claude.md` file when using Claude Code with Kalix hydrological models.

## Recommended `claude.md` Structure for Hydrological Models

### 1. **Model Format Specification**
```markdown
# Hydrological Model Format (INI)

## File Structure
Models are defined in INI format with the following sections:
- `[model]` - Global model configuration
- `[data]` - Input data specifications
- `[output]` - Output configuration
- `[node.{name}]` - Individual node definitions
- `[link.{name}]` - Link definitions between nodes

## Key Conventions
- Node names must be unique within the model
- Coordinates are specified as `loc = x, y` (floating point)
- Time series data referenced by file paths or identifiers
- Flow directions defined by link source/target relationships
```

### 2. **Node Types and Parameters**
```markdown
## Supported Node Types

### Rainfall-Runoff Models
- `type = sacramento` - Sacramento Soil Moisture Accounting model
- `type = gr4j` - GR4J conceptual rainfall-runoff model
- Key parameters: `area`, `rain_data`, `pet_data`, calibration parameters

### Storage Nodes
- `type = reservoir` - Storage with inflow/outflow relationships
- `type = lake` - Natural water body storage
- Key parameters: `capacity`, `initial_storage`, `outlet_config`

### Process Nodes
- `type = loss` - Evaporation, seepage losses
- `type = routing` - Flow routing/timing
- `type = confluence` - Multiple inflow combination

### Boundary Conditions
- `type = inflow` - External water inputs
- `type = outflow` - Model boundary outputs
```

### 3. **Common Patterns and Examples**
```markdown
## Typical Model Patterns

### Basic Catchment Model
```ini
[model]
name = Sample Catchment
start_time = 2020-01-01
end_time = 2020-12-31
timestep = daily

[node.catchment1]
type = sacramento
loc = 100, 200
area = 500.0
rain_data = rainfall.csv
pet_data = pet.csv

[node.outlet]
type = outflow
loc = 150, 100

[link.flow1]
source = catchment1
target = outlet
```

### Multi-Catchment System
- Pattern for connecting multiple sub-catchments
- Use of confluence nodes for combining flows
- Routing between catchment outlets
```

### 4. **Data File Conventions**
```markdown
## Input Data Format

### Time Series Files (CSV)
- First column: timestamp (YYYY-MM-DD or YYYY-MM-DD HH:MM:SS)
- Subsequent columns: data values
- Headers specify data type/location
- Missing values: leave blank or use NaN

### Coordinate System
- Projected coordinates (meters) preferred
- Consistent CRS across all model components
- Origin and scale appropriate for catchment size
```

### 5. **Calibration and Optimization**
```markdown
## Model Calibration

### Parameter Bounds
- Sacramento: typical ranges for UZTWM, UZFWM, UZK, etc.
- GR4J: X1 (100-1200), X2 (-5 to 3), X3 (20-300), X4 (1.1-2.9)

### Optimization Targets
- Nash-Sutcliffe efficiency at outlet nodes
- Peak flow timing and magnitude
- Low flow characteristics
- Water balance closure

### Validation Approaches
- Split-sample testing (calibration vs validation periods)
- Cross-validation for parameter uncertainty
- Spatial validation across sub-catchments
```

### 6. **Common Issues and Solutions**
```markdown
## Troubleshooting Guide

### Model Convergence Issues
- Check mass balance closure
- Verify all nodes have required parameters
- Ensure link connectivity creates valid flow network

### Data Issues
- Timestamp alignment between different data sources
- Units consistency (mm/day vs m³/s)
- Missing data handling strategies

### Performance Optimization
- Node placement to minimize routing complexity
- Time step selection for stability vs accuracy
- Memory considerations for large catchments
```

### 7. **Workflow Integration**
```markdown
## Development Workflow

### Version Control
- Track model files (.ini) and data dependencies
- Document parameter changes and calibration iterations
- Use meaningful commit messages for model versions

### Testing Strategy
- Unit tests for individual node behavior
- Integration tests for full model runs
- Benchmark against known results

### Documentation Standards
- Model purpose and study area description
- Data sources and preprocessing steps
- Calibration methodology and results
- Known limitations and assumptions
```

### 8. **Platform-Specific Features**
```markdown
## Kalix-Specific Features

### GUI Integration
- Visual node placement affects `loc = x, y` coordinates
- Map display requires valid coordinate system
- Zoom-to-fit works best with reasonable coordinate ranges

### CLI Integration
- Model validation via `kalixcli validate model.ini`
- Batch runs for calibration: `kalixcli run --optimize`
- Result export: `kalixcli export --format csv`

### File Watching
- Auto-reload enabled for clean model files
- Changes to node coordinates sync between text and map
- Preference for team collaboration via kalix_prefs.json
```

## Implementation Benefits

This structure would give Claude comprehensive context about:

- **Syntax and structure** of your INI models
- **Domain knowledge** about hydrological modeling
- **Common patterns** you use repeatedly
- **Debugging approaches** for typical issues
- **Integration points** with your Kalix platform

This would enable Claude to provide much more targeted assistance with model development, debugging, optimization, and documentation tasks specific to your hydrological modeling workflow.

## Usage Recommendations

1. **Copy relevant sections** to your project's `claude.md` file
2. **Customize examples** with your specific node types and parameters
3. **Add project-specific conventions** and naming patterns
4. **Include study area context** and typical model scales
5. **Document team workflows** and collaboration practices

## Version History

- v1.0 (September 2025) - Initial guide based on Kalix platform features and hydrological modeling best practices