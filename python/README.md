# kalix (Python)

Python interface for [Kalix](https://kalix.io), wrapping the Rust implementation
via PyO3.

**v0.1** ships read/write of the Kalix Gorilla-compressed timeseries format
(`.kaz` / `.kai` paired files) as pandas DataFrames.

Planned for later releases: model loading & simulation, programmatic model
building, optimisation.

## Install

```bash
pip install kalix
```

## Usage

```python
import kalix

# Read a .kaz/.kai pair into a DataFrame
df = kalix.read_kaz("results.kaz")
print(df.head())

# Write a DataFrame back out
kalix.write_kaz("out.kaz", df)
```

The DataFrame index is a UTC `DatetimeIndex`; each column is one timeseries.

`read_kaz` accepts either extension (or no extension) and finds both files:

```python
kalix.read_kaz("results.kaz")  # same as
kalix.read_kaz("results.kai")  # same as
kalix.read_kaz("results")
```

## Building from source

Requires Rust (1.70+) and [maturin](https://www.maturin.rs/).

```bash
# Editable install for development
cd python
maturin develop --release

# Build a wheel
maturin build --release
```

## Tests

```bash
maturin develop
pytest python/tests
```
