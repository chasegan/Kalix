package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.ValidationResult;
import com.kalix.ide.linter.ValidationRule;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates file existence for input files referenced in the model.
 */
public class FileValidator implements ValidationStrategy {

    private final Map<String, Long> fileExistenceCache = new HashMap<>();
    private final long cacheTimeout = 5000; // 5 seconds

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result) {
        ValidationRule rule = schema.getValidationRule("file_paths");
        if (rule == null || !rule.isEnabled()) return;

        List<String> inputFiles = model.getInputFiles();
        for (String filePath : inputFiles) {
            if (!fileExists(filePath)) {
                int lineNumber = findFilePathLineNumber(model, filePath);
                result.addIssue(lineNumber,
                              "Input file does not exist: " + filePath,
                              rule.getSeverity(), "file_not_found");
            }
        }
    }

    @Override
    public String getDescription() {
        return "Input file existence validation";
    }

    private boolean fileExists(String filePath) {
        // Use cache to avoid repeated file system calls
        long now = System.currentTimeMillis();
        Long lastCheck = fileExistenceCache.get(filePath);

        if (lastCheck != null && (now - lastCheck) < cacheTimeout) {
            return true; // Assume it still exists within cache timeout
        }

        boolean exists = Files.exists(Paths.get(filePath));
        if (exists) {
            fileExistenceCache.put(filePath, now);
        } else {
            fileExistenceCache.remove(filePath);
        }

        return exists;
    }

    private int findFilePathLineNumber(INIModelParser.ParsedModel model, String filePath) {
        INIModelParser.Section inputsSection = model.getSections().get("inputs");
        return inputsSection != null ? inputsSection.getStartLine() + 1 : 1;
    }
}