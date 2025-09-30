package com.kalix.ide.linter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks document changes and performs incremental validation to improve performance.
 * Only re-validates sections that have actually changed.
 */
public class IncrementalValidator {

    private static final Logger logger = LoggerFactory.getLogger(IncrementalValidator.class);

    private final ModelLinter linter;

    // Cached validation state
    private String lastValidatedContent = "";
    private ValidationResult lastValidationResult = new ValidationResult();
    private Map<String, SectionCache> sectionCache = new ConcurrentHashMap<>();
    private Set<String> changedSections = new HashSet<>();

    public IncrementalValidator(ModelLinter linter) {
        this.linter = linter;
    }

    /**
     * Validate content incrementally, only re-validating changed sections.
     */
    public ValidationResult validateIncremental(String content) {
        if (content == null || content.trim().isEmpty()) {
            clearCache();
            return new ValidationResult();
        }

        // If content hasn't changed, return cached result
        if (content.equals(lastValidatedContent)) {
            return lastValidationResult;
        }

        // Parse current content
        INIModelParser.ParsedModel currentModel = INIModelParser.parse(content);

        // Detect changes and determine what needs re-validation
        detectChanges(currentModel);

        ValidationResult result;

        // If too many sections changed, do full validation
        boolean forceFullValidation = shouldDoFullValidation(currentModel);
        if (changedSections.size() > 5 || forceFullValidation) {
            logger.debug("Performing full validation ({} sections changed)", changedSections.size());
            result = linter.validate(content);
            updateCacheForFullValidation(content, currentModel, result);
        } else {
            logger.debug("Performing incremental validation ({} sections changed)", changedSections.size());
            result = performIncrementalValidation(content, currentModel);
            updateCacheForIncrementalValidation(content, currentModel, result);
        }

        lastValidatedContent = content;
        lastValidationResult = result;
        changedSections.clear();

        return result;
    }

    /**
     * Detect which sections have changed since last validation.
     */
    private void detectChanges(INIModelParser.ParsedModel currentModel) {
        changedSections.clear();

        // Check for new, removed, or modified sections
        Set<String> currentSectionNames = currentModel.getSections().keySet();
        Set<String> cachedSectionNames = sectionCache.keySet();

        // New sections
        for (String sectionName : currentSectionNames) {
            if (!cachedSectionNames.contains(sectionName)) {
                changedSections.add(sectionName);
            }
        }

        // Removed sections
        for (String sectionName : cachedSectionNames) {
            if (!currentSectionNames.contains(sectionName)) {
                changedSections.add(sectionName);
                // Section was removed, no need to cache it anymore
                sectionCache.remove(sectionName);
            }
        }

        // Modified sections
        for (Map.Entry<String, INIModelParser.Section> entry : currentModel.getSections().entrySet()) {
            String sectionName = entry.getKey();
            INIModelParser.Section currentSection = entry.getValue();

            SectionCache cached = sectionCache.get(sectionName);
            if (cached != null && !cached.matches(currentSection)) {
                changedSections.add(sectionName);
            }
        }

        // Special handling for inputs and outputs (they don't have key-value properties)
        if (currentModel.getInputFiles() != null) {
            SectionCache inputsCache = sectionCache.get("inputs");
            if (inputsCache == null || !inputsCache.matchesInputs(currentModel.getInputFiles())) {
                changedSections.add("inputs");
            }
        }

        if (currentModel.getOutputReferences() != null) {
            SectionCache outputsCache = sectionCache.get("outputs");
            if (outputsCache == null || !outputsCache.matchesOutputs(currentModel.getOutputReferences())) {
                changedSections.add("outputs");
                logger.debug("Outputs section changed");
            }
        } else {
            // If outputs section is now empty but we had cached outputs, mark as changed
            SectionCache outputsCache = sectionCache.get("outputs");
            if (outputsCache != null && outputsCache.outputReferences != null && !outputsCache.outputReferences.isEmpty()) {
                changedSections.add("outputs");
                logger.debug("Outputs section changed (now empty)");
            }
        }
    }

    /**
     * Determine if we should do full validation instead of incremental.
     */
    private boolean shouldDoFullValidation(INIModelParser.ParsedModel currentModel) {
        // Do full validation if:
        // 1. We don't have a cached result
        // 2. Schema may have changed (could be detected by schema manager)
        // 3. There are cross-section dependencies that might be affected

        if (sectionCache.isEmpty()) {
            return true;
        }

        // If node sections changed, we need to check downstream references
        for (String changedSection : changedSections) {
            if (changedSection.startsWith("node.")) {
                // Node changes might affect downstream validation
                return true;
            }
        }

        return false;
    }

    /**
     * Perform incremental validation on only the changed sections.
     */
    private ValidationResult performIncrementalValidation(String content, INIModelParser.ParsedModel currentModel) {
        ValidationResult result = new ValidationResult();

        // Start with previous validation results for unchanged sections
        if (lastValidationResult != null) {
            for (ValidationIssue issue : lastValidationResult.getIssues()) {
                // Keep issues that are not in changed sections
                String issueSection = findSectionForLine(issue.getLineNumber(), currentModel);

                // Special handling for outputs section - if it changed, drop ALL previous errors
                // from outputs section regardless of line number mapping issues
                if (changedSections.contains("outputs") &&
                    (issueSection == null || "outputs".equals(issueSection) ||
                     issue.getRuleName().equals("invalid_output_reference"))) {
                    // Drop all output-related errors when outputs section changes
                    continue;
                }

                // Only keep issues if we can definitively identify the section and it hasn't changed
                if (issueSection != null && !changedSections.contains(issueSection)) {
                    result.addIssue(issue);
                }
                // If issueSection is null, the error is stale/invalid and should be dropped
            }
        }

        // Validate only changed sections
        // Note: This is a simplified implementation. A full implementation would
        // need to validate individual sections in isolation, which requires
        // refactoring the ModelLinter to support section-specific validation.

        // For now, we'll do targeted validation of specific aspects
        for (String sectionName : changedSections) {
            validateChangedSection(sectionName, currentModel, result);
        }

        return result;
    }

    /**
     * Validate a specific changed section.
     */
    private void validateChangedSection(String sectionName, INIModelParser.ParsedModel model, ValidationResult result) {
        INIModelParser.Section section = model.getSections().get(sectionName);
        if (section == null) {
            return; // Section was removed
        }

        // This is a simplified section-specific validation
        // In a full implementation, you'd extract relevant validation logic from ModelLinter

        if (sectionName.equals("attributes")) {
            validateAttributesSection(section, result);
        } else if (sectionName.equals("inputs")) {
            validateInputsSection(model.getInputFiles(), result);
        } else if (sectionName.equals("outputs")) {
            validateOutputsSection(model.getOutputReferences(), model, result);
        } else if (sectionName.startsWith("node.")) {
            if (section instanceof INIModelParser.NodeSection) {
                validateNodeSection((INIModelParser.NodeSection) section, model, result);
            }
        }
    }

    private void validateAttributesSection(INIModelParser.Section section, ValidationResult result) {
        // Basic ini_version validation using shared logic
        INIModelParser.Property versionProp = section.getProperties().get("ini_version");
        if (versionProp == null) {
            result.addIssue(section.getStartLine() + 1,
                          "Missing required property: ini_version",
                          ValidationRule.Severity.ERROR, "missing_ini_version");
        } else {
            String version = versionProp.getValue();
            if (!ValidationUtils.isValidIniVersion(version)) {
                result.addIssue(versionProp.getLineNumber(),
                              "Invalid ini_version format. Expected: X.Y.Z",
                              ValidationRule.Severity.ERROR, "invalid_ini_version");
            }
        }
    }

    private void validateInputsSection(List<String> inputFiles, ValidationResult result) {
        // Basic file existence check (simplified)
        for (String filePath : inputFiles) {
            if (!java.nio.file.Files.exists(java.nio.file.Paths.get(filePath))) {
                result.addIssue(1, // Line number would need to be tracked properly
                              "Input file does not exist: " + filePath,
                              ValidationRule.Severity.ERROR, "file_not_found");
            }
        }
    }

    private void validateOutputsSection(List<String> outputRefs, INIModelParser.ParsedModel model, ValidationResult result) {
        LinterSchema schema = linter.getSchemaManager().getCurrentSchema();
        if (schema != null) {
            ValidationUtils.validateOutputReferencesWithSchema(outputRefs, model, schema, result);
        } else {
            ValidationUtils.validateOutputReferences(outputRefs, model, result);
        }
    }

    private void validateNodeSection(INIModelParser.NodeSection node, INIModelParser.ParsedModel model, ValidationResult result) {
        // Basic node validation
        if (node.getNodeType() == null) {
            result.addIssue(node.getStartLine(),
                          "Node missing required 'type' parameter: " + node.getNodeName(),
                          ValidationRule.Severity.ERROR, "missing_node_type");
        }

        // Check downstream references
        for (INIModelParser.Property prop : node.getProperties().values()) {
            if (prop.getKey().startsWith("ds_")) {
                String referencedNode = prop.getValue();
                if (!model.getNodes().containsKey(referencedNode)) {
                    result.addIssue(prop.getLineNumber(),
                                  "Link points to non-existent node: " + referencedNode,
                                  ValidationRule.Severity.ERROR, "invalid_node_reference");
                }
            }
        }
    }

    private String findSectionForLine(int lineNumber, INIModelParser.ParsedModel model) {
        // Use section boundaries as the primary method for determining section membership
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            INIModelParser.Section section = entry.getValue();
            if (lineNumber >= section.getStartLine() && lineNumber <= section.getEndLine()) {
                return entry.getKey();
            }
        }

        // Fallback: Special handling for outputs section using line number mapping
        // This helps when section boundaries might not be perfectly accurate
        if (model.getOutputReferenceLineNumbers().containsValue(lineNumber)) {
            return "outputs";
        }

        return null;
    }

    /**
     * Update cache after full validation.
     */
    private void updateCacheForFullValidation(String content, INIModelParser.ParsedModel model, ValidationResult result) {
        sectionCache.clear();

        // Cache all sections
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            String sectionName = entry.getKey();
            INIModelParser.Section section = entry.getValue();
            sectionCache.put(sectionName, new SectionCache(section));
        }

        // Cache special sections
        if (model.getInputFiles() != null) {
            SectionCache inputsCache = new SectionCache();
            inputsCache.inputFiles = new ArrayList<>(model.getInputFiles());
            sectionCache.put("inputs", inputsCache);
        }

        if (model.getOutputReferences() != null) {
            SectionCache outputsCache = new SectionCache();
            outputsCache.outputReferences = new ArrayList<>(model.getOutputReferences());
            sectionCache.put("outputs", outputsCache);
        }
    }

    /**
     * Update cache after incremental validation - only update changed sections.
     */
    private void updateCacheForIncrementalValidation(String content, INIModelParser.ParsedModel model, ValidationResult result) {
        // Update cache for changed sections only
        for (String sectionName : changedSections) {
            if ("inputs".equals(sectionName)) {
                SectionCache inputsCache = new SectionCache();
                inputsCache.inputFiles = new ArrayList<>(model.getInputFiles());
                sectionCache.put("inputs", inputsCache);
            } else if ("outputs".equals(sectionName)) {
                SectionCache outputsCache = new SectionCache();
                outputsCache.outputReferences = new ArrayList<>(model.getOutputReferences());
                sectionCache.put("outputs", outputsCache);
            } else {
                // Regular section
                INIModelParser.Section section = model.getSections().get(sectionName);
                if (section != null) {
                    sectionCache.put(sectionName, new SectionCache(section));
                }
            }
        }
    }

    /**
     * Clear all cached data.
     */
    public void clearCache() {
        lastValidatedContent = "";
        lastValidationResult = new ValidationResult();
        sectionCache.clear();
        changedSections.clear();
    }

    /**
     * Cache for section content to detect changes.
     */
    private static class SectionCache {
        private Map<String, String> properties = new HashMap<>();
        private List<String> inputFiles;
        private List<String> outputReferences;

        public SectionCache() {
            // Empty constructor for special sections
        }

        public SectionCache(INIModelParser.Section section) {
            for (Map.Entry<String, INIModelParser.Property> entry : section.getProperties().entrySet()) {
                properties.put(entry.getKey(), entry.getValue().getValue());
            }
        }

        public boolean matches(INIModelParser.Section section) {
            Map<String, String> currentProperties = new HashMap<>();
            for (Map.Entry<String, INIModelParser.Property> entry : section.getProperties().entrySet()) {
                currentProperties.put(entry.getKey(), entry.getValue().getValue());
            }
            return properties.equals(currentProperties);
        }

        public boolean matchesInputs(List<String> currentInputs) {
            return Objects.equals(inputFiles, currentInputs);
        }

        public boolean matchesOutputs(List<String> currentOutputs) {
            return Objects.equals(outputReferences, currentOutputs);
        }
    }
}