---
title: "Simulation Context Vars"
---

# Simulation Context Vars

The `sim.*` namespace provides access to the current simulation date and timestep. These variables are useful for building time-dependent logic into your model.

### Available Variables

| Variable | Description | Example Value |
| --- | --- | --- |
| `sim.year` | Current calendar year | `2020` |
| `sim.month` | Current month (1-12) | `6` |
| `sim.day` | Current day of month (1-31) | `15` |
| `sim.day_of_year` | Day of year (1-366) | `167` |
| `sim.step` | Simulation timestep counter (from 0) | `42` |
| `sim.new_day` | 1 on the first step of a calendar day, else 0 | `1` |
| `sim.new_month` | 1 on the first step of a calendar month, else 0 | `0` |
| `sim.new_year` | 1 on the first step of a calendar year, else 0 | `0` |

### Examples

```
# Seasonal demand multiplier (higher in summer months)
demand= if(sim.month >= 11 || sim.month <= 2, 1.5, 1.0) * base_demand

# Environmental flow requirement varies by month
min_flow= if(sim.month >= 6 && sim.month <= 8, 50, 100)

# Irrigation season (Oct-Apr)
irrigation_active= if(sim.month >= 10 || sim.month <= 4, 1, 0)
```

### Calendar boundary flags

The `sim.new_*` flags are 1.0 on the first simulation step of each calendar
day, month, or year (and on the first step of the run), and 0.0 otherwise.
They exist because the obvious spelling is a trap at sub-daily timesteps:
`sim.month == 7 && sim.day == 1` is true for *every hourly step* of 1 July,
so anything it resets would reset all day long. The flag fires exactly once.

Their main use is as reset conditions for the
[temporal functions](dynamic-expressions.md#temporal-functions):

```ini
# Water-year accounting: total diversions since 1 July
used_wy = sum_since(node.town.diversion, sim.new_month && sim.month == 7)
```

There is deliberately no water-year variable in Kalix — the start month
varies from valley to valley, so the boundary is written where it's used, or
named once per model as a [user-defined function](fn.md) like
`new_wy() = sim.new_month && sim.month == 7`.

**Note:** Offset syntax is not supported for `sim.*` variables.
