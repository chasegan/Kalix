package com.kalix.ide.preferences;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * Hybrid preference management system that stores user-configurable preferences
 * in a local JSON file (kalix_prefs.json) and transient UI state in OS preferences.
 *
 * File preferences are portable and shareable between users/machines.
 * OS preferences handle rapidly-changing UI state like window positions.
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
     * Initialize the preference file location (next to executable).
     */
    private static void initializePreferenceFile() {
        try {
            // Get the directory containing the currently running JAR
            String jarPath = PreferenceManager.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            File jarFile = new File(jarPath);
            File jarDir = jarFile.getParentFile();

            // For development (not in JAR), use current working directory
            if (jarDir == null || jarPath.endsWith("/classes/")) {
                jarDir = new File(System.getProperty("user.dir"));
            }

            preferenceFile = new File(jarDir, "kalix_prefs.json");
        } catch (Exception e) {
            // Fallback to current directory
            preferenceFile = new File("kalix_prefs.json");
        }
    }

    // ==== FILE-BASED PREFERENCE METHODS ====

    /**
     * Gets a boolean preference from the file-based preference system.
     * If the value is missing or invalid, returns the default and saves it to the file.
     */
    public static boolean getFileBoolean(String key, boolean defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        // Missing/invalid - use default and save it
        setFileBoolean(key, defaultValue);
        return defaultValue;
    }

    /**
     * Sets a boolean preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    public static void setFileBoolean(String key, boolean value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a string preference from the file-based preference system.
     * If the value is missing or invalid, returns the default and saves it to the file.
     */
    public static String getFileString(String key, String defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        // Missing/invalid - use default and save it
        setFileString(key, defaultValue);
        return defaultValue;
    }

    /**
     * Sets a string preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    public static void setFileString(String key, String value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets an integer preference from the file-based preference system.
     * If the value is missing or invalid, returns the default and saves it to the file.
     */
    public static int getFileInt(String key, int defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        // Missing/invalid - use default and save it
        setFileInt(key, defaultValue);
        return defaultValue;
    }

    /**
     * Sets an integer preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    public static void setFileInt(String key, int value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a double preference from the file-based preference system.
     * If the value is missing or invalid, returns the default and saves it to the file.
     */
    public static double getFileDouble(String key, double defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        // Missing/invalid - use default and save it
        setFileDouble(key, defaultValue);
        return defaultValue;
    }

    /**
     * Sets a double preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    public static void setFileDouble(String key, double value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    /**
     * Gets a string list preference from the file-based preference system.
     * If the value is missing or invalid, returns the default and saves it to the file.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getFileStringList(String key, List<String> defaultValue) {
        ensureLoaded();
        Object value = filePreferences.get(key);
        if (value instanceof List) {
            try {
                return (List<String>) value;
            } catch (ClassCastException e) {
                // Invalid list type - use default
            }
        }
        // Missing/invalid - use default and save it
        setFileStringList(key, defaultValue);
        return defaultValue;
    }

    /**
     * Sets a string list preference in the file-based preference system.
     * Changes are immediately saved to disk.
     */
    public static void setFileStringList(String key, List<String> value) {
        ensureLoaded();
        filePreferences.put(key, value);
        saveToFile();
    }

    // ==== OS-BASED PREFERENCE METHODS ====

    /**
     * Gets a boolean preference from the OS preference system.
     */
    public static boolean getOsBoolean(String key, boolean defaultValue) {
        return osPrefs.getBoolean(key, defaultValue);
    }

    /**
     * Sets a boolean preference in the OS preference system.
     */
    public static void setOsBoolean(String key, boolean value) {
        osPrefs.putBoolean(key, value);
    }

    /**
     * Gets a string preference from the OS preference system.
     */
    public static String getOsString(String key, String defaultValue) {
        return osPrefs.get(key, defaultValue);
    }

    /**
     * Sets a string preference in the OS preference system.
     */
    public static void setOsString(String key, String value) {
        osPrefs.put(key, value);
    }

    /**
     * Gets an integer preference from the OS preference system.
     */
    public static int getOsInt(String key, int defaultValue) {
        return osPrefs.getInt(key, defaultValue);
    }

    /**
     * Sets an integer preference in the OS preference system.
     */
    public static void setOsInt(String key, int value) {
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
