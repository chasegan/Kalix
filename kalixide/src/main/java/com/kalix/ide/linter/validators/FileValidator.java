package com.kalix.ide.linter.validators;

import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;

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
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        ValidationRule rule = schema.getValidationRule("file_paths");
        if (rule == null || !rule.isEnabled()) return;

        List<String> inputFiles = model.getInputFiles();
        for (String filePath : inputFiles) {
            if (!fileExists(filePath, baseDirectory)) {
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

    private boolean fileExists(String filePath, java.io.File baseDirectory) {
        // Create cache key that includes base directory
        String cacheKey = (baseDirectory != null ? baseDirectory.getAbsolutePath() + ":" : "") + filePath;

        // Use cache to avoid repeated file system calls
        long now = System.currentTimeMillis();
        Long lastCheck = fileExistenceCache.get(cacheKey);

        if (lastCheck != null && (now - lastCheck) < cacheTimeout) {
            return true; // Assume it still exists within cache timeout
        }

        // Resolve the path relative to base directory if provided
        java.nio.file.Path path;
        if (baseDirectory != null && !Paths.get(filePath).isAbsolute()) {
            path = baseDirectory.toPath().resolve(filePath);
        } else {
            path = Paths.get(filePath);
        }

        boolean exists = Files.exists(path);
        if (exists) {
            fileExistenceCache.put(cacheKey, now);
        } else {
            fileExistenceCache.remove(cacheKey);
        }

        return exists;
    }

    private int findFilePathLineNumber(INIModelParser.ParsedModel model, String filePath) {
        // First try to get the exact line number for this file path
        Integer lineNumber = model.getInputFileLineNumbers().get(filePath);
        if (lineNumber != null) {
            return lineNumber;
        }

        // Fallback to section start line if not found
        INIModelParser.Section inputsSection = model.getSections().get("inputs");
        return inputsSection != null ? inputsSection.getStartLine() + 1 : 1;
    }
}