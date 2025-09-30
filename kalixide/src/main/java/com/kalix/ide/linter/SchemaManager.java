package com.kalix.ide.linter;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages linter schema lifecycle including loading from preferences and schema reloading.
 */
public class SchemaManager {

    private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);

    private LinterSchema currentSchema;
    private Set<String> disabledRules = new HashSet<>();
    private boolean lintingEnabled;

    // Callback interface for preference changes
    public interface LintingStateChangeListener {
        void onLintingEnabledChanged(boolean enabled);
    }

    private final List<LintingStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Initialize the schema manager by loading schema and preferences.
     */
    public void initialize() {
        loadLinterPreferences();
        reloadSchema();
    }

    /**
     * Load linter preferences from the preference system.
     */
    private void loadLinterPreferences() {
        // Load linting enabled flag
        lintingEnabled = PreferenceManager.getFileBoolean(PreferenceKeys.LINTER_ENABLED, true);

        // Load disabled rules
        disabledRules.clear();
        List<String> disabledRulesList = PreferenceManager.getFileStringList(PreferenceKeys.LINTER_DISABLED_RULES, List.of());
        disabledRules.addAll(disabledRulesList);

        logger.debug("Loaded linter preferences: enabled={}, disabled rules={}", lintingEnabled, disabledRules);
    }

    /**
     * Reload the schema from preferences.
     */
    public void reloadSchema() {
        try {
            String customSchemaPath = PreferenceManager.getFileString(PreferenceKeys.LINTER_SCHEMA_PATH, "");

            if (customSchemaPath.isEmpty()) {
                // Use default embedded schema
                currentSchema = LinterSchema.loadDefault();
                logger.info("Loaded default embedded schema");
            } else {
                // Try to load custom schema
                Path schemaPath = Paths.get(customSchemaPath);
                if (Files.exists(schemaPath) && Files.isRegularFile(schemaPath)) {
                    currentSchema = LinterSchema.loadFromFile(schemaPath);
                    logger.info("Loaded custom schema from: {}", customSchemaPath);
                } else {
                    logger.warn("Custom schema file not found: {}, falling back to default", customSchemaPath);
                    currentSchema = LinterSchema.loadDefault();
                }
            }

            // Apply disabled rules to the loaded schema
            applyDisabledRules();

        } catch (Exception e) {
            logger.error("Failed to load schema, falling back to default", e);
            try {
                currentSchema = LinterSchema.loadDefault();
                applyDisabledRules();
            } catch (Exception fallbackError) {
                logger.error("Failed to load fallback schema", fallbackError);
                currentSchema = null;
            }
        }
    }

    /**
     * Apply disabled rules from preferences to the loaded schema.
     */
    private void applyDisabledRules() {
        if (currentSchema == null) return;

        for (String ruleName : disabledRules) {
            ValidationRule rule = currentSchema.getValidationRule(ruleName);
            if (rule != null) {
                rule.setEnabled(false);
                logger.debug("Disabled rule: {}", ruleName);
            }
        }
    }

    /**
     * Update preferences and reload schema.
     */
    public void updatePreferences(boolean enabled, String schemaPath, Set<String> disabledRuleNames) {
        boolean wasEnabled = this.lintingEnabled;

        // Save to preferences
        PreferenceManager.setFileBoolean(PreferenceKeys.LINTER_ENABLED, enabled);
        PreferenceManager.setFileString(PreferenceKeys.LINTER_SCHEMA_PATH, schemaPath != null ? schemaPath : "");
        PreferenceManager.setFileStringList(PreferenceKeys.LINTER_DISABLED_RULES, disabledRuleNames.stream().toList());

        // Update local state
        this.lintingEnabled = enabled;
        this.disabledRules = new HashSet<>(disabledRuleNames);

        // Reload schema with new preferences
        reloadSchema();

        // Notify listeners if linting enabled state changed
        if (wasEnabled != enabled) {
            notifyLintingStateChanged(enabled);
        }

        logger.info("Updated linter preferences and reloaded schema");
    }

    /**
     * Enable or disable a specific validation rule.
     */
    public void setRuleEnabled(String ruleName, boolean enabled) {
        if (enabled) {
            disabledRules.remove(ruleName);
        } else {
            disabledRules.add(ruleName);
        }

        // Update schema
        if (currentSchema != null) {
            ValidationRule rule = currentSchema.getValidationRule(ruleName);
            if (rule != null) {
                rule.setEnabled(enabled);
            }
        }

        // Save to preferences
        PreferenceManager.setFileStringList(PreferenceKeys.LINTER_DISABLED_RULES, disabledRules.stream().toList());
    }

    // Getters
    public LinterSchema getCurrentSchema() {
        return currentSchema;
    }

    public boolean isLintingEnabled() {
        return lintingEnabled && currentSchema != null;
    }

    public Set<String> getDisabledRules() {
        return new HashSet<>(disabledRules);
    }

    public String getCurrentSchemaPath() {
        return PreferenceManager.getFileString(PreferenceKeys.LINTER_SCHEMA_PATH, "");
    }

    /**
     * Check if the schema is successfully loaded and ready for use.
     */
    public boolean isSchemaLoaded() {
        return currentSchema != null;
    }

    /**
     * Get schema version for display purposes.
     */
    public String getSchemaVersion() {
        return currentSchema != null ? currentSchema.getVersion() : "unknown";
    }

    /**
     * Add a listener for linting state changes.
     */
    public void addLintingStateChangeListener(LintingStateChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a linting state change listener.
     */
    public void removeLintingStateChangeListener(LintingStateChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notify all listeners that the linting enabled state has changed.
     */
    private void notifyLintingStateChanged(boolean enabled) {
        for (LintingStateChangeListener listener : listeners) {
            try {
                listener.onLintingEnabledChanged(enabled);
            } catch (Exception e) {
                logger.warn("Error notifying linting state change listener", e);
            }
        }
    }
}