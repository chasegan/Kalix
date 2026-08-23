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
| `infill` | 2 | Infill a missing value: `infill(x, value)` is value when x is NaN, else x unchanged |
| `clamp` | 3 | Constrain to a range: clamp(x, lo, hi) |
| `is_leap_year` | 1 | 1 in a Gregorian leap year, else 0: is\_leap\_year(sim.year) |
| `month_at` | 1 | Month (1-12) at the current date + n days — the pattern month an order placed today arrives in |
| `days_in_month_at` | 1 | Leap-aware days in the month at the current date + n days |
| `moving_sum` | 3 | Sum over the last n steps: moving\_sum(x, n, default) |
| `moving_mean` | 3 | Mean over the last n steps |
| `moving_min` | 3 | Minimum over the last n steps |
| `moving_max` | 3 | Maximum over the last n steps |
| `moving_annual_sum` | 3 | Sum over the last n\_years water years: moving\_annual\_sum(x, wy\_month, n\_years) |
| `moving_annual_mean` | 3 | Mean over the last n\_years water years |
| `moving_annual_min` | 3 | Minimum over the last n\_years water years |
| `moving_annual_max` | 3 | Maximum over the last n\_years water years |
| `moving_monthly_sum` | 2 | Sum over the last n\_months calendar months: moving\_monthly\_sum(x, n\_months) |
| `moving_monthly_mean` | 2 | Mean over the last n\_months calendar months |
| `moving_monthly_min` | 2 | Minimum over the last n\_months calendar months |
| `moving_monthly_max` | 2 | Maximum over the last n\_months calendar months |
| `moving_daily_sum` | 2 | Sum over the last n\_days calendar days: moving\_daily\_sum(x, n\_days) |
| `moving_daily_mean` | 2 | Mean over the last n\_days calendar days |
| `moving_daily_min` | 2 | Minimum over the last n\_days calendar days |
| `moving_daily_max` | 2 | Maximum over the last n\_days calendar days |
| `sum_since` | 2 | Sum of x since a reset condition last fired |
| `min_since` | 2 | Minimum of x since reset |
| `max_since` | 2 | Maximum of x since reset |
| `count_since` | 2 | Steps on which a condition held since reset |
| `steps_since` | 1 | Steps elapsed since reset (0 on a reset step) |

Two names are deliberately absent. There is no `log`: write the explicit `ln`
or `log10`. And there is no `avg` or `average`: the function is `mean`, named
for the specific statistic ("average" is the family that also contains the
median and mode).

The calendar pair (`month_at`, `days_in_month_at`) exists for order-ahead
pattern lookups — orders are placed `lag` days before delivery, so the demand
pattern belongs to the *arrival* month:

```ini
order = annual * table.pattern(month_at(3)) / days_in_month_at(3)
```

Negative offsets look back. The engine owns the calendar: the offset date is
computed exactly, so year boundaries and leap-February need no hand-rolled
logic and any offset length works.

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
    min(target, recent * const.demand_fraction)
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

**Annual, monthly, and daily windows** — `moving_annual_sum(x, wy_month,
n_years)` and friends work differently: `x` is bucketed by calendar period
first (one running total per water year, month, or day), and the statistic
is reported over the last `n` *buckets*, not the last `n` steps. There's no
`default` — a bucket not yet reached simply contributes nothing.
`moving_annual_*` takes an anchor month, `wy_month` (1-12): a new water year
starts the first time the month reaches it. `moving_monthly_*`/
`moving_daily_*` need no anchor — a new bucket starts every calendar
month/day.

```ini
# Trailing 3-year total, water year starting 1 July
three_yr_diversion = moving_annual_sum(node.town.diversion, 7, 3)

# Trailing 12-month mean
rolling_annual_mean_flow = moving_monthly_mean(node.gauge_1.dsflow, 12)
```

`moving_annual_min`/`max` (and their monthly/daily equivalents) suppress
NaN — a NaN input never disturbs the tracked extremum, matching plain
`moving_min`/`max`. `moving_annual_sum`/`mean` still **poison** on NaN, like
plain `moving_sum`/`mean`: a real gap in the data should make that year's
total suspect, not vanish quietly. This matters if you build a value with
`if(cond, x, 0.0 / 0.0)` to make it count only on certain steps (the usual
way to say "only this branch counts" in a side-effect-free expression
language, since there's no bare `nan` literal outside the `[offset,
default]` position) — that composes straight into `moving_annual_max`, but
needs `infill(..., 0)` to compose into `moving_annual_sum`. `infill` substitutes the value rather than dropping the element, so the fill is stated at the call site:

```ini
daily_total = if(sim.new_day, moving_daily_sum(node.gauge_1.dsflow, 1), 0.0 / 0.0)
peak_daily_total_wy = moving_annual_max(daily_total, 7, 5)
sum_daily_totals_wy = moving_annual_sum(infill(daily_total, 0), 7, 5)
```

**Event windows** — the `*_since` family accumulates since a reset condition
last fired, and the **last argument is always the reset condition**. On the
step the reset fires, the accumulator clears first and that step's
contribution is then included — on 1 July, "usage this water year" equals that
day's usage, not zero. The start of the run counts as a reset.

```ini
used_wy = sum_since(node.town.diversion, sim.new_month && sim.month == 7)
dry_spell = steps_since(node.gauge.dsflow > const.low_flow_threshold)
spill_days = count_since(node.dam.ds_1_spill > 0, sim.new_year)
```

`sum_since`/`*_since` and the calendar flags deliberately carry no
water-year setting of their own — the boundary is an expression written
where it's used (or named once per model in a [user-defined
function](fn.md)), because the water year varies from valley to valley.
`moving_annual_*` above is the one deliberate exception: a trailing
multi-year window needs more than a reset condition can express, so it
takes `wy_month` directly.

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
