# Trailhead Paths — Specification

## Overview

Kalix currently supports absolute and relative paths for referencing files and folders (e.g. input timeseries data). This works well when all models sit at the same directory depth relative to the data, but breaks down when models are scattered across varying levels of a directory tree.

**Trailhead paths** solve this by searching upward through parent directories until the target path is found.

## Syntax

A trailhead path is prefixed with the `^` character (the **trailhead marker**), followed by a `/`, then the target path:

```
^/data/climate/evaporation.csv
```

The `^` means: *starting from the current directory, try to resolve the target path. If it doesn't exist here, step up to the parent directory and try again, continuing upward until it is found.*

The `^` marker is chosen because it is not special to any shell in a path context and is vanishingly unlikely to be used as a literal folder name.

## Resolution algorithm

Given:
- A trailhead path string `^/<target>`
- A **context directory** (the directory containing the model file that declares the path)

Resolve as follows:

1. Let `current` = context directory.
2. Check whether `current/<target>` exists (file or directory).
3. If found, return `current/<target>` as the resolved absolute path.
4. If not found, set `current` to its parent and repeat from step 2.
5. If the filesystem root is reached without finding the target, return an error.

**Nearest match wins.** If the target path exists at multiple ancestor levels, the closest match (starting from the context directory) is used. This matches the convention used by Git (`.git`), Cargo (`Cargo.toml`), etc.

## Path kind enum

All Kalix paths should be represented as one of three kinds:

```
Absolute      — starts with `/` (Unix) or drive letter (Windows)
Relative      — any path not matching the other two kinds
Trailhead     — starts with `^/`
```

## Struct requirements

The path struct must hold:

- **raw**: the original string exactly as authored (for round-tripping on save/serialise)
- **resolved**: the absolute path after resolution

Construction is separated into two phases:

1. **Parse** — determine the path kind and extract the target (the portion after `^/`). This cannot fail for well-formed input.
2. **Resolve** — given a context directory, walk the filesystem and produce the absolute path. This can fail (target not found, I/O error, etc.). Resolution of absolute and relative paths is trivial but should pass through the same interface.

The struct should expose the path kind, and for trailhead paths, the target string.

## Cross-platform considerations

- Use `/` as the separator in the raw trailhead path string. Normalise to the platform separator during resolution.
- On Windows, directory traversal must stop at the drive root (e.g. `C:\`), not attempt to go above it.
- Resolved absolute paths should use the platform-native representation.
- The `^` character is legal in folder names on all platforms. In the unlikely event a user has a literal folder named `^`, they cannot use it as the first segment of a path in Kalix. This is an accepted trade-off.

## Files and folders

Trailhead paths may reference either a file or a folder. The resolution algorithm is the same in both cases — it checks whether the full target path exists at each ancestor level.

## Error cases

The implementation must handle and report clearly:

| Case | Error |
|---|---|
| Target not found at any ancestor level | "Trailhead path target `{target}` not found in any ancestor of `{context_dir}`" |
| Context directory does not exist | "Context directory `{context_dir}` does not exist" |
| Filesystem root reached | Same as target not found |
| Malformed trailhead path (e.g. `^` with no `/`) | "Invalid trailhead path syntax: `{raw}`" |

## Examples

Given this directory tree:

```
project/
  data/
    climate/
      evaporation.csv
  models/
    baseline/
      model.kalix
    scenarios/
      high_growth/
        model.kalix
      sensitivity/
        detailed/
          model.kalix
```

All three model files can reference the same data using:

```
^/data/climate/evaporation.csv
```

Resolution tries the full target path `data/climate/evaporation.csv` at each level:

| Model location | Resolution walk | Resolved path |
|---|---|---|
| `project/models/baseline/` | `./data/climate/evaporation.csv`? no. `../data/climate/evaporation.csv`? no. `../../data/climate/evaporation.csv`? yes. | `project/data/climate/evaporation.csv` |
| `project/models/scenarios/high_growth/` | tries 3 levels up until `project/` | `project/data/climate/evaporation.csv` |
| `project/models/scenarios/sensitivity/detailed/` | tries 4 levels up until `project/` | `project/data/climate/evaporation.csv` |

A trailhead path can also reference a single file without any folder structure:

```
^/my_file.csv
```

This searches for `my_file.csv` in the current directory, then the parent, then the grandparent, and so on.
