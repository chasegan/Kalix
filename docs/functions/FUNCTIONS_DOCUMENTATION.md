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

See [data_references.md](../data_references.md), [node_references.md](../node_references.md)
and [sim_references.md](../sim_references.md) for the `data.*`, `node.*` and `sim.*`
namespaces, including temporal offset syntax like `data.flow[-1, 0.0]`.

### Expressions
```ini
evap = data.base_evap * 1.2
rain = data.rainfall_a + data.rainfall_b
```

## Arithmetic

Standard operators: `+`, `-`, `*`, `/`, `%` (modulo), `^` or `**` (power)

```ini
flow = (data.inflow - data.loss) * 0.95
adjusted = data.value ^ 2 + 10
```

## Conditional Logic

Use `if(condition, true_value, false_value)` for conditional expressions.

```ini
# Summer evaporation is higher
evap = if(sim.month > 10, data.evap_summer, data.evap_winter)

# Apply seasonal adjustment
rain = data.rainfall * if(data.season == 1, 1.2, 0.8)

# Clamp negative values to zero
inflow = if(data.raw_flow < 0, 0, data.raw_flow)
```

Comparison operators: `>`, `<`, `>=`, `<=`, `==`, `!=`

Logical operators: `&&` (and), `||` (or), `!` (not). Conditions treat any
non-zero value as true. `if`, `&&` and `||` short-circuit: only the branch
that is taken gets evaluated.

## Lookup Tables

Named tables defined in `[table.*]` sections can be called like functions —
`table.my_table(x)` for 1D interpolation, `table.my_table(col_key, row_key)`
for 2D. See [table_references.md](../table_references.md) for the full syntax
and semantics.

```ini
flow = table.rating(node.reach_5.dsflow)
release = table.pump_rating(sim.month, node.dam.volume)
```

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
    data.base_demand * if(sim.month > 10, 1.5, 1.0),
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

Two names are deliberately absent. There is no `log`: write the explicit
`ln` or `log10`. And there is no `avg` or `average`: the function is `mean`,
named for the specific statistic ("average" is the family that also contains
the median and mode).

## Notes

- Expressions are parsed once at model load and evaluated once per timestep
- Simple constants and data references are optimized for performance
- Whitespace is ignored: `a+b` and `a + b` are equivalent
- Function names, like all references, are **case-insensitive**
- Mathematical edge cases follow IEEE 754: division by zero gives ±∞,
  domain errors (e.g. `sqrt(-1)`) give NaN, and NaN propagates through
  calculations — check outputs for NaN/∞ to detect problems

## Future Directions

The features below are **not yet implemented**. They are recorded here as the
intended growth path of the expression language, roughly in priority order.

- **Stateful / temporal functions** — moving averages, rolling sums,
  days-since-event counters, and accumulators. The largest expressive gap:
  expressions are currently pure and stateless, so anything that remembers
  earlier timesteps (beyond fixed offsets like `[-1, 0.0]`) is impossible.
- **Convenience functions** — `clamp(x, lo, hi)` as a clearer spelling of
  `min(max(x, lo), hi)`.
- **Richer calendar logic** — water-year fields, `days_in_month`, leap-year
  awareness, building on the `sim.*` namespace.
- **Named intermediate values** — `let`-style bindings or user-defined
  functions, so a long expression can name its parts and reuse them.
- **Inline interpolation** — `interp(x, x1, y1, x2, y2, ...)` for tiny two-
  or three-point relationships that don't warrant a named `[table.*]` section.
- **File-backed tables** — `file = ./tables/rating.csv` as an alternative to
  inline `data` in `[table.*]` sections.
- **Bilinear 2D lookup** — a per-table opt-in interpolating across column
  keys as well as down rows, for continuous (rather than integer-like) column
  keys. Backward compatible: today's exact-match tables are unaffected.
