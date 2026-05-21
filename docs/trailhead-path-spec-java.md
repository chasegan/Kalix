# Trailhead Paths — Java Specification (KalixIDE)

## Context

The Rust runtime implements trailhead path resolution for model execution. KalixIDE (Java) needs the same resolution logic for development-time features: browsing available input timeseries, validating model paths, and reporting linting errors when paths don't resolve.

Both implementations must resolve identically given the same inputs. The Rust specification is the authoritative reference for syntax, resolution algorithm, and error semantics. This document covers only Java-specific design decisions.

Refer to `trailhead-path-spec.md` for:
- Syntax definition (`^/<target>`)
- Resolution algorithm (walk-up, nearest match wins)
- Cross-platform considerations
- Error cases

## Class design

### `KalixPath`

Immutable value class. Use a sealed interface or enum for the path kind.

```java
public enum PathKind {
    ABSOLUTE,
    RELATIVE,
    TRAILHEAD
}
```

The class must expose:

- `String raw()` — original string, preserved exactly for round-tripping
- `PathKind kind()`
- `String target()` — the target path (the portion after `^/`); only meaningful when kind is `TRAILHEAD`, null otherwise
- `Path resolve(Path contextDir)` — returns the resolved absolute path
- `static KalixPath parse(String raw)` — factory method, determines kind and extracts target; never touches the filesystem; throws `IllegalArgumentException` on malformed trailhead syntax (e.g. `^` with no `/`, or `^/` with no target)

### Parse vs resolve separation

`parse()` is pure — no I/O, cannot fail for well-formed input. This supports IDE features that need to inspect paths without resolving them (syntax highlighting, kind detection, extracting the target for display).

`resolve()` performs filesystem traversal and can fail. It returns `Path` (platform-native absolute path). It throws checked exceptions (see below).

## Resolution

Use `java.nio.file.Path` throughout. The algorithm:

1. Let `current` = `contextDir.toAbsolutePath().normalize()`
2. Let `candidate` = `current.resolve(target).normalize()`
3. Check if `candidate` exists (`Files.exists()`).
4. If found, return `candidate`.
5. Set `current` = `current.getParent()`. If null, throw (root reached).
6. Repeat from step 2.

For absolute and relative paths, `resolve()` is trivial:
- Absolute: return `Paths.get(raw).normalize()`
- Relative: return `contextDir.resolve(raw).normalize()`

## Separator handling

Raw trailhead paths always use `/` as the separator (as authored in model files). During resolution, `Path.resolve()` handles platform conversion automatically. When serialising back, always write the `raw` string — never the resolved path.

## Exceptions

Define a single checked exception:

```java
public class KalixPathResolutionException extends Exception {
    private final String raw;
    private final Path contextDir;
    // constructor, getters
}
```

Throw it when:
- Target not found at any ancestor level
- Context directory does not exist
- Filesystem root reached without finding target

For parse-time errors (malformed syntax), throw `IllegalArgumentException`.

## IDE integration notes

### Linting

When validating a model file, parse each path and attempt resolution using the model file's parent directory as context. Report:

- `WARNING` if the trailhead path resolves but the final target is an empty directory (for folder targets) or has an unexpected format (for file targets)
- `ERROR` if the target cannot be found at any ancestor level (the trailhead path is broken)
- `ERROR` on malformed trailhead syntax

### Timeseries browsing

To support autocomplete for partially typed trailhead paths, resolve as much of the target as possible. For example, given `^/data/climate/` typed so far, walk up to find the nearest ancestor containing `data/climate/`, then list its contents to offer file suggestions.

### Caching

Resolution involves repeated filesystem existence checks walking up the tree. For IDE responsiveness:

- Cache successful resolutions keyed by (context directory, raw path) pair. Invalidate on file-system change events (`WatchService`) or on manual refresh.
- Where multiple model files share the same context directory, their caches can be shared.

## Testing

At a minimum, test:

- Parse correctly classifies absolute, relative, and trailhead paths
- Resolution walks up the correct number of levels
- Nearest match wins when target exists at multiple ancestor levels
- Resolution of a single file target (e.g. `^/my_file.csv`) works correctly
- Resolution of a nested target (e.g. `^/data/climate/evap.csv`) works correctly
- Resolution fails clearly when target is not found
- Round-trip: `parse(path).raw()` returns the original string exactly
- Cross-platform separators: raw strings with `/` resolve correctly on Windows
- Malformed trailhead syntax is rejected at parse time
- Context directory that does not exist is rejected at resolve time
