package com.kalix.ide.linter;

import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.preferences.PreferenceKeys;
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

        /**
         * Called when the schema or per-rule preferences change while the global
         * enabled flag stays the same (e.g. an individual rule is toggled, or the
         * schema file is reloaded). Listeners should revalidate so highlights
         * reflect the new rules immediately rather than on the next keystroke.
         */
        default void onSchemaChanged() {}
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
        lintingEnabled = PreferenceKeys.LINTER_ENABLED.get();

        // Load disabled rules
        disabledRules.clear();
        List<String> disabledRulesList = PreferenceKeys.LINTER_DISABLED_RULES.get();
        disabledRules.addAll(disabledRulesList);

    }

    /**
     * Reload the schema from preferences and notify listeners.
     */
    public void reloadSchema() {
        reloadSchemaInternal();
        notifySchemaChanged();
    }

    /**
     * Reload the schema without notifying listeners (callers decide which
     * notification, if any, applies).
     */
    private void reloadSchemaInternal() {
        try {
            String customSchemaPath = PreferenceKeys.LINTER_SCHEMA_PATH.get();

            if (customSchemaPath.isEmpty()) {
                // Use default embedded schema
                currentSchema = LinterSchema.loadDefault();
            } else {
                // Try to load custom schema
                Path schemaPath = Paths.get(customSchemaPath);
                if (Files.exists(schemaPath) && Files.isRegularFile(schemaPath)) {
                    currentSchema = LinterSchema.loadFromFile(schemaPath);
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
            }
        }
    }

    /**
     * Update preferences and reload schema.
     */
    public void updatePreferences(boolean enabled, String schemaPath, Set<String> disabledRuleNames) {
        boolean wasEnabled = this.lintingEnabled;

        // Save to preferences
        PreferenceKeys.LINTER_ENABLED.set(enabled);
        PreferenceKeys.LINTER_SCHEMA_PATH.set(schemaPath != null ? schemaPath : "");
        PreferenceKeys.LINTER_DISABLED_RULES.set(disabledRuleNames.stream().toList());

        // Update local state
        this.lintingEnabled = enabled;
        this.disabledRules = new HashSet<>(disabledRuleNames);

        // Reload schema with new preferences
        reloadSchemaInternal();

        // Notify listeners: an enabled-flag flip drives the full enable/disable
        // path; otherwise the rules/schema changed in place and listeners must
        // revalidate (stale highlights persisted until the next keystroke before).
        if (wasEnabled != enabled) {
            notifyLintingStateChanged(enabled);
        } else {
            notifySchemaChanged();
        }
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
        return PreferenceKeys.LINTER_SCHEMA_PATH.get();
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

    /**
     * Notify all listeners that the schema or per-rule preferences changed.
     */
    private void notifySchemaChanged() {
        for (LintingStateChangeListener listener : listeners) {
            try {
                listener.onSchemaChanged();
            } catch (Exception e) {
                logger.warn("Error notifying schema change listener", e);
            }
        }
    }
}