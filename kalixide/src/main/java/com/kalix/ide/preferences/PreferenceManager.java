package com.kalix.ide.preferences;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * Hybrid preference management system that stores user-configurable preferences
 * in a local JSON file (kalix_prefs.json) and transient UI state in OS preferences.
 *
 * <p>File preferences are portable and shareable between users/machines.
 * OS preferences handle rapidly-changing UI state like window positions.
 * Access goes through the typed {@link Pref} constants in {@link PreferenceKeys};
 * the stringly accessors here are package-private plumbing for {@link Pref}.
 *
 * <p><b>Preference file location.</b> On <b>macOS</b> the per-user config
 * directory ({@code ~/.kalix}) is always used: writing inside the {@code .app}
 * bundle would invalidate its code signature and fail on read-only installs.
 * On <b>Windows/Linux</b> the location is portable, with this precedence:
 * <ol>
 *   <li>The directory containing the application JAR (portable installs),
 *       resolved via the code-source URI so install paths with spaces or
 *       non-ASCII characters work.</li>
 *   <li>In development — when the code source is a classes directory such as
 *       Gradle's {@code build/classes/java/main} — the working directory.</li>
 *   <li>If the directory chosen above is not writable, a per-user config
 *       directory ({@code ~/.kalix}), noted once on stderr.</li>
 * </ol>
 *
 * <p>Reads never write: a missing key yields the caller's default without
 * persisting it, so untouched defaults are not recorded as explicit choices
 * and two running instances do not rewrite each other's file at startup.
 * Sets are saved to disk immediately.
 */
public class PreferenceManager {

    // File-based preferences (cached in memory, read once)
    private static Map<String, Object> filePreferences = new HashMap<>();
    private static boolean preferencesLoaded = false;
    private static File preferenceFile;

    // OS-based preferences (delegated to Java Preferences)
    private static final Preferences osPrefs = Preferences.userNodeForPackage(PreferenceManager.class);

    static {
        initializePreferenceFile();
    }

    /**
     * Initialize the preference file location. See the class javadoc for the
     * directory precedence.
     */
    private static void initializePreferenceFile() {
        File dir;
        if (isMac()) {
            // macOS: never write inside the .app bundle — it breaks the code
            // signature and fails on read-only installs. Always use the per-user
            // config dir, shared across versions.
            dir = new File(System.getProperty("user.home"), ".kalix");
        } else {
            // Windows/Linux: portable — prefs live alongside the install, with a
            // per-user fallback when that directory is not writable.
            dir = resolveApplicationDirectory();
            if (!isWritableDirectory(dir)) {
                File fallback = new File(System.getProperty("user.home"), ".kalix");
                System.err.println("Kalix: preference directory " + dir.getAbsolutePath()
                    + " is not writable; using " + fallback.getAbsolutePath());
                dir = fallback;
            }
        }
        preferenceFile = new File(dir, "kalix_prefs.json");
    }

    /** True when running on macOS (drives the per-user prefs location above). */
    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * The directory the application runs from: the JAR's directory for installs,
     * or the working directory in development (code source is a classes directory,
     * e.g. Gradle's {@code build/classes/java/main}). Resolved via the code-source
     * URI, not its raw path, so URL-encoded characters ("My%20Folder") cannot
     * silently point at a nonexistent directory.
     */
    private static File resolveApplicationDirectory() {
        try {
            URI location = PreferenceManager.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI();
            Path codeSource = Paths.get(location);
            if (Files.isDirectory(codeSource)) {
                // Development: running from a classes directory, not a JAR
                return new File(System.getProperty("user.dir"));
            }
            Path jarDir = codeSource.getParent();
            return jarDir != null ? jarDir.toFile() : new File(System.getProperty("user.dir"));
        } catch (Exception e) {
            // No code source / opaque URI - fall back to the working directory
            return new File(System.getProperty("user.dir"));
        }
    }

    /** True when the directory exists and can be written to. */
    private static boolean isWritableDirectory(File dir) {
        return dir.isDirectory() && Files.isWritable(dir.toPath());
    }

    /**
     * Test hook: redirect the preference file and drop all cached state so the
     * next read reloads from the given file. Package-private, tests only.
     */
    static synchronized void redirectForTesting(File file) {
        preferenceFile = file;
        filePreferences = new HashMap<>();
        preferencesLoaded = false;
    }

    // ==== FILE-BASED PREFERENCE METHODS (package-private plumbing for Pref) ====

    /**
     * Gets a boolean preference from the file-based preference system.
     * A missing or invalid value yields the default without writing it back.
     */
    static boolean getFileBoolean(String key, boolean defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Sets a boolean preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    static void setFileBoolean(String key, boolean value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a string preference from the file-based preference system.
     * A missing or invalid value yields the default without writing it back.
     */
    static String getFileString(String key, String defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    /**
     * Sets a string preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    static void setFileString(String key, String value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets an integer preference from the file-based preference system.
     * A missing or invalid value yields the default without writing it back.
     */
    static int getFileInt(String key, int defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    /**
     * Sets an integer preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    static void setFileInt(String key, int value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a double preference from the file-based preference system.
     * A missing or invalid value yields the default without writing it back.
     */
    static double getFileDouble(String key, double defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : defaultValue;
    }

    /**
     * Sets a double preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    static void setFileDouble(String key, double value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a string list preference from the file-based preference system.
     * A missing or invalid value yields the default without writing it back.
     */
    @SuppressWarnings("unchecked")
    static List<String> getFileStringList(String key, List<String> defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return defaultValue;
    }

    /**
     * Sets a string list preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    static void setFileStringList(String key, List<String> value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    // ==== OS-BASED PREFERENCE METHODS (package-private plumbing for Pref) ====

    /**
     * Gets a boolean preference from the OS preference system.
     */
    static boolean getOsBoolean(String key, boolean defaultValue) {
        return osPrefs.getBoolean(key, defaultValue);
    }

    /**
     * Sets a boolean preference in the OS preference system.
     */
    static void setOsBoolean(String key, boolean value) {
        osPrefs.putBoolean(key, value);
    }

    /**
     * Gets a string preference from the OS preference system.
     */
    static String getOsString(String key, String defaultValue) {
        return osPrefs.get(key, defaultValue);
    }

    /**
     * Sets a string preference in the OS preference system.
     */
    static void setOsString(String key, String value) {
        osPrefs.put(key, value);
    }

    /**
     * Gets an integer preference from the OS preference system.
     */
    static int getOsInt(String key, int defaultValue) {
        return osPrefs.getInt(key, defaultValue);
    }

    /**
     * Sets an integer preference in the OS preference system.
     */
    static void setOsInt(String key, int value) {
        osPrefs.putInt(key, value);
    }

    // ==== INTERNAL IMPLEMENTATION ====

    /**
     * Ensures that file preferences have been loaded from disk.
     */
    private static void ensureLoaded() {
        if (!preferencesLoaded) {
            loadFromFile();
            preferencesLoaded = true;
        }
    }

    /**
     * Loads preferences from the JSON file.
     * If the file is missing or corrupted, starts with an empty preference map.
     */
    private static void loadFromFile() {
        try {
            if (preferenceFile.exists()) {
                String content = Files.readString(preferenceFile.toPath());
                Map<String, Object> loaded = parseSimpleJson(content);
                filePreferences = loaded != null ? loaded : new HashMap<>();
            }
        } catch (Exception e) {
            // File missing/corrupted - start with empty map
            filePreferences = new HashMap<>();
            System.err.println("Warning: Could not load preferences from " +
                preferenceFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Saves the current file preferences to disk as JSON.
     * If saving fails, logs a warning but continues execution.
     */
    private static void saveToFile() {
        try {
            // Create parent directory if it doesn't exist
            File parentDir = preferenceFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String json = generateSimpleJson(filePreferences);

            // Atomic write: a crash mid-write must never truncate the preferences
            // file (load treats a corrupt file as empty, losing every setting).
            // Write a sibling temp file, then move it over the target.
            java.nio.file.Path target = preferenceFile.toPath();
            java.nio.file.Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, json);
            try {
                Files.move(temp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not save preferences to " +
                preferenceFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Gets the current preference file location for debugging purposes.
     */
    public static String getPreferenceFilePath() {
        return preferenceFile.getAbsolutePath();
    }

    // ==== SIMPLE JSON IMPLEMENTATION ====
    //
    // A minimal, dependency-free JSON reader/writer for the flat preference shape:
    // one object of string/boolean/number/string-array values. The writer emits
    // strictly valid JSON (full string escaping, so quotes, backslashes in Windows
    // paths, and newlines in free-text preferences round-trip and stay readable by
    // external tools). The reader is a character-level parser -- no regex splitting,
    // so commas and quotes inside string values are handled correctly -- and is
    // deliberately lenient about invalid escape sequences so files written by the
    // pre-escaping implementation (raw backslashes in paths) load unchanged.

    /**
     * Parses a simple JSON object into a Map.
     * Only supports flat key-value pairs with string, boolean, number, and string array values.
     */
    private static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        JsonCursor cur = new JsonCursor(json);

        cur.skipWhitespace();
        if (!cur.tryConsume('{')) {
            return result; // not an object -- treat as empty
        }
        cur.skipWhitespace();
        if (cur.tryConsume('}')) {
            return result;
        }

        while (true) {
            cur.skipWhitespace();
            if (cur.peek() != '"') {
                break; // malformed key -- salvage what was parsed so far
            }
            String key = cur.readString();
            cur.skipWhitespace();
            if (!cur.tryConsume(':')) {
                break;
            }
            cur.skipWhitespace();
            Object value = cur.readValue();
            result.put(key, value);
            cur.skipWhitespace();
            if (cur.tryConsume(',')) {
                continue;
            }
            break; // '}' or end of input
        }

        return result;
    }

    /** Character-level cursor over a JSON document. */
    private static final class JsonCursor {
        private final String s;
        private int pos;

        JsonCursor(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        boolean tryConsume(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        /** Reads a value: string, array of strings, boolean, null, or number. */
        Object readValue() {
            char c = peek();
            if (c == '"') {
                return readString();
            }
            if (c == '[') {
                return readStringArray();
            }
            // Literal: read until a delimiter
            int start = pos;
            while (pos < s.length() && ",}]".indexOf(s.charAt(pos)) < 0
                    && !Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            String literal = s.substring(start, pos);
            switch (literal) {
                case "true": return true;
                case "false": return false;
                case "null": return null;
                default:
                    try {
                        return literal.contains(".")
                            ? (Object) Double.parseDouble(literal)
                            : (Object) Integer.parseInt(literal);
                    } catch (NumberFormatException e) {
                        return literal; // preserve unknown literals as strings
                    }
            }
        }

        /** Reads a double-quoted string, unescaping it. Cursor must be on the opening quote. */
        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\' && pos < s.length()) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                    pos += 4;
                                    break;
                                } catch (NumberFormatException ignored) {
                                    // fall through to lenient handling
                                }
                            }
                            sb.append('\\').append('u');
                            break;
                        default:
                            // Lenient: files written by the pre-escaping implementation
                            // contain raw backslashes (Windows paths). Keep both chars.
                            sb.append('\\').append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString(); // unterminated string -- salvage
        }

        /** Reads a JSON array of strings. Cursor must be on the opening bracket. */
        List<String> readStringArray() {
            List<String> result = new ArrayList<>();
            pos++; // opening bracket
            skipWhitespace();
            if (tryConsume(']')) {
                return result;
            }
            while (pos < s.length()) {
                skipWhitespace();
                if (peek() == '"') {
                    result.add(readString());
                } else {
                    // Non-string element -- skip to next delimiter
                    while (pos < s.length() && ",]".indexOf(s.charAt(pos)) < 0) {
                        pos++;
                    }
                }
                skipWhitespace();
                if (tryConsume(',')) {
                    continue;
                }
                tryConsume(']');
                break;
            }
            return result;
        }
    }

    /**
     * Generates simple JSON from a Map.
     * Only supports flat key-value pairs with string, boolean, number, and string list values.
     * Keys are written in sorted order so the file diffs cleanly under version control.
     */
    private static String generateSimpleJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        boolean first = true;
        for (String key : new java.util.TreeMap<>(map).keySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;

            sb.append("  ").append(quoteJsonString(key)).append(": ");
            sb.append(formatJsonValue(map.get(key)));
        }

        sb.append("\n}");
        return sb.toString();
    }

    /**
     * Formats a value for JSON output.
     */
    private static String formatJsonValue(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof Boolean) {
            return value.toString();
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(quoteJsonString(item.toString()));
            }
            sb.append("]");
            return sb.toString();
        } else {
            // String value
            return quoteJsonString(value.toString());
        }
    }

    /** Quotes and escapes a string per the JSON grammar. */
    private static String quoteJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
