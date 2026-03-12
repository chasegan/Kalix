package com.kalix.ide.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Immutable value class representing a Kalix file path that may be absolute, relative,
 * or a trailhead path ({@code ^/target}). Trailhead paths search upward through ancestor
 * directories until the target is found.
 *
 * <p>Usage follows a parse-then-resolve pattern:
 * <pre>
 *   KalixPath kp = KalixPath.parse("^/data/climate/evap.csv");
 *   Path resolved = kp.resolve(modelFileDirectory);
 * </pre>
 */
public final class KalixPath {

    public enum PathKind {
        ABSOLUTE,
        RELATIVE,
        TRAILHEAD
    }

    private final String raw;
    private final PathKind kind;
    private final String target; // portion after ^/ for trailhead paths, null otherwise

    private KalixPath(String raw, PathKind kind, String target) {
        this.raw = raw;
        this.kind = kind;
        this.target = target;
    }

    /**
     * Parse a raw path string and determine its kind. Does not touch the filesystem.
     *
     * @param raw the path string as authored in the model file
     * @return a KalixPath instance
     * @throws IllegalArgumentException on malformed trailhead syntax (e.g. bare {@code ^} or {@code ^/} with no target)
     */
    public static KalixPath parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Path string must not be null or empty");
        }

        if (raw.startsWith("^/") || raw.startsWith("^\\")) {
            if (raw.length() <= 2) {
                throw new IllegalArgumentException("Invalid trailhead path syntax: '" + raw + "'");
            }
            String target = raw.substring(2);
            return new KalixPath(raw, PathKind.TRAILHEAD, target);
        }

        if (raw.equals("^")) {
            throw new IllegalArgumentException("Invalid trailhead path syntax: '" + raw + "'");
        }

        if (Paths.get(raw).isAbsolute()) {
            return new KalixPath(raw, PathKind.ABSOLUTE, null);
        }

        return new KalixPath(raw, PathKind.RELATIVE, null);
    }

    /** Returns the original path string exactly as authored. */
    public String raw() { return raw; }

    /** Returns the kind of path. */
    public PathKind kind() { return kind; }

    /** Returns the target portion (after {@code ^/}) for trailhead paths, null otherwise. */
    public String target() { return target; }

    /**
     * Resolve this path against a context directory (typically the model file's parent directory).
     *
     * <ul>
     *   <li><b>Absolute</b> paths are returned as-is (normalized).</li>
     *   <li><b>Relative</b> paths are resolved against the context directory.</li>
     *   <li><b>Trailhead</b> paths walk upward from the context directory until the target is found.</li>
     * </ul>
     *
     * @param contextDir the directory to resolve against
     * @return the resolved absolute path
     * @throws KalixPathResolutionException if the path cannot be resolved
     */
    public Path resolve(Path contextDir) throws KalixPathResolutionException {
        if (contextDir == null) {
            throw new KalixPathResolutionException(raw, null,
                    "Context directory must not be null");
        }

        switch (kind) {
            case ABSOLUTE:
                return Paths.get(raw).normalize();

            case RELATIVE:
                return contextDir.toAbsolutePath().normalize().resolve(raw).normalize();

            case TRAILHEAD:
                return resolveTrailhead(contextDir);

            default:
                throw new IllegalStateException("Unknown path kind: " + kind);
        }
    }

    private Path resolveTrailhead(Path contextDir) throws KalixPathResolutionException {
        Path current = contextDir.toAbsolutePath().normalize();

        while (current != null) {
            Path candidate = current.resolve(target).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = current.getParent();
            if (parent != null && parent.equals(current)) {
                // Reached filesystem root
                break;
            }
            current = parent;
        }

        throw new KalixPathResolutionException(raw, contextDir,
                "Trailhead path target '" + target + "' not found in any ancestor of '" + contextDir + "'");
    }

    @Override
    public String toString() {
        return raw;
    }
}
