# kalix (Python)

Python interface for [Kalix](https://chasegan.notion.site/Kalix-User-Guide-762687200b564e8e8c82b4f98879974f).

Current functionality:
- run simulations from INI model files (in-process, no separate CLI binary)
- read and write Pixie files (`.pxt`, `.pxb`)

Planned:
- programmatic model manipulation and building (`Model` class)
- optimisation

## Install

```bash
pip install kalix
```

## Usage

### Run a simulation

```python
import kalix

kalix.simulate("model.ini", output_file="results.pxb")
df = kalix.read_pixie("results.pxb")
print(df.head())
```

The Python equivalent of `kalix sim model.ini -o results.pxb`, but in-process —
no separate CLI binary required. Output format is inferred from the extension
(`.pxb` for the Pixie pair, `.csv` for CSV).

Both outputs are keyword-only, and at least one is required:

```python
# Outputs + mass-balance report
kalix.simulate("model.ini", output_file="results.pxb", mass_balance="mb.txt")

# Mass-balance only
kalix.simulate("model.ini", mass_balance="mb.txt")
```

### Read / write Pixie files

```python
df = kalix.read_pixie("results.pxb")
print(df.head())

kalix.write_pixie("out.pxb", df)
```

The DataFrame index is a UTC `DatetimeIndex`; each column is one timeseries.

`read_pixie` accepts either extension (or no extension) and finds both files:

```python
kalix.read_pixie("results.pxb")  # same as
kalix.read_pixie("results.pxt")  # same as
kalix.read_pixie("results")
```

`write_pixie` prefers a tz-aware `DatetimeIndex`, but will try to coerce other
inputs (emitting a `UserWarning` when it does):

- a naive `DatetimeIndex` is localised to UTC;
- a default `RangeIndex(0, n, 1)` triggers promotion of the zeroth column to
  the index (and drops it from the body);
- any other non-`DatetimeIndex` is passed through `pd.to_datetime(..., utc=True)`.

Integer and float dtypes are never auto-interpreted as datetimes (this would
silently misread values as epoch nanoseconds). Set a `DatetimeIndex` explicitly
in those cases.

## Building from source

Requires Rust (1.70+) and [maturin](https://www.maturin.rs/).

```bash
# Editable install for development
cd python
maturin develop --release
# uv run maturin develop --release --uv

# Build a wheel
maturin build --release
# uv run maturin build --release -- uv
```

## Tests

### Running tests directly

```bash
maturin develop
pytest python/tests
```

Alternatively with uv:

```bash
uv run maturin develop --uv
uv run pytest
```

### Running tests with tox

Tox is configured to test against multiple Python versions (3.9-3.14):

```bash
# Run tests in development environment (fast, uses current Python)
uv run tox -e dev

# Run tests against all configured Python versions
uv run tox

# Run tests for specific Python version
uv run tox -e py312

# List all available test environments
uv run tox list

# Run tests in parallel
uv run tox -p auto
```
