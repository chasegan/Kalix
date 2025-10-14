package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.linter.utils.ValidationUtils;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates output references and downstream node references.
 */
public class ReferenceValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        validateOutputReferences(model, schema, result);
        validateDownstreamReferences(model, schema, result);
    }

    @Override
    public String getDescription() {
        return "Output and downstream reference validation";
    }

    private void validateOutputReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        // Use shared validation logic
        ValidationUtils.validateOutputReferencesWithSchema(model.getOutputReferences(), model, schema, result);

        // Additional validation for node existence (beyond pattern matching)
        ValidationRule rule = schema.getValidationRule("output_references");
        if (rule == null || !rule.isEnabled()) return;

        Pattern outputPattern = ValidationUtils.getOutputReferencePattern();
        for (String outputRef : model.getOutputReferences()) {
            if (outputPattern.matcher(outputRef).matches()) {
                // Extract node name and check if it exists
                String[] parts = outputRef.split("\\.");
                if (parts.length >= 2) {
                    String nodeName = parts[1];
                    if (!model.getNodes().containsKey(nodeName)) {
                        int lineNumber = findOutputRefLineNumber(model, outputRef);
                        result.addIssue(lineNumber,
                                      "Output reference points to non-existent node: " + nodeName,
                                      rule.getSeverity(), "invalid_node_reference");
                    }
                }
            }
        }
    }

    private void validateDownstreamReferences(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("dsnode_references");
        if (rule == null || !rule.isEnabled()) return;

        Set<String> nodeNames = model.getNodes().keySet();

        for (INIModelParser.NodeSection node : model.getNodes().values()) {
            for (INIModelParser.Property prop : node.getProperties().values()) {
                if (prop.getKey().startsWith("ds_")) {
                    String referencedNode = prop.getValue();
                    if (!nodeNames.contains(referencedNode)) {
                        result.addIssue(prop.getLineNumber(),
                                      "Link points to non-existent node: " + referencedNode,
                                      rule.getSeverity(), "invalid_node_reference");
                    }
                }
            }
        }
    }

    private int findOutputRefLineNumber(INIModelParser.ParsedModel model, String outputRef) {
        // Get the actual line number where this output reference appears
        Integer lineNumber = model.getOutputReferenceLineNumbers().get(outputRef);
        if (lineNumber != null) {
            return lineNumber;
        }

        // If lookup failed, try to find a reasonable line number in the outputs section
        INIModelParser.Section outputsSection = model.getSections().get("outputs");
        if (outputsSection != null) {
            // If we have any output references with line numbers, use the first one as approximation
            if (!model.getOutputReferenceLineNumbers().isEmpty()) {
                int firstOutputLine = model.getOutputReferenceLineNumbers().values().iterator().next();
                return firstOutputLine;
            }
            // Otherwise use the section start line + 2
            return Math.max(outputsSection.getStartLine() + 2, 1);
        }

        // Last resort fallback
        return 1;
    }
}