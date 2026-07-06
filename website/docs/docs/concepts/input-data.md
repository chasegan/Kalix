---
title: Input data
---

# Input data

Specify input data files in the `[inputs]` section of your model.

- Each input file should be specified on a new line. Find out how to delcare input data at [Declaring Input Data](input-data.md).

- Kalix supports csv files, and alternative formats described here [Supported Data Formats](input-data.md).

- Each file may contain 1 or multiple timeseries and these are accessible from within the model using via the timeseries name (i.e. column name if the input is a CSV file), or the number (counting from 1) of the timeseries. Read more about how to reference timeseries data at [Referencing Input Data](dynamic-expressions/referencing-input-data.md) .

[Declaring Input Data](input-data.md)[Supported Data Formats](input-data.md)


## Declaring Input Data

Kalix models declare input data files by listing them in the `[inputs]` section of your model file:

```toml
[inputs]
c:/data/climate_data.csv
c:/data/streamflow_data.csv
c:/data/misc.csv
```

Kalix models declare input data using file paths. Each file path can be written in one of three styles:

- **absolute paths**, e.g. `c:/data/climate.csv`,

- **relative paths**, e.g. `../../data/climate.csv`, or

- **trailhead paths**, e.g. `^/data/climate.csv` (these are unique to Kalix and you can find out more about them in the section below)

Once a file is listed here, you can reference its columns from dynamic expressions using the `data.*` namespace. See [Referencing Input Data](dynamic-expressions/referencing-input-data.md) for column lookup, name sanitisation, and temporal offset syntax.

# Trailhead Paths

Relative paths work well when all your models sit at the same depth in your project folder, but they break when models are organised at different levels. In order to reference the same “evaporation.csv” dataset, each model would need a different relative path:

```
project/
  data/
    climate/
      evaporation.csv
  models/
    baseline/
      model.kalix          → ../../data/climate/evaporation.csv ✓
    scenarios/
      high_growth/
        model.kalix        → ../../../data/climate/evaporation.csv ✓
      sensitivity/
        detailed/
          model.kalix      → ../../../../data/climate/evaporation.csv ✓
```

Every model above needs a different number of `../` segments, and if you move a model to a new location you have to update all its data paths. Absolute paths can avoid this but tie your project to a specific machine.

The solution… Kalix’s **trailhead paths** tell Kalix to search upward through parent folders until it finds what you’re looking for. Use trailhead paths with the prefix `^/`:

```toml
^/data/climate/evaporation.csv
```

This means: *starting from the folder this model file is in, look for* *`data/climate/evaporation.csv`**. If it doesn’t exist here, check the parent folder, then the grandparent, and so on until the file is found.*

All three models in the example above can now use the exact same path, regardless of how deeply nested they are:

```
^/data/climate/evaporation.csv
```

## Syntax

```
^/<target path>
```

- `^` — the trailhead marker, signals that this is a trailhead path

- Everything after `^/` is the target path that Kalix searches for

Use forward slashes `/` regardless of your operating system.

## How resolution works

1. Kalix looks in the current folder (where your model file lives) for the target path.

2. If found, the path is resolved.

3. If not found, Kalix moves up to the parent folder and checks again.

4. This continues until the target is found or the filesystem root is reached, at which point Kalix reports an error.

If the target exists at more than one level, the **nearest one wins** — the first match walking upward from your model file is used.

## Examples

| Trailhead path | Resolves to |
| --- | --- |
| `^/data/climate/evaporation.csv` | The nearest ancestor directory (starting from the current directory) from where `data/climate/evaporation.csv` exists. |
| `^/my_file.csv` | The nearest ancestor directory (starting from the current directory) where `my_file.csv` exists. |
| `^/../my_file.csv` | The nearest ancestor directory (starting from the current directory) where `../my_file.csv` exists. Note that this version will always be looking up one level due to the `../`, and therefore will skip any copy of `my_file.csv` in the current directory. |

## When to use trailhead paths

**Use them when** your project has a shared data folder and models at varying depths. They’re especially useful for scenario-based workflows where you duplicate and reorganise model files frequently.

**Stick with relative paths when** your project structure is flat or all models are at the same depth. There’s no advantage to trailhead paths in that case.

**Stick with absolute paths when** your data lives outside the project tree entirely (e.g. on a network drive that is always mounted at the same location).

## Tips

- The target can be a file or a folder.

- Trailhead paths are supported everywhere Kalix accepts a file or folder path.

- If Kalix can’t find the target, you’ll get a clear error message telling you what it was looking for and where it searched from.

- Avoid having the same target path exist at multiple levels of your project tree. It’s not an error, but the nearest match may not be the one you intended.


## Supported Data Formats

Supported data formats are listed below.

| Extension | Implied format | Details |
| --- | --- | --- |
| .csv | Comma separated values. | see below… |
| .pxt + .pxb | Pixie fast lightweight binary format. | see below… |

# CSV files

Rules for CSV files are as follows:

- 1 header row.
  - Header row values are comma-separated and will be interpreted as the timeseries names.

- Timestamps should be in the first column. The column name for this column doesn’t matter. Timestamps are allowed to take a range of valid [ISO 8601 formats](https://en.wikipedia.org/wiki/ISO_8601). Some good choices are:
  - **yyyy-MM-dd** e.g. `2022-03-21` for daily data.
  - **yyyy-MM-dd'T'HH****:mm:****ss** e.g. `2019-06-28T22:00:00` for sub-daily data. Note that the Python formatter for this is “%Y-%m-%dT%H:%M:%S”.

- Timesteps in the file must be regular (no missing timestamps), and in temporal order.

- Blank values (or whitespace) are taken to indicate missing data.

# Pixie binary files (.pxt + .pxb)

The Pixie binary format stores one or more timeseries efficiently using a compression algorithm inspired by [Gorilla](https://www.vldb.org/pvldb/vol8/p1816-teller.pdf). It always uses **two paired files** that share the same base name. Both files are required.

- `.pxt` — index file. A plain-text CSV with one row per series, listing the series name, byte offset into the `.pxb` file, start/end times, timestep, and length. This file is human-readable and can be useful for inspecting the contents of the dataset.

- `.pxb` — binary file. Contains the actual compressed timeseries data. Each series is written as a single block. Binary offsets recorded in the `.pxt` file allow individual series to be read without decompressing the whole file.

**Advantages**

- **Smaller files.** Pixie files are typically 5–10× smaller than the CSV equivalent. The savings are bigger when timeseries are longer and more regular.

| Commandline output flag | Files written | Total size |
| --- | --- | --- |
| -o results.csv | results.csv | 263 MB (72 MB if zipped using aggressive zip compression) |
| -o results.pxb | results.pxt + results.pxb | 39 MB |
| -o results.pxt | results.pxt + results.pxb | 39 MB |

- **Random access.** Individual series can be loaded without reading the whole file. This makes access way faster than CSV.

- **Fast encoding and decoding algorithm**. Encoding or decoding happens in one sweep using operations that are very cheap for the cpu. You wont notice it happen.

- **Lossless.** Round-tripping a series through `.pxt`/`.pxb` returns the original values exactly, including special values like NaN.

**Disadvantages**

- You cannot see the data in a text editor. Special tooling is needed to read the data. The index file partly mitigates this downside.

**Other details**

Both 64-bit double precision (recommended) and 32-bit float precision are supported.

# How to think about timestamps and timeseries data

This is trickier than you might think!

Consider the following data:

| Timestamp | Dam\_release\_ML | Dam\_level\_m |
| --- | --- | --- |
| 2022-01-21 | 240 | 125 |
| 2022-01-22 | 320 | 124 |

The value of “Dam\_release\_ML” is 240 ML on 2022-01-21, meaning that the cumulative release during that day is 240 ML. And the value of “Dam\_level\_m” on that day is 125m meaning that the storage level at the end of the day (convention reporting) is 125m.

But strictly speaking the timestamp 2022-01-21 refers to the instant 2022-01-21T00:00:00 which is at the start of the day! Thus:

- Cumulative values are reported at the start of the interval.

- Instantaneous values should be interpreted as being at the end of the interval, but are reported using timestamps at the start of the interval.
