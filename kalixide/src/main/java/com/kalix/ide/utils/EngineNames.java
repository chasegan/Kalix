package com.kalix.ide.utils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Java mirror of the name rules owned by the Rust engine: name sanitisation,
 * and the node-name rule.
 *
 * <p>The point of truth is {@code fn sanitize_name} in
 * {@code src/misc/misc_functions.rs}: <em>lowercase first</em>, then map every
 * character outside {@code [a-z0-9_]} to {@code '_'}. The engine applies this
 * rule to input-file source names (derived from the filename), CSV column
 * names, and user-provided aliases when it builds {@code data.*} reference
 * paths (see {@code src/timeseries_input.rs}). Any IDE code that produces or
 * matches engine-facing {@code data.*} references must go through this class
 * so the two sides cannot drift apart.
 *
 * <p>Example: {@code MyData.csv} &rarr; {@code mydata_csv}.
 */
public final class EngineNames {

    private EngineNames() {
    }

    /**
     * Sanitizes a name exactly as the engine's {@code sanitize_name} does:
     * lowercase first, then replace every character outside {@code [a-z0-9_]}
     * with {@code '_'}.
     *
     * @param name the raw name (alias, column name, filename)
     * @return the engine-form name
     */
    public static String sanitize(String name) {
        String lowered = name.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        // Iterate code points (not chars) to match Rust's per-character mapping:
        // one supplementary character becomes one underscore, not two.
        lowered.codePoints().forEach(cp -> {
            if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '_') {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_');
            }
        });
        return sb.toString();
    }

    /**
     * Derives the engine's source name for an input file path: the final path
     * component (filename, extension included) run through {@link #sanitize}.
     * Mirrors {@code TimeseriesInput::load} in {@code src/timeseries_input.rs}
     * ({@code Path::file_name} then {@code sanitize_name}).
     *
     * <p>Example: {@code ./data/MyData.csv} &rarr; {@code mydata_csv}.
     *
     * @param filePath the file path (absolute, relative, or trailhead form)
     * @return the engine-form source name
     */
    public static String sanitizeFileName(String filePath) {
        Path fileName = Paths.get(filePath).getFileName();
        return sanitize(fileName != null ? fileName.toString() : filePath);
    }

    private static final Pattern NODE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** The message shown wherever a node name fails {@link #isValidNodeName}. */
    public static final String NODE_NAME_RULE = "Node names may contain only letters, digits and underscores";

    /**
     * Whether {@code name} is a legal node name. Mirrors {@code fn is_valid_name_segment}
     * in {@code src/misc/misc_functions.rs}: ASCII letters (either case), digits and
     * underscores, non-empty.
     *
     * @param name the node name, without the {@code node.} prefix
     * @return true if the engine accepts it
     */
    public static boolean isValidNodeName(String name) {
        return name != null && NODE_NAME.matcher(name).matches();
    }

    /**
     * The key node names are compared by. Node names are not case sensitive in the
     * engine, so {@code Dam} and {@code dam} are the same node.
     *
     * @param name the node name
     * @return the name lowercased
     */
    public static String nodeKey(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
