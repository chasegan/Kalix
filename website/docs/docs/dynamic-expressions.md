---
title: "Dynamic Expressions"
---

# Dynamic Expressions

Many model parameters accept “dynamic expressions” which are mathematical expressions that may include data references, constants, model results, inbuilt variables.

### Basic Usage

The simplest dynamic expressions are constants, data references, model result references.

#### Constant expressions

```ini
evap = 5.0
rain = 2.5
```

#### Data References

```ini
evap = data.climate.by_name.evaporation
rain = data.rainfall.by_name.value
```

Click [here](referencing-input-data.md) to find out more about data references.

#### Model Result References

```ini
evap = data.climate.by_name.evaporation
rain = data.rainfall.by_name.value
```

Click [here](referencing-model-results.md) to find out more about model result references.

### Arithmetic

Dynamic expressions accept standard mathematical operators: `+`, `-`, `*`, `/`, `^`

```ini
evap = 1.2 * data.climate.by_name.evaporation
rain = 0.2 * data.rainfall.by_name.site_a + 0.8 * data.rainfall.by_name.site_b

observed = data.price_of_bananas.by_name.discounted ^ 2 + 10
```

### Conditional Logic

Use `if(condition, true_value, false_value)` for conditional expressions.

```ini
# Summer evaporation is higher
evap = if(data.month > 10, data.summer.by_index.1, data.winter.by_index.1)

# Apply seasonal adjustment
rain = data.rainfall.by_index.1 * if(data.season.by_index.1 == 1, 1.2, 0.8)

# Clamp negative values to zero
inflow = if(data.raw_flows.by_index.0 < 0, 0, data.raw_flows.by_index.0)
```

Comparison operators: `>`, `<`, `>=`, `<=`, `==`, `!=`

### Common Functions

You can also use common mathematical functions.

```ini
# Take the greater of two values
evap = max(data.evap.by_name.observed, data.evap.by_name.modelled)

# Ensure non-negative
inflow = max(data.flow.by_name.inflow - data.flow.by_name.loss, 0)

# Absolute value
diff = abs(observed - modeled)

# Power and square root
area = sqrt(area_squared)
volume = pow(radius, 3) * 3.14159

# Trigonometric
angle = sin(time * 0.1) * amplitude

# Complex example
evap = if(temp > 30,
    evap_high * 1.3,
    if(temp > 20, 
	      data.evap_medium, 
	      data.evap_low * 0.8))
```

Available functions:

| Function | Arguments | Description |
| --- | --- | --- |
| `if` | 3 | Conditional: if(condition, true\_val, false\_val) |
| `max` | 2+ | Maximum of values |
| `min` | 2+ | Minimum of values |
| `sum` | 1+ | Sum of values |
| `mean` | 1+ | Arithmetic mean of values |
| `abs` | 1 | Absolute value |
| `sqrt` | 1 | Square root |
| `pow` | 2 | Power: pow(base, exponent) |
| `exp` | 1 | Exponential (e^x) |
| `ln` | 1 | Natural logarithm |
| `log10` | 1 | Base-10 logarithm |
| `log2` | 1 | Base-2 logarithm |
| `sin` | 1 | Sine |
| `cos` | 1 | Cosine |
| `tan` | 1 | Tangent |
| `asin` | 1 | Arcsine |
| `acos` | 1 | Arccosine |
| `atan` | 1 | Arctangent |
| `atan2` | 2 | Two-argument arctangent: atan2(y, x) |
| `floor` | 1 | Round down |
| `ceil` | 1 | Round up |
| `round` | 1 | Round to nearest |
| `sign` | 1 | Sign (-1, 0, or 1) |

Two names are deliberately absent. There is no `log`: write the explicit `ln`
or `log10`. And there is no `avg` or `average`: the function is `mean`, named
for the specific statistic ("average" is the family that also contains the
median and mode).

### Lookup Tables

Named tables defined in `[table.*]` sections can be called like functions —
`table.my_table(x)` for 1D interpolation, `table.my_table(x, y)` for 2D.

```ini
flow = table.rating(node.reach_5.dsflow)
release = table.pump_rating(sim.month, node.dam.volume)
```

Click [here](tables.md) to find out more about lookup tables.

### Notes

- Expressions are evaluated once per timestep

- Simple constants and data references are optimised for performance

- Whitespace is ignored: `a+b` and `a + b` are equivalent

- Function names are not case sensitive

[Referencing Input Data](referencing-input-data.md)[Referencing Model Results](referencing-model-results.md)[Simulation Context Vars](simulation-context-vars.md)[Constants](constants.md)[Tables](tables.md)
