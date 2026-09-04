package com.kalix.ide.linter.validators;

import com.kalix.ide.io.KalixPath;
import com.kalix.ide.io.KalixPathResolutionException;
import com.kalix.ide.linter.parsing.INIModelParser;
import com.kalix.ide.linter.LinterSchema;
import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates file existence for input files referenced in the model.
 *
 * <p>Pixie sources get two extra checks, mirroring what the engine enforces at
 * load time (see {@code TimeseriesInput::read_source}): {@code [data]} names the
 * {@code .pxt} half only, and the {@code .pxb} sibling it implies must be
 * present. Catching both here means the modeller sees them in the editor rather
 * than as a failed run.</p>
 */
public class FileValidator implements ValidationStrategy {

    private final Map<String, Long> fileExistenceCache = new HashMap<>();
    private final long cacheTimeout = 5000; // 5 seconds

    @Override
    public void validate(INIModelParser.ParsedModel model, LinterSchema schema, ValidationResult result, java.io.File baseDirectory) {
        ValidationRule rule = schema.getValidationRule("file_paths");
        if (rule == null || !rule.isEnabled()) return;

        // Every entry on its own line: a path listed twice that does not exist
        // is wrong on both lines.
        for (INIModelParser.ListEntry entry : model.getInputFileEntries()) {
            String filePath = entry.text();
            int lineNumber = entry.lineNumber();
            if (isPixieBinary(filePath)) {
                // Reported instead of "does not exist": the .pxb is usually
                // sitting right there, and pointing at the wrong half of the
                // pair is the mistake worth naming.
                result.addIssue(lineNumber,
                              "Name the .pxt half of a Pixie pair here, not the .pxb: "
                                      + pixieSibling(filePath, ".pxt"),
                              rule.getSeverity(), "pixie_binary_named");
                continue;
            }

            if (!fileExists(filePath, baseDirectory)) {
                result.addIssue(lineNumber,
                              "Input file does not exist: " + filePath,
                              rule.getSeverity(), "file_not_found");
                continue;
            }

            if (isPixieMetadata(filePath)) {
                String companion = pixieSibling(filePath, ".pxb");
                if (!fileExists(companion, baseDirectory)) {
                    result.addIssue(lineNumber,
                                  "Pixie source is missing its companion file: " + companion,
                                  rule.getSeverity(), "pixie_companion_missing");
                }
            }
        }
    }

    private static boolean isPixieMetadata(String filePath) {
        return filePath.toLowerCase().endsWith(".pxt");
    }

    private static boolean isPixieBinary(String filePath) {
        return filePath.toLowerCase().endsWith(".pxb");
    }

    /** Swaps a Pixie path's extension for the other half of the pair. */
    private static String pixieSibling(String filePath, String extension) {
        return filePath.substring(0, filePath.length() - 4) + extension;
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

        // Resolve the path using KalixPath (supports absolute, relative, and trailhead paths)
        try {
            Path contextDir = baseDirectory != null ? baseDirectory.toPath() : Paths.get(".");
            Path resolved = KalixPath.parse(filePath).resolve(contextDir);
            boolean exists = Files.exists(resolved);
            if (exists) {
                fileExistenceCache.put(cacheKey, now);
            } else {
                fileExistenceCache.remove(cacheKey);
            }
            return exists;
        } catch (IllegalArgumentException | KalixPathResolutionException e) {
            fileExistenceCache.remove(cacheKey);
            return false;
        }
    }
}