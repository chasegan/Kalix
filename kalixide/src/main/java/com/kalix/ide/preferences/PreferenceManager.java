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
            Files.writeString(preferenceFile.toPath(), json);
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

    /**
     * Parses a simple JSON object into a Map.
     * Only supports flat key-value pairs with string, boolean, number, and string array values.
     */
    private static Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();

        // Remove whitespace and outer braces
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) {
            json = json.substring(1, json.length() - 1).trim();
        }

        if (json.isEmpty()) {
            return result;
        }

        // Split by commas (simple approach - doesn't handle arrays with commas)
        String[] pairs = json.split(",(?=\\s*\"[^\"]+\"\\s*:)");

        for (String pair : pairs) {
            pair = pair.trim();
            int colonIndex = pair.indexOf(":");
            if (colonIndex > 0) {
                String key = pair.substring(0, colonIndex).trim();
                String value = pair.substring(colonIndex + 1).trim();

                // Remove quotes from key
                if (key.startsWith("\"") && key.endsWith("\"")) {
                    key = key.substring(1, key.length() - 1);
                }

                // Parse value
                Object parsedValue = parseJsonValue(value);
                if (parsedValue != null) {
                    result.put(key, parsedValue);
                }
            }
        }

        return result;
    }

    /**
     * Parses a JSON value (string, boolean, number, or string array).
     */
    private static Object parseJsonValue(String value) {
        value = value.trim();

        // Boolean values
        if ("true".equals(value)) {
            return true;
        } else if ("false".equals(value)) {
            return false;
        }
        // Null value
        else if ("null".equals(value)) {
            return null;
        }
        // String array
        else if (value.startsWith("[") && value.endsWith("]")) {
            return parseJsonStringArray(value);
        }
        // String value
        else if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        // Number value
        else {
            try {
                if (value.contains(".")) {
                    return Double.parseDouble(value);
                } else {
                    return Integer.parseInt(value);
                }
            } catch (NumberFormatException e) {
                return value; // Return as string if not a valid number
            }
        }
    }

    /**
     * Parses a JSON string array.
     */
    private static List<String> parseJsonStringArray(String arrayStr) {
        List<String> result = new ArrayList<>();

        // Remove brackets
        arrayStr = arrayStr.substring(1, arrayStr.length() - 1).trim();

        if (arrayStr.isEmpty()) {
            return result;
        }

        // Split by commas
        String[] items = arrayStr.split(",");
        for (String item : items) {
            item = item.trim();
            if (item.startsWith("\"") && item.endsWith("\"")) {
                result.add(item.substring(1, item.length() - 1));
            }
        }

        return result;
    }

    /**
     * Generates simple JSON from a Map.
     * Only supports flat key-value pairs with string, boolean, number, and string list values.
     */
    private static String generateSimpleJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;

            sb.append("  \"").append(entry.getKey()).append("\": ");
            sb.append(formatJsonValue(entry.getValue()));
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
                sb.append("\"").append(item.toString()).append("\"");
            }
            sb.append("]");
            return sb.toString();
        } else {
            // String value
            return "\"" + value + "\"";
        }
    }
}