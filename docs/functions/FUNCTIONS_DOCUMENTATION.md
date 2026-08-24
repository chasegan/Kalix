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

## Program Blocks

A value can be a `{ ... }` program block: `;`-terminated statements followed
by a bare final expression, whose value is the block's value. Statements are
local assignments (`name = expression;`) and assertions (`assert(cond);`).

```ini
pond_demand = {
    target = table.monthly_demand(sim.month);
    recent = moving_mean(node.headwater.ds_1, 30, 0.0);
    assert(target >= 0);
    min(target, recent * const.demand_fraction)
    }
```

- The final line must be a bare expression with **no** `;` — a terminated
  final line is a load error ("program has no result value"), never a silent
  default.
- Locals are bare names, private to their block, case-insensitive, and must
  be assigned above their first use. Builtin function names cannot be used
  as locals. Dotted names (`data.*`, `node.*`, ...) are model references and
  cannot be assigned.
- `assert(cond)` fails the run — loudly, naming the statement, node, and
  timestep — when `cond` is 0 **or NaN**. NaN is exactly the case a modeller
  most needs caught.
- A block is only legal as the entire value; blocks cannot be nested inside
  expressions.

## User-Defined Functions

Define reusable functions in a `[fn]` section — the signature is the key, so
the name is written exactly once — and call them namespaced as `fn.name(...)`:

```ini
[fn]
storage_frac(v, cap) = v / cap
new_wy() = sim.new_month && sim.month == 7
net_demand(pop, doy) = {
    base = pop * const.per_capita;
    peak = 1 + 0.3 * sin(2 * 3.14159 * doy / 365);
    base * peak
    }
```

- **Fixed signatures**: no default arguments, no overloads; calls bind
  positionally; zero-argument functions are legal. Duplicate names are a
  load error, even at different arities.
- **Definitions live anywhere** in the model file, including after their
  callers — functions are passive, like tables.
- Bodies may be plain expressions or `{ ... }` blocks, and may reference
  `data.*`, `node.*`, `const.*`, `sim.*`, `table.*`, other `fn.*` — and `this.`,
  which rebinds to the **calling** node, turning a function into a rule
  template applied at many nodes.
- **No recursion**, direct or mutual — the call graph is checked at load.
- Functions have zero runtime cost: every call site is expanded at model
  load (arguments bind once, body locals can never collide with the
  caller's), so a function used at fifty nodes runs exactly as fast as
  fifty pasted copies — including short-circuiting: a call in an untaken
  `if` branch (or short-circuited `&&`/`||` operand) does not execute.
- Two consequences worth knowing: an `assert` inside a body is a
  **precondition** — it fires only when the call's branch is actually
  taken (an invariant that must hold always belongs at statement level,
  where asserts always run); and a stateful builtin inside a body gets
  independent state per call site, advancing every step even when its
  branch is untaken, so window values never depend on branching history.

## Model Variables

`[var.*]` sections hold published calculations. Unlike tables and functions
(passive — defined anywhere), var blocks are **active**: they execute at
their file position among the nodes, in the flow phase, reading anything
computed above them. The file reads downstream, calculations included.

```ini
[var.accounting]
inflow_wy = sum_since(node.headwater.ds_1, fn.new_wy())
headroom = {
    cap = const.annual_cap;
    assert(cap > 0);
    cap - var.accounting.inflow_wy
    }
prev_headroom = var.accounting.headroom[-1, -999]
```

- Section name is the namespace: key `headroom` in `[var.accounting]` is the
  series `var.accounting.headroom` — computed exactly once per timestep,
  readable in any expression, offset-addressable, and recordable in
  `[outputs]` like any node output.
- Keys evaluate top to bottom; later keys see this step's earlier keys.
- Reading a value computed *below* the block's position (this-timestep) is
  caught at run start by the same validation as node references; use
  `[-1, default]` for the previous step's value.
- `phase = flow` (default). `phase = order` is designed but not yet
  implemented and is rejected at load.
- This replaces the dummy-gauge / `reference_flow` scratch-node workaround.

## Temporal (Stateful) Functions

These functions remember earlier timesteps. Their state advances exactly
once per timestep, **unconditionally** — a `moving_mean` inside an untaken
`if` branch still updates, so its value is a property of the series, never
of which branches past evaluations took.

### Moving windows — the last n steps

`moving_sum(x, n, default)`, `moving_mean(...)`, `moving_min(...)`,
`moving_max(...)`

One shared signature: `x` is any expression; `n` is a positive integer
literal (state is sized at model load); `default` is the **element
default** — the window is pre-filled with it at run start, so the statistic
is well-defined from step 0 and warms up from a stated assumption.

```ini
recent_flow = moving_mean(node.gauge_1.dsflow, 30, 0.0)
```

NaN behaviour follows each function's scalar counterpart: a NaN entering
`moving_sum`/`moving_mean` makes the statistic NaN until it leaves the
window (n steps); `moving_min`/`moving_max` suppress NaN exactly as
`min`/`max` do.

### Annual, monthly, and daily windows — the last n water years/months/days

`moving_sum_years(x, n_years, wy_month)`,
`moving_min_years(...)`, `moving_max_years(...)`

`moving_sum_months(x, n_months)`,
`moving_min_months(...)`, `moving_max_months(...)`

`moving_sum_days(x, n_days)`,
`moving_min_days(...)`, `moving_max_days(...)`

A different shape from the moving windows above: `x` is bucketed by
calendar period first — one running total (or running min/max) per water
year, month, or day — and the statistic is reported over the last `n`
*buckets*, not the last `n` steps. Argument order mirrors the fixed windows:
the value, then the window length, then whatever the variant needs (the
water-year anchor for `_years`; nothing for the others). There is no
`default` argument: a bucket not yet reached contributes nothing to a sum
(it starts at 0), and min/max start undefined and read NaN until real data
arrives, same as an empty `moving_min`/`moving_max` window.

`moving_*_years` takes an explicit anchor month, `wy_month` (1-12): a new
water year starts on the first step whose month equals it. This is the
engine's one water-year concept — see the note under Event windows below.
`moving_*_months`/`moving_*_days` take no anchor; a new bucket starts on
every calendar month/day.

```ini
[const]
const.wy_month = 7
```
```ini
# Trailing 3-year total, updated daily, water year starting 1 July
three_yr_diversion = moving_sum_years(node.town.diversion, 3, const.wy_month)

# Trailing 12-month mean
rolling_12mo_total = moving_sum_months(node.gauge_1.dsflow, 12)
```

**There is deliberately no `moving_mean_*` in this family.** For sum, min and
max the calendar bucketing only changes *when values leave the window* — the
statistic itself is unchanged, because `max(max A, max B) = max(A ∪ B)` and
likewise for sums. A mean is different: dividing by the bucket count makes it
a *rate per period* (a mean annual total, not a mean of `x`), and the right
divisor during warm-up — when fewer than `n` periods have elapsed — is a
modelling decision, not something the engine should guess. Write it out:

```ini
mean_annual_diversion = moving_sum_years(node.town.diversion, 3, const.wy_month) / 3
```

That way the divisor, and what it assumes about the first three years, is
visible in the model file.

At a daily timestep, `moving_*_days(x, n)` is equivalent to
`moving_*(x, n, 0)` from the section above (every step is a new bucket); it
earns its keep at sub-daily timesteps, bucketing several steps into one day.

**NaN policy differs from `moving_sum`.** A NaN entering
`moving_min_years`/`max` (and the monthly/daily equivalents) is suppressed,
never poisoning the tracked extremum — matching plain `moving_min`/`max`.
But `moving_sum_years`/`mean` still **poison** on NaN, exactly like plain
`moving_sum`/`moving_mean`: a genuine gap in a raw series should make that
water year's total suspect, not silently vanish.

This asymmetry matters for a common pattern: gating a value to only count
once per period, via `if(cond, x, NAN)` — the *only* way to express "only
this branch counts" in this language. Feed that straight into
`moving_max_years`/`min` and it works, because NaN is exactly what those
suppress. Feed it straight into `moving_sum_years`/`mean` and the first
untagged step poisons the running total. Wrap it in `infill(..., 0)` for the
sum/mean side to get the same gated behaviour there. Note `infill`
*substitutes* the value rather than excluding the element — zero is the right
fill for a sum precisely because it is the additive identity, but the same
substitution inside a plain `moving_mean` would be counted in the average, so
state the fill deliberately:

```ini
daily_total = if(sim.new_day, moving_sum_days(node.gauge_1.dsflow, 1), 0.0 / 0.0)
peak_daily_total_wy = moving_max_years(daily_total, 5, const.wy_month)
sum_daily_totals_wy = moving_sum_years(infill(daily_total, 0), 5, const.wy_month)
```

### Event windows — since a reset condition last fired

`sum_since(x, reset)`, `min_since(x, reset)`, `max_since(x, reset)`,
`count_since(cond, reset)`, `steps_since(reset)`

The **last argument is always the reset condition** (any expression, truthy
when non-zero). `count_since` counts the steps on which `cond` held;
`steps_since` counts steps elapsed. Semantics are **reset-then-accumulate**:
on the step the reset fires, the accumulator clears first and that step's
contribution is then included — on 1 July, "usage this water year" equals
that day's usage, not zero. Run start acts as an implicit reset.

```ini
[const]
const.wy_month = 7
```
```ini
used_wy = sum_since(node.town.diversion, sim.new_month && sim.month == const.wy_month)
dry_spell = steps_since(node.gauge.dsflow > const.low_flow_threshold)
spill_days = count_since(node.dam.ds_1_spill > 0, sim.new_year)
```

`sum_since`/`*_since` and the `sim.*` flags deliberately carry no
water-year concept of their own: the boundary is an idiom written at the
point of use (or named once per model via a constant), because the
water-year month varies from valley to valley. `moving_*_years` above is
the one deliberate exception — a trailing multi-year window needs ring
state a reset condition alone can't express, so it takes `wy_month`
directly rather than forcing a hand-rolled ring via `[fn]`.

### Calendar boundary flags

`sim.new_day`, `sim.new_month`, `sim.new_year` — 1.0 on the first step of
each calendar day/month/year (and at step 0), 0.0 otherwise. Timestep-agnostic:
at hourly resolution `sim.new_month && sim.month == 7` is true only for the
first hour of 1 July, where the naive `sim.day == 1 && sim.month == 7` would
fire 24 times.

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
| `clamp` | 3 | Constrain to a range: clamp(x, lo, hi) = min(max(x, lo), hi) |
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
| `infill` | 2 | Infill a missing value: infill(x, value) is value when x is NaN, else x |
| `moving_sum` | 3 | Sum over the last n steps: moving_sum(x, n, default) |
| `moving_mean` | 3 | Mean over the last n steps |
| `moving_min` | 3 | Minimum over the last n steps |
| `moving_max` | 3 | Maximum over the last n steps |
| `moving_sum_years` | 3 | Sum over the last n_years water years: moving_sum_years(x, n_years, wy_month) |
| `moving_min_years` | 3 | Minimum over the last n_years water years |
| `moving_max_years` | 3 | Maximum over the last n_years water years |
| `moving_sum_months` | 2 | Sum over the last n_months calendar months: moving_sum_months(x, n_months) |
| `moving_min_months` | 2 | Minimum over the last n_months calendar months |
| `moving_max_months` | 2 | Maximum over the last n_months calendar months |
| `moving_sum_days` | 2 | Sum over the last n_days calendar days: moving_sum_days(x, n_days) |
| `moving_min_days` | 2 | Minimum over the last n_days calendar days |
| `moving_max_days` | 2 | Maximum over the last n_days calendar days |
| `sum_since` | 2 | Sum of x since reset last fired: sum_since(x, reset) |
| `min_since` | 2 | Minimum of x since reset |
| `max_since` | 2 | Maximum of x since reset |
| `count_since` | 2 | Steps on which cond held since reset: count_since(cond, reset) |
| `steps_since` | 1 | Steps elapsed since reset (0 on a reset step) |

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

- **Richer calendar logic** — `days_in_month` and similar fields, building
  on the `sim.*` namespace. (The boundary flags `sim.new_day/month/year`
  shipped with the temporal functions; there is deliberately no water-year
  field — see Temporal Functions above.)
- **Order-phase model variables** — `phase = order` on `[var.*]` blocks
  (the order phase walks bottom-up; the interleave with the ordering system
  is designed but unimplemented — rejected at load meanwhile).
- **Inline interpolation** — `interp(x, x1, y1, x2, y2, ...)` for tiny two-
  or three-point relationships that don't warrant a named `[table.*]` section.
- **File-backed tables** — `file = ./tables/rating.csv` as an alternative to
  inline `values` in `[table.*]` sections.
- **Bilinear 2D lookup** — a per-table opt-in interpolating across column
  keys as well as down rows, for continuous (rather than integer-like) column
  keys. Backward compatible: today's exact-match tables are unaffected.
