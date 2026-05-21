# Simulation Context Variables

The `sim.*` namespace provides access to the current simulation date and timestep. These variables are useful for building time-dependent logic into your model.

## Available Variables

| Variable | Description | Example Value |
|----------|-------------|---------------|
| `sim.year` | Current calendar year | `2020` |
| `sim.month` | Current month (1-12) | `6` |
| `sim.day` | Current day of month (1-31) | `15` |
| `sim.day_of_year` | Day of year (1-366) | `167` |
| `sim.step` | Simulation timestep counter (from 0) | `42` |

## Examples

```ini
; Seasonal demand multiplier (higher in summer months)
demand = if(sim.month >= 11 || sim.month <= 2, 1.5, 1.0) * base_demand

; Environmental flow requirement varies by month
min_flow = if(sim.month >= 6 && sim.month <= 8, 50, 100)

; Irrigation season (Oct-Apr in Southern Hemisphere)
irrigation_active = if(sim.month >= 10 || sim.month <= 4, 1, 0)
```

**Note:** Offset syntax is not supported for `sim.*` variables.
