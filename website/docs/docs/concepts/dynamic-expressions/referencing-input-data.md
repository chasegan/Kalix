---
title: "Referencing Input Data"
---

# Referencing Input Data

The page [Declaring Input Data](../input-data.md) explains how to import data in your Kalix model. Here’s a reminder:

```
[inputs]
./data/climate.csv
./data/streamflow.csv
```

Each CSV file should have a date/timestamp column as the first column, followed by one or more data columns:

```
Date,Rainfall,Evaporation
2020-01-01,12.5,4.2
2020-01-02,0.0,5.1
```

### Referencing Data in Expressions

Once imported, you can reference any column using the `data.*` namespace in dynamic expressions. Kalix provides two ways to reference columns:

#### By Column Name

```
data.<filename>.by_name.<column_name>
```

#### By Column Index

```
data.<filename>.by_index.<column_number>
```

Column indices start at 1 (the first data column after the date column).

**Example:** For `climate.csv` with columns `Date, Rainfall, Evaporation`:
- `data.climate_csv.by_name.rainfall` - references the Rainfall column
- `data.climate_csv.by_index.1` - also references the Rainfall column
- `data.climate_csv.by_index.2` - references the Evaporation column

### Name Sanitisation

Kalix sanitises filenames and column names to ensure valid references:

1. **Lowercase conversion** - all names become lowercase

2. **Special character replacement** - characters other than `a-z`, `0-9`, and `_` are replaced with underscores

| Original | Sanitised |
| --- | --- |
| `Climate-Data.csv` | `climate_data_csv` |
| `Flow (ML/d)` | `flow__ml_d_` |
| `Station_001` | `station_001` |

This means references are **case-insensitive** - `data.climate_csv.by_name.Rainfall` and `data.climate_csv.by_name.rainfall` both work.

### Temporal Offset Syntax

You can access values from previous or future timesteps using offset syntax:

```
variable[offset, default_value]
```

- **Negative offset** = past values (e.g., `1` = yesterday)

- **Zero offset** = current timestep

- **Positive offset** = future values (e.g., `+1` = tomorrow)

The `default_value` is returned when the offset goes outside the available data range.

**Examples:**

```
# Yesterday's rainfall (default to 0 if at start of data)
data.climate_csv.by_name.rainfall[-1, 0.0]

# Tomorrow's forecast demand (forward lookup)
data.forecast_csv.by_name.demand[1, 20.0]

# Calculate change from yesterday
data.climate_csv.by_name.rainfall - data.climate_csv.by_name.rainfall[-1, 0.0]
```

**Note:** Forward lookups (positive offsets) only work for `data.*` references where data is pre-loaded. They cannot be used with `node.*` references since future model outputs haven’t been computed yet.

### Using Data in Node Parameters

Reference data in any node parameter that accepts dynamic expressions:

```
[node.little_creek]
loc = 0,0
type = inflow
inflow = 0.23 * data.climate_csv.by_name.big_creek_inflow
```
