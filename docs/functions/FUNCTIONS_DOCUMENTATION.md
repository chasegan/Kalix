# Functions in Kalix

Model parameters can accept constants, data references, or mathematical expressions.

## Basic Usage

### Constants
```ini
evap = 5.0
rain = 2.5
```

### Data References
```ini
evap = data.climate.by_date.evaporation
rain = data.rainfall.by_name.value
```

### Expressions
```ini
evap = data.base_evap * 1.2
rain = data.rainfall_a + data.rainfall_b
```

## Arithmetic

Standard operators: `+`, `-`, `*`, `/`, `^`

```ini
flow = (data.inflow - data.loss) * 0.95
adjusted = data.value ^ 2 + 10
```

## Conditional Logic

Use `if(condition, true_value, false_value)` for conditional expressions.

```ini
# Summer evaporation is higher
evap = if(data.month > 10, data.evap_summer, data.evap_winter)

# Apply seasonal adjustment
rain = data.rainfall * if(data.season == 1, 1.2, 0.8)

# Clamp negative values to zero
inflow = if(data.raw_flow < 0, 0, data.raw_flow)
```

Comparison operators: `>`, `<`, `>=`, `<=`, `==`, `!=`

## Common Functions

### max and min
```ini
# Take the greater of two values
evap = max(data.evap_pan, data.evap_modeled)

# Ensure non-negative
flow = max(data.flow - data.loss, 0)

# Limit to capacity
release = min(data.demand, data.capacity)
```

### Mathematical Functions
```ini
# Absolute value
diff = abs(data.observed - data.modeled)

# Power and square root
area = sqrt(data.area_squared)
volume = pow(data.radius, 3) * 3.14159

# Trigonometric
angle = sin(data.time * 0.1) * data.amplitude
```

## Complex Examples

### Multi-condition Seasonal Adjustment
```ini
evap = if(data.temp > 30,
    data.evap_high * 1.3,
    if(data.temp > 20, data.evap_medium, data.evap_low * 0.8))
```

### Demand with Minimum Threshold
```ini
demand = max(
    data.base_demand * if(data.month > 10, 1.5, 1.0),
    100
)
```

### Flow with Loss and Constraints
```ini
net_flow = min(
    max(data.inflow - data.loss, 0),
    data.channel_capacity
)
```

## Available Functions

| Function | Arguments | Description |
|----------|-----------|-------------|
| `if` | 3 | Conditional: if(condition, true_val, false_val) |
| `max` | 2+ | Maximum of values |
| `min` | 2+ | Minimum of values |
| `abs` | 1 | Absolute value |
| `sqrt` | 1 | Square root |
| `pow` | 2 | Power: pow(base, exponent) |
| `exp` | 1 | Exponential (e^x) |
| `log` | 1 | Natural logarithm |
| `ln` | 1 | Natural logarithm (alias) |
| `log10` | 1 | Base-10 logarithm |
| `sin` | 1 | Sine |
| `cos` | 1 | Cosine |
| `tan` | 1 | Tangent |
| `asin` | 1 | Arcsine |
| `acos` | 1 | Arccosine |
| `atan` | 1 | Arctangent |
| `floor` | 1 | Round down |
| `ceil` | 1 | Round up |
| `round` | 1 | Round to nearest |
| `sign` | 1 | Sign (-1, 0, or 1) |

## Notes

- Expressions are evaluated once per timestep
- Simple constants and data references are optimized for performance
- Whitespace is ignored: `a+b` and `a + b` are equivalent
- Case-sensitive: use lowercase for function names
