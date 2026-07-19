# Lookup Tables in Kalix

## Overview

Named lookup tables let you define a relationship once — a rating curve, a
storage relationship, a seasonal pattern — and use it anywhere a dynamic
expression is accepted. Tables are defined in `[table.<name>]` sections and
called from expressions using the `table.*` namespace:

```ini
[table.rating_52105]
values = 0,   0,
         0.5, 120,
         1.2, 450,
         3.0, 2200,

[node.gauge_52105]
type = gauge
reference_flow = table.rating_52105(node.reach_5.dsflow)
```

Tables come in two forms:

- **1D tables** interpolate linearly between (x, y) breakpoints.
- **2D tables** first select a column by exact key match, then interpolate
  down that column.

## Defining a 1D Table

A 1D table is rows of `x, y` pairs. The x values must be strictly ascending.

```ini
[table.rating]
values = 0,   0,
         0.5, 120,
         3.0, 2200,
```

An optional text header row (exactly two non-numeric labels) is allowed for
readability:

```ini
[table.rating]
values = stage, flow,
         0,     0,
         0.5,   120,
         3.0,   2200,
```

Call a 1D table with one argument — any expression:

```ini
flow = table.rating(node.reach_5.dsflow)
```

**Interpolation is clamped, not extrapolated.** Between breakpoints the value
is linearly interpolated; outside the table range the nearest endpoint value
is returned. NaN input produces NaN output.

## Defining a 2D Table

A 2D table is a grid. Declare its width with `n_cols` (the row-key column is
included in the count). The first row holds a non-numeric corner label
followed by the column keys; each following row is a row key and its values.
Both key sets must be strictly ascending.

The corner label is text, never a number — that is how the parser recognises
the key row. The convention is `y\x`, read like the diagonally split corner
of a printed two-way table: `y` names what runs *down* the first column, `x`
names what runs *across* the top row. It also mirrors the call signature:
`table.name(x, y)` takes the column key first, the row key second.

```ini
; Pump rate by month (columns) and storage volume (rows)
[table.pump_rating]
n_cols = 4
values = y\x,  1,    2,    3,
         0,    0,    0,    0,
         500,  1.0,  1.2,  1.5,
         2000, 4.0,  4.8,  6.0,
```

Better still, put your real axis names in the corner using the same
rows\columns pattern — any non-numeric text is accepted, so the corner can
document the table:

```ini
[table.pump_rating]
n_cols = 4
values = volume\month, 1,    2,    3,
         0,            0,    0,    0,
         500,          1.0,  1.2,  1.5,
         2000,         4.0,  4.8,  6.0,
```

Call a 2D table with two arguments — first the **column key**, then the
**row key**:

```ini
release = table.pump_rating(sim.month, node.dam.volume)
```

The lookup:

1. **Column selection is an exact match.** The first argument must exactly
   equal one of the column keys. If it doesn't, the simulation stops with an
   error naming the table, the offending value, and the available keys.
2. **Row lookup interpolates,** with the same clamped-linear rule as 1D
   tables.

**Exact match and computed keys.** Exact matching is intended for
integer-like keys — months, seasons, zone numbers. `sim.month` and similar
values match reliably. Avoid computing a column key with arithmetic that can
introduce floating-point error (e.g. `volume / 300`): a value of
`6.9999999...` will not match a key of `7`, and the run will stop with an
error showing the offending value.

## Formatting Rules

- **Line breaks carry no meaning.** A table may be written on a single line
  or spread over any number of indented continuation lines — the same
  convention as node tables (e.g. the storage node's `dimensions`).
- A trailing comma at the end of the data is allowed.
- The number of values must fit the declared shape exactly: an even count for
  1D tables, and a multiple of `n_cols` for 2D tables (the corner label
  makes the key row the same width as every other row).
- All cells must be finite numbers — `nan` and `inf` are rejected.

## Naming Rules

Table names follow the same rules as constants: lowercase letters, digits,
and underscores, starting with a letter. Dots are not allowed in table names.
Each table name must be unique.

Like other references, table calls are case-insensitive in expressions:
`table.Rating(x)` and `table.rating(x)` are equivalent.

## Where Tables Can Be Used

Anywhere a dynamic expression is accepted, and any expression can be an
argument — including other table lookups:

```ini
[node.dam]
type = storage
demand = max(table.monthly_demand(sim.month, node.dam.volume), const.min_demand)
```

Tables are global: define once, reference from as many expressions as you
like. The `[table.*]` section may appear anywhere in the model file.

## Errors

| Problem | When detected |
|---------|---------------|
| Malformed data (bad number, wrong count, non-ascending keys) | Model load |
| Unknown table name in an expression | Model load |
| Wrong number of arguments (1D takes 1, 2D takes 2) | Model load |
| 2D column key with no exact match | During simulation, with table name, value, and available keys |
