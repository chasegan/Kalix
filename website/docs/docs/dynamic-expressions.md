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
| `clamp` | 3 | Constrain to a range: clamp(x, lo, hi) |
| `moving_sum` | 3 | Sum over the last n steps: moving\_sum(x, n, default) |
| `moving_mean` | 3 | Mean over the last n steps |
| `moving_min` | 3 | Minimum over the last n steps |
| `moving_max` | 3 | Maximum over the last n steps |
| `sum_since` | 2 | Sum of x since a reset condition last fired |
| `min_since` | 2 | Minimum of x since reset |
| `max_since` | 2 | Maximum of x since reset |
| `count_since` | 2 | Steps on which a condition held since reset |
| `steps_since` | 1 | Steps elapsed since reset (0 on a reset step) |

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

### Program Blocks

A value can be a `{ ... }` program block: statements terminated by `;`, then a
bare final expression whose value is the block's value. Statements are local
assignments and assertions.

```ini
pond_demand = {
    target = table.monthly_demand(sim.month);
    recent = moving_mean(node.headwater.ds_1, 30, 0.0);
    assert(target >= 0);
    min(target, recent * c.demand_fraction)
    }
```

- The final line must be a bare expression with **no** `;` — a terminated
  final line is a load error, never a silent default.
- Locals are bare lowercase names, private to their block, and must be
  assigned above their first use. They cannot take the name of any inbuilt
  function or reserved word.
- `assert(cond)` stops the run — naming the statement, node, and timestep —
  when `cond` is 0 **or NaN**. NaN is exactly the case you most want caught.
- A block is only legal as the entire value.

### Temporal Functions

These functions remember earlier timesteps. Their state advances exactly once
per timestep, unconditionally — a `moving_mean` inside an untaken `if` branch
still updates, so its value depends only on the series it watches, never on
which branches past evaluations took.

**Moving windows** — `moving_sum(x, n, default)` and friends compute over the
last `n` steps. `n` and `default` must be plain numbers (state is sized when
the model loads): `default` pre-fills the window, so the statistic is
well-defined from the very first step.

```ini
recent_flow = moving_mean(node.gauge_1.dsflow, 30, 0.0)
```

**Event windows** — the `*_since` family accumulates since a reset condition
last fired, and the **last argument is always the reset condition**. On the
step the reset fires, the accumulator clears first and that step's
contribution is then included — on 1 July, "usage this water year" equals that
day's usage, not zero. The start of the run counts as a reset.

```ini
used_wy = sum_since(node.town.diversion, sim.new_month && sim.month == 7)
dry_spell = steps_since(node.gauge.dsflow > c.low_flow_threshold)
spill_days = count_since(node.dam.ds_1_spill > 0, sim.new_year)
```

There is deliberately no water-year setting in Kalix — the boundary is an
expression written where it's used (or named once per model in a
[user-defined function](fn.md)), because the water year varies from valley
to valley.

### User-Defined Functions

Functions defined in the [`[fn]` section](fn.md) are called with the `fn.`
prefix:

```ini
order = fn.net_demand(data.town.by_name.population, sim.day_of_year)
```

### Model Variables

Values published by [`[var.*]` blocks](vars.md) are read like any series,
including with the offset syntax:

```ini
release = min(this.order, var.accounting.headroom)
prev = var.accounting.headroom[-1, 0.0]
```

### Notes

- Expressions are evaluated once per timestep

- Simple constants and data references are optimised for performance

- Whitespace is ignored: `a+b` and `a + b` are equivalent

- Function names are not case sensitive when *called*; names you *define*
  (functions, parameters, var blocks and keys) are strictly lowercase

- Nothing you name may collide with an inbuilt function, a temporal function,
  or a reserved word (`assert`, `this`) — the load error names the clash

[Referencing Input Data](referencing-input-data.md)[Referencing Model Results](referencing-model-results.md)[Simulation Context Vars](simulation-context-vars.md)[Constants](constants.md)[Tables](tables.md)
