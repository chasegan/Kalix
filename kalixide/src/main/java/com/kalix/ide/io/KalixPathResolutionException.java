package com.kalix.ide.io;

import java.nio.file.Path;

/**
 * Checked exception thrown when a {@link KalixPath} cannot be resolved against
 * a context directory. This covers trailhead targets not found in any ancestor,
 * missing context directories, and similar resolution failures.
 */
public class KalixPathResolutionException extends Exception {

    private final String raw;
    private final Path contextDir;

    public KalixPathResolutionException(String raw, Path contextDir, String message) {
        super(message);
        this.raw = raw;
        this.contextDir = contextDir;
    }

    /** The original path string that failed to resolve. */
    public String getRaw() { return raw; }

    /** The context directory that was used during resolution, may be null. */
    public Path getContextDir() { return contextDir; }
}
