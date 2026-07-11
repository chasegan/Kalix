---
title: "[table.*]"
---

# [table.*]

Lookup tables let you define a relationship once — a rating curve, a storage
relationship, a seasonal pattern — and use it from any
[dynamic expression](index.md). Each table is its own section, and its name
becomes callable through the `table.*` namespace:

```ini
[table.rating]
values = 0,   0,
         0.5, 120,
         1.2, 450,
         3.0, 2200,

[node.gauge_52105]
type = gauge
reference_flow = table.rating(node.reach_5.dsflow)
```

Tables come in two forms:

- **1D tables** interpolate linearly between (x, y) breakpoints.
- **2D tables** first select a column by exact key match, then interpolate
  down that column.

Tables are global: define one once and reference it from as many expressions
as you like. The `[table.*]` section can appear anywhere in the model file.

## 1D tables

A 1D table is rows of `x, y` breakpoints. The x values must be strictly
ascending. Call it with one argument — any expression:

```ini
[table.rating]
values = 0,   0,
         0.5, 120,
         3.0, 2200,
```

```ini
flow = table.rating(node.reach_5.dsflow)
```

Between breakpoints the value is linearly interpolated. **Outside the table
range the nearest endpoint value is returned** — clamped, never extrapolated.
NaN in gives NaN out.

An optional text header row (exactly two non-numeric labels) is allowed for
readability:

```ini
[table.rating]
values = stage, flow,
         0,     0,
         0.5,   120,
         3.0,   2200,
```

## 2D tables

A 2D table is a grid. Declare its width with `n_cols` — the row-key column is
included in the count, so a table with a key column and 12 monthly columns is
`n_cols = 13`.

The first row is the *key row*: a non-numeric corner label followed by the
column keys. Every following row is a row key and its values. Both key sets
must be strictly ascending.

```ini
; Pump rate by month (columns) and storage volume (rows)
[table.pump_rating]
n_cols = 4
values = volume\month, 1,    2,    3,
         0,            0,    0,    0,
         500,          1.0,  1.2,  1.5,
         2000,         4.0,  4.8,  6.0,
```

Call a 2D table with two arguments — the **column key** first, then the
**row key**:

```ini
release = table.pump_rating(sim.month, node.dam.volume)
```

The lookup works in two steps:

1. **Column selection is an exact match.** The first argument must exactly
   equal one of the column keys. If it doesn't, the simulation stops with an
   error naming the table, the offending value, and the available keys.
2. **Row lookup interpolates** down the selected column, with the same
   clamped-linear rule as 1D tables.

!!! warning "Exact match and computed keys"
    Exact matching is intended for integer-like keys — months, seasons, zone
    numbers. [`sim.month`](simulation-context-vars.md) and similar values
    match reliably. Avoid computing a column key with arithmetic that can
    introduce floating-point error (e.g. `volume / 300`): a value of
    `6.9999999…` will not match a key of `7`, and the run will stop with an
    error showing the offending value.

### The corner label

The corner label is text, never a number — that is how the parser recognises
the key row. The convention is `y\x`, read like the diagonally split corner
of a printed two-way table: `y` names what runs *down* the first column, `x`
names what runs *across* the top row. It also mirrors the call signature —
`table.name(x, y)` takes the column key first, the row key second.

Better still, put your real axis names in the corner using the same
rows\columns pattern. Any non-numeric text is accepted, so the corner can
document the table: `volume\month` in the example above says the rows are
keyed by volume, the columns by month, and (reading it against the call
convention) that the table is called as `table.pump_rating(month, volume)`.

## Formatting rules

- **Line breaks carry no meaning.** A table may be written on one line or
  spread over any number of indented continuation lines — the same convention
  as node tables such as the [storage node's](storage.md)
  `dimensions`.
- A trailing comma is allowed.
- The number of values must fit the declared shape exactly: an even count for
  1D tables, and a multiple of `n_cols` for 2D tables (the corner label makes
  the key row the same width as every other row).
- All cells must be finite numbers — `nan` and `inf` are rejected.
- Table names follow the same rules as constants: lowercase letters, digits
  and underscores, starting with a letter, no dots, unique per model. Calls
  are case-insensitive: `table.Rating(x)` and `table.rating(x)` are the same.

## Composing with expressions

Any expression can be a table argument — including another table lookup — and
a table call is just a value in the surrounding expression:

```ini
[node.dam]
type = storage
demand = max(table.monthly_demand(sim.month, node.dam.volume), c.min_demand)
```

## Editing tables in KalixIDE

KalixIDE's **Table View** (right-click a table's `values` property, or
++cmd+t++ / ++ctrl+t++) opens the table as an editable grid — the same
spreadsheet-style editor used for storage dimensions and loss tables. For 2D
tables the key row appears as the first grid row, so column keys are as
editable as values. The IDE's linter also checks table sections as you type:
shape, key ordering and reference errors are flagged in the editor before the
model ever runs.

## When things go wrong

| Problem | When detected |
|---------|---------------|
| Malformed values (bad number, wrong count, non-ascending keys) | Model load (and live in the IDE) |
| Unknown table name in an expression | Model load |
| Wrong number of arguments (1D takes 1, 2D takes 2) | Model load |
| 2D column key with no exact match | During simulation, with table name, value, and available keys |
