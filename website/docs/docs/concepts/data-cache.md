---
title: "Data cache"
---

# Data cache

The data cache is the central hub for timeseries data in Kalix. It houses input data, function results, and model results all in one place, and provides equal access to data from various parts of the model.

### How does data get into the data cache?

- ***You can load data*** from csv files (and other types of files) by defining them as input data in your model. Input data can subsequently be used throughout your model.

- ***You can use functions*** inside your model to calculate values dynamically. The results of these live in the data cache.

- ***Any model results*** generated within the simulation are also available via the data cache. Model results are only tracked if they are being used in the model, or if they are identified as output to be written-out after the model run.

### How can I use the data in my model?

- Every series in the model has a unique name, used to refer to the data within the model.
  - For input data (i.e. data loaded from a file) each series has three names: one based on the series name within the data file (e.g. column header text), and one based on the series number within the data file (e.g. column number). Here are examples:
    - `data.input_rainfall_csv.by_name.35001_rain`
    - `data.input_rainfall_csv.by_index.1`
  - For function results, the name is based on the function name. Example:
    - `func.reach_01_inflow`
  - For model results, the name is based on the node name. Example:
    - `node.0031_hoover_dam.dsflow`

- Units are implicit: volumes are in [ML], flows are in [ML/d], levels are in [m], rainfall evaporation and seepage rates are [mm/d].

- Alternative units are not supported.

- Names are not case sensitive.
