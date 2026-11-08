package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import com.kalix.ide.utils.EngineNames;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates what must be unique, as the engine refuses it: node names (ignoring case),
 * section headers, and keys within a section. Each node name must also follow the
 * engine's name rule.
 */
public class UniqueNameValidator implements ValidationStrategy {

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        checkSectionHeaders(model, schema, result);
        checkKeys(model, schema, result);
        ValidationRule formatRule = schema.getValidationRule("node_name_format");
        if (formatRule != null && formatRule.isEnabled()) {
            for (INIModelParser.NodeSection node : model.getAllNodeSections()) {
                if (!EngineNames.isValidNodeName(node.getNodeName())) {
                    result.addIssue(node.getStartLine(),
                                  EngineNames.NODE_NAME_RULE + ": " + node.getNodeName(),
                                  formatRule.getSeverity(), "node_name_format");
                }
            }
        }

        ValidationRule rule = schema.getValidationRule("unique_node_names");
        if (rule == null || !rule.isEnabled()) return;

        Map<String, List<Integer>> duplicates = INIModelParser.findDuplicateNodes(model);
        for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet()) {
            String nodeName = entry.getKey();
            List<Integer> lineNumbers = entry.getValue();

            for (Integer lineNumber : lineNumbers) {
                result.addIssue(lineNumber,
                              "Duplicate node name: " + nodeName + " (node names are not case sensitive)",
                              rule.getSeverity(), "duplicate_node_name");
            }
        }
    }

    /**
     * A section header may appear only once; the engine refuses a repeat, which would
     * otherwise replace the earlier section. Node sections are left to the node rule,
     * which also catches a repeat that differs only in case.
     */
    private static void checkSectionHeaders(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("unique_section_headers");
        if (rule == null || !rule.isEnabled()) return;

        Map<String, Integer> firstLine = new HashMap<>();
        for (INIModelParser.Section section : model.getAllSections()) {
            if (section instanceof INIModelParser.NodeSection) continue;
            Integer earlier = firstLine.putIfAbsent(section.getName(), section.getStartLine());
            if (earlier != null) {
                result.addIssue(section.getStartLine(),
                              "Duplicate section [" + section.getName() + "] (also defined on line " + earlier + ")",
                              rule.getSeverity(), "duplicate_section");
            }
        }
    }

    /**
     * A key may appear only once in a section, and a bare entry once in {@code [outputs]}
     * or {@code [data]}, in any case, since the engine matches keys ignoring case; the
     * engine refuses a repeat, which would otherwise replace the earlier value or be kept once.
     */
    private static void checkKeys(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("unique_keys");
        if (rule == null || !rule.isEnabled()) return;

        for (INIModelParser.Section section : model.getAllSections()) {
            Map<String, Integer> firstLine = new HashMap<>();
            for (INIModelParser.Property property : section.getAllProperties()) {
                Integer earlier = firstLine.putIfAbsent(property.getKey().toLowerCase(Locale.ROOT), property.getLineNumber());
                if (earlier != null) {
                    result.addIssue(property.getLineNumber(),
                                  "Duplicate key '" + property.getKey() + "' in [" + section.getName() + "] (also defined on line " + earlier + ")",
                                  rule.getSeverity(), "duplicate_key");
                }
            }
        }
        // A repeated [outputs] entry is kept once by the engine, so it is not reported here.
        // An aliased [data] line is keyed by its alias, so one file may be listed under
        // two aliases; only the bare paths are entries in the engine's sense.
        Set<Integer> aliasLines = new HashSet<>();
        for (INIModelParser.Section section : model.getAllSections()) {
            if (!section.getName().equals("data")) continue;
            for (INIModelParser.Property property : section.getAllProperties()) {
                aliasLines.add(property.getLineNumber());
            }
        }
        List<INIModelParser.ListEntry> barePaths = model.getInputFileEntries().stream()
            .filter(entry -> !aliasLines.contains(entry.lineNumber())).toList();
        checkEntries(barePaths, "data", rule, result);
    }

    private static void checkEntries(List<INIModelParser.ListEntry> entries, String section,
                                     ValidationRule rule, ValidationResult result) {
        Map<String, Integer> firstLine = new HashMap<>();
        for (INIModelParser.ListEntry entry : entries) {
            Integer earlier = firstLine.putIfAbsent(entry.text().toLowerCase(Locale.ROOT), entry.lineNumber());
            if (earlier != null) {
                result.addIssue(entry.lineNumber(),
                              "Duplicate entry '" + entry.text() + "' in [" + section + "] (also listed on line " + earlier + ")",
                              rule.getSeverity(), "duplicate_key");
            }
        }
    }

    @Override
    public String getDescription() {
        return "Unique node name, section header and key validation";
    }
}