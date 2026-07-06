---
title: Commandline
---

# Commandline

`kalix` runs models, calibrations, and other modelling tasks from the terminal.

**Usage** — `kalix <COMMAND>`

**Commands**

`help`
:   Show help, or help for a given subcommand.

`simulate` · alias `sim`
:   Run a simulation — see [simulate](#simulate).

`optimise` · alias `opt`
:   Run an optimisation — see [optimise](#optimise).

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

## simulate

Run a simulation. Alias: `sim`.

**Usage** — `kalix simulate <MODEL_FILE> [OPTIONS]`

**Arguments**

`<MODEL_FILE>`
:   Path to the model file.

**Options**

`-o`, `--output-file <OUTPUT_FILE>`
:   File to save the model results to.

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

## optimise

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
