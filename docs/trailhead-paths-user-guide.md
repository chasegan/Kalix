# Trailhead Paths

## The problem

Kalix models reference input data files using paths. Relative paths work well when all your models sit at the same depth in your project folder, but they break when models are organised at different levels:

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

Every model needs a different number of `../` segments, and if you move a model to a new location you have to update all its paths. Absolute paths avoid this but tie your project to a specific machine.

## The solution

A **trailhead path** tells Kalix to search upward through parent folders until it finds what you're looking for. Prefix the path with `^/`:

```
^/data/climate/evaporation.csv
```

This means: *starting from the folder this model file is in, look for `data/climate/evaporation.csv`. If it doesn't exist here, check the parent folder, then the grandparent, and so on until it's found.*

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

| Trailhead path | What Kalix searches for | Resolves to |
|---|---|---|
| `^/data/climate/evaporation.csv` | `data/climate/evaporation.csv` | The nearest ancestor directory containing `data/climate/evaporation.csv` |
| `^/my_file.csv` | `my_file.csv` | The nearest ancestor directory containing `my_file.csv` |

## When to use trailhead paths

**Use them when** your project has a shared data folder and models at varying depths. They're especially useful for scenario-based workflows where you duplicate and reorganise model files frequently.

**Stick with relative paths when** your project structure is flat or all models are at the same depth. There's no advantage to trailhead paths in that case.

**Stick with absolute paths when** your data lives outside the project tree entirely (e.g. on a network drive that is always mounted at the same location).

## Tips

- The target can be a file or a folder.
- Trailhead paths are supported everywhere Kalix accepts a file or folder path.
- If Kalix can't find the target, you'll get a clear error message telling you what it was looking for and where it searched from.
- Avoid having the same target path exist at multiple levels of your project tree. It's not an error, but the nearest match may not be the one you intended.
