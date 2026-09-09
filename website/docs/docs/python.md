---
title: Kalix in Python
---

# Kalix in Python

Kalix ships as a [Python package on PyPI](https://pypi.org/project/kalix/) with the full API documented there. It wraps the Rust engine, so you can run models and read their results straight from a script or notebook.

## Install

```bash
pip install kalix
```

## A quick example

Run a model and load its results as a pandas DataFrame:

```python
import kalix
import pandas as pd

# Run a model and write the results to CSV
kalix.simulate("my_model.ini", output_file="results.csv")

# Load the results
results = pd.read_csv("results.csv", parse_dates=[0], index_col=0)
print(results.head())
```

## Converting data files

`kalix.convert()` mirrors [`kalix convert`](cli.md#kalix-convert) on the commandline — the same engine conversion between date-indexed CSV and the Pixie pair, with formats chosen by file extension:

```python
kalix.convert("flows.csv", "flows.pxt")   # writes flows.pxt + flows.pxb
```

It returns the list of files written. To work with Pixie data in memory instead, `kalix.read_pixie()` and `kalix.write_pixie()` move whole pandas DataFrames in and out of the format.

For a full walkthrough, see [Running from Python](../tutorials/05-python.md).
