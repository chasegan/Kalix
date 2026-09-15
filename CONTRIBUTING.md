# Contributing to Kalix 

Thank you for your interest in contributing to Kalix - we welcome all contribution, whether through discussions on the science, feature requests, bug reports, or PRs. If you haven't already, read [GOVERNANCE.md](GOVERNANCE.md).

<!--
## LLM policy 

...

-->

# Feature requests and bug reports

[Open an issue](https://github.com/chasegan/Kalix/issues/new/choose).

# Contributing code

Please check in with us before starting work - it is important to ensure we have consensus on the solution to the problem. New features should [open an issue](https://github.com/chasegan/Kalix/issues/new/choose) first to discuss approach. If you are starting work on an existing issue, take a look at the discussion and any linked pull requests to avoid duplicating work.

Ensure you have read the [Manifesto](https://kalix.org/code/design/manifesto/) describing the philosophy of Kalix, and the [Architecture Decision Records (ADRs)](https://kalix.org/code/design/adrs/). 

This repository is fundamentally composed of three parts: the rust engine ([`src/`](src/)), the python bindings ([`python/`](python/)) and the shipped IDE ([`kalixide/`](kalixide/)). 

## Kalix engine

Kalix is written in [Rust](https://rust-lang.org/). It is the simulation engine and command line tool that comprises the core of this project.

## Python bindings

The `kalix` python package ([README](python/README.md)) uses [PyO3](https://github.com/pyo3/pyo3) to generate python bindings. It contains a crate that binds the engine directly, and the python layer dispatches, and presents errors. We prefer that logic be kept in the crate wherever possible. These bindings enable iteration, optimisation and scenario-based modelling workflows.

## KalixIDE

KalixIDE is written in Java 23. It is a lightweight, cross-platform model editing tool.
