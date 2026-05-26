# kalix (Python)

Python interface for [Kalix](https://chasegan.notion.site/Kalix-User-Guide-762687200b564e8e8c82b4f98879974f).

Current functionality:
- read and write Pixie files (`.pxt`, `.pxb`)

Planned:
- model loading & simulation
- programmatic model manipulation and building
- optimisation

## Install

```bash
pip install kalix
```

## Usage

```python
import kalix

# Read a .pxt/.pxb pair into a DataFrame
df = kalix.read_pixie("results.pxb")
print(df.head())

# Write a DataFrame back out
kalix.write_pixie("out.pxb", df)
```

The DataFrame index is a UTC `DatetimeIndex`; each column is one timeseries.

`read_pixie` accepts either extension (or no extension) and finds both files:

```python
kalix.read_pixie("results.pxb")  # same as
kalix.read_pixie("results.pxt")  # same as
kalix.read_pixie("results")
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
