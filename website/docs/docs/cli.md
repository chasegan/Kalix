---
title: Commandline
---

# Commandline

`kalix` runs models, calibrations, and other modelling tasks from the terminal.

## kalix

**Usage** — `kalix <COMMAND>`

**Commands**

`help`
:   Show help, or help for a given subcommand.

`simulate` · alias `sim`
:   Run a simulation — see [`kalix simulate`](#kalix-simulate).

`optimise` · alias `opt`
:   Run an optimisation — see [`kalix optimise`](#kalix-optimise).

`resave`
:   Load a model and write it back out, without simulating — see [`kalix resave`](#kalix-resave).

`new-session`
:   Open an interactive session; the engine communicates two-way over STDIO using a bespoke protocol (used internally by KalixIDE).

`get-api`
:   Print this commandline API specification as JSON.

**Options**

`-h`, `--help`
:   Print help.

`-v`, `--version`
:   Print the current kalix version.

**Examples**

```console
$ kalix help
$ kalix get-api
$ kalix new-session
```

## kalix simulate

Run a simulation. Alias: `sim`.

**Usage** — `kalix simulate <MODEL_FILE> [OPTIONS]`

**Arguments**

`<MODEL_FILE>`
:   Path to the model file.

**Options**

`-o`, `--output-file <OUTPUT_FILE>`
:   File to save the model results to. A `.csv` extension writes CSV; `.pxt` or `.pxb` writes the Pixie pair. CSV values are written at full double precision in the shortest form that reads back to the same number, so very small or very large values appear in exponent notation (`1.5e-7`, `1e20`) rather than as long runs of zeros.

`-p`, `--profile`
:   Report performance-profiling figures to the console.

`-m`, `--mass-balance <MASS_BALANCE>`
:   File to save the mass-balance report to.

`-v`, `--verify-mass-balance <VERIFY_MASS_BALANCE>`
:   Existing mass-balance report to verify against — checks that nothing has changed.

`-h`, `--help`
:   Print help.

**Example**

```console
$ kalix simulate my_model.ini -o results.csv
```

## kalix optimise

Run an optimisation. Alias: `opt`.

**Usage** — `kalix optimise <CONFIG_FILE> [MODEL_FILE] [OPTIONS]`

**Arguments**

`<CONFIG_FILE>`
:   Path to the optimisation configuration file.

`[MODEL_FILE]`
:   Path to the model file. Optional — the model file can also be set inside the optimisation config.

**Options**

`-s`, `--save-model <SAVE_MODEL>`
:   Path to save the optimised model file (`.ini`).

`-h`, `--help`
:   Print help.

**Example**

```console
$ kalix optimise config.ini model.ini -s calibrated_model.ini
```

## kalix resave

Load a model and write it back out, without simulating. Use it to reformat a model file, or to check that a model survives a save-and-reload unchanged.

**Usage** — `kalix resave <MODEL_FILE> [OUTPUT_FILE] [OPTIONS]`

**Arguments**

`<MODEL_FILE>`
:   Path to the model file to load.

`[OUTPUT_FILE]`
:   File to write the resaved model to. Required, unless you pass `--in-place`.

**Options**

`--in-place`
:   Overwrite the model file with the resaved model, instead of writing to an `[OUTPUT_FILE]`. The original is not kept, so reach for this on a model you can recover — one under version control, say.

`--save-method <SAVE_METHOD>`
:   How to write the output. `standard` (the default) rewrites only the sections that changed, leaving the rest of the file exactly as it was — comments, spacing and number formatting included. `canonical` re-renders every section from the model, normalising dates, numbers and table layout, so expect the file to change even when the model has not.

`-h`, `--help`
:   Print help.

**Examples**

```console
$ kalix resave my_model.ini tidy_model.ini --save-method canonical
$ kalix resave my_model.ini --in-place --save-method canonical
```
