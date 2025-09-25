package com.kalix.ide.cli;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Objects;

/**
 * Handles version compatibility checking between IDE and CLI.
 * Provides semantic version parsing and compatibility validation.
 */
public class VersionCompatibility {
    
    // Pattern to match semantic versions (major.minor.patch[-prerelease][+build])
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "(?:kalixcli\\s+)?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-]([a-zA-Z0-9.-]+))?(?:[+]([a-zA-Z0-9.-]+))?"
    );
    
    /**
     * Represents a semantic version.
     */
    public static class Version implements Comparable<Version> {
        private final int major;
        private final int minor;
        private final int patch;
        private final String prerelease;
        private final String build;
        private final String originalString;
        
        public Version(int major, int minor, int patch) {
            this(major, minor, patch, null, null, null);
        }
        
        public Version(int major, int minor, int patch, String prerelease, String build, String originalString) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.prerelease = prerelease;
            this.build = build;
            this.originalString = originalString;
        }
        
        public int getMajor() { return major; }
        public int getMinor() { return minor; }
        public int getPatch() { return patch; }
        public String getPrerelease() { return prerelease; }
        public String getBuild() { return build; }
        public String getOriginalString() { return originalString; }
        
        public boolean isPrerelease() { return prerelease != null && !prerelease.isEmpty(); }
        
        @Override
        public int compareTo(Version other) {
            // Compare major version
            int result = Integer.compare(this.major, other.major);
            if (result != 0) return result;
            
            // Compare minor version
            result = Integer.compare(this.minor, other.minor);
            if (result != 0) return result;
            
            // Compare patch version
            result = Integer.compare(this.patch, other.patch);
            if (result != 0) return result;
            
            // Handle prerelease versions (prerelease < release)
            if (this.prerelease == null && other.prerelease != null) {
                return 1; // this is release, other is prerelease
            }
            if (this.prerelease != null && other.prerelease == null) {
                return -1; // this is prerelease, other is release
            }
            if (this.prerelease != null && other.prerelease != null) {
                return this.prerelease.compareTo(other.prerelease);
            }
            
            return 0; // Equal
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Version version = (Version) obj;
            return major == version.major &&
                   minor == version.minor &&
                   patch == version.patch &&
                   Objects.equals(prerelease, version.prerelease);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(major, minor, patch, prerelease);
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(major).append('.').append(minor).append('.').append(patch);
            if (prerelease != null) {
                sb.append('-').append(prerelease);
            }
            if (build != null) {
                sb.append('+').append(build);
            }
            return sb.toString();
        }
    }
    
    /**
     * Compatibility levels between IDE and CLI versions.
     */
    public enum CompatibilityLevel {
        FULLY_COMPATIBLE("Fully compatible"),
        MOSTLY_COMPATIBLE("Mostly compatible - some features may not work"),
        LIMITED_COMPATIBLE("Limited compatibility - basic functions only"),
        INCOMPATIBLE("Incompatible - upgrade required");
        
        private final String description;
        
        CompatibilityLevel(String description) {
            this.description = description;
        }
        
        public String getDescription() { return description; }
    }
    
    /**
     * Result of a compatibility check.
     */
    public static class CompatibilityResult {
        private final CompatibilityLevel level;
        private final String message;
        private final Version cliVersion;
        private final Version requiredVersion;
        private final boolean canProceed;
        
        public CompatibilityResult(CompatibilityLevel level, String message, 
                                 Version cliVersion, Version requiredVersion, boolean canProceed) {
            this.level = level;
            this.message = message;
            this.cliVersion = cliVersion;
            this.requiredVersion = requiredVersion;
            this.canProceed = canProceed;
        }
        
        public CompatibilityLevel getLevel() { return level; }
        public String getMessage() { return message; }
        public Version getCliVersion() { return cliVersion; }
        public Version getRequiredVersion() { return requiredVersion; }
        public boolean canProceed() { return canProceed; }
        
        public boolean isFullyCompatible() { return level == CompatibilityLevel.FULLY_COMPATIBLE; }
        public boolean isIncompatible() { return level == CompatibilityLevel.INCOMPATIBLE; }
        
        @Override
        public String toString() {
            return String.format("Compatibility[%s: %s]", level, message);
        }
    }
    
    // Current IDE version expectations
    private static final Version MIN_SUPPORTED_VERSION = new Version(0, 1, 0);
    private static final Version RECOMMENDED_VERSION = new Version(0, 1, 0);
    private static final Version MAX_TESTED_VERSION = new Version(1, 0, 0);
    
    /**
     * Parses a version string into a Version object.
     * 
     * @param versionString The version string to parse
     * @return Optional containing the parsed Version, or empty if invalid
     */
    public static java.util.Optional<Version> parseVersion(String versionString) {
        if (versionString == null || versionString.trim().isEmpty()) {
            return java.util.Optional.empty();
        }
        
        Matcher matcher = VERSION_PATTERN.matcher(versionString.trim());
        if (!matcher.find()) {
            // Try to handle simple numeric versions like "0.1.0"
            String[] parts = versionString.trim().split("\\.");
            if (parts.length >= 3) {
                try {
                    int major = Integer.parseInt(parts[0]);
                    int minor = Integer.parseInt(parts[1]);
                    int patch = Integer.parseInt(parts[2]);
                    return java.util.Optional.of(new Version(major, minor, patch, null, null, versionString));
                } catch (NumberFormatException e) {
                    return java.util.Optional.empty();
                }
            }
            return java.util.Optional.empty();
        }
        
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            String prerelease = matcher.group(4);
            String build = matcher.group(5);
            
            return java.util.Optional.of(new Version(major, minor, patch, prerelease, build, versionString));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
    
    /**
     * Checks compatibility between IDE and CLI versions.
     * 
     * @param cliVersionString The CLI version string
     * @return CompatibilityResult indicating the level of compatibility
     */
    public static CompatibilityResult checkCompatibility(String cliVersionString) {
        java.util.Optional<Version> cliVersionOpt = parseVersion(cliVersionString);
        
        if (cliVersionOpt.isEmpty()) {
            return new CompatibilityResult(
                CompatibilityLevel.INCOMPATIBLE,
                "Could not determine CLI version: " + cliVersionString,
                null,
                RECOMMENDED_VERSION,
                false
            );
        }
        
        Version cliVersion = cliVersionOpt.get();
        
        // Check if CLI version is too old
        if (cliVersion.compareTo(MIN_SUPPORTED_VERSION) < 0) {
            return new CompatibilityResult(
                CompatibilityLevel.INCOMPATIBLE,
                String.format("CLI version %s is too old. Minimum required: %s", 
                    cliVersion, MIN_SUPPORTED_VERSION),
                cliVersion,
                MIN_SUPPORTED_VERSION,
                false
            );
        }
        
        // Check if CLI version is too new (untested)
        if (cliVersion.compareTo(MAX_TESTED_VERSION) > 0) {
            return new CompatibilityResult(
                CompatibilityLevel.LIMITED_COMPATIBLE,
                String.format("CLI version %s is newer than tested version %s. Some features may not work as expected.", 
                    cliVersion, MAX_TESTED_VERSION),
                cliVersion,
                RECOMMENDED_VERSION,
                true
            );
        }
        
        // Check if it's the recommended version
        if (cliVersion.getMajor() == RECOMMENDED_VERSION.getMajor() && 
            cliVersion.getMinor() == RECOMMENDED_VERSION.getMinor()) {
            return new CompatibilityResult(
                CompatibilityLevel.FULLY_COMPATIBLE,
                String.format("CLI version %s is fully compatible", cliVersion),
                cliVersion,
                RECOMMENDED_VERSION,
                true
            );
        }
        
        // Handle different major versions
        if (cliVersion.getMajor() != RECOMMENDED_VERSION.getMajor()) {
            if (cliVersion.getMajor() > RECOMMENDED_VERSION.getMajor()) {
                return new CompatibilityResult(
                    CompatibilityLevel.MOSTLY_COMPATIBLE,
                    String.format("CLI version %s has newer major version. Most features should work.", cliVersion),
                    cliVersion,
                    RECOMMENDED_VERSION,
                    true
                );
            } else {
                return new CompatibilityResult(
                    CompatibilityLevel.LIMITED_COMPATIBLE,
                    String.format("CLI version %s has older major version. Limited functionality available.", cliVersion),
                    cliVersion,
                    RECOMMENDED_VERSION,
                    true
                );
            }
        }
        
        // Same major version, different minor version
        return new CompatibilityResult(
            CompatibilityLevel.MOSTLY_COMPATIBLE,
            String.format("CLI version %s is mostly compatible", cliVersion),
            cliVersion,
            RECOMMENDED_VERSION,
            true
        );
    }
    
    /**
     * Gets the minimum supported CLI version.
     */
    public static Version getMinimumSupportedVersion() {
        return MIN_SUPPORTED_VERSION;
    }
    
    /**
     * Gets the recommended CLI version.
     */
    public static Version getRecommendedVersion() {
        return RECOMMENDED_VERSION;
    }
    
    /**
     * Gets the maximum tested CLI version.
     */
    public static Version getMaximumTestedVersion() {
        return MAX_TESTED_VERSION;
    }
    
    /**
     * Checks if a CLI version supports a specific feature.
     * This can be expanded as new features are added to the CLI.
     */
    public static boolean supportsFeature(Version cliVersion, String featureName) {
        if (cliVersion == null) return false;
        
        // Define feature requirements
        switch (featureName.toLowerCase()) {
            case "get-api":
                return cliVersion.compareTo(new Version(0, 1, 0)) >= 0;
            case "sim":
            case "simulate":
                return cliVersion.compareTo(new Version(0, 1, 0)) >= 0;
            case "calibrate":
                return cliVersion.compareTo(new Version(0, 1, 0)) >= 0;
            case "test":
                return cliVersion.compareTo(new Version(0, 1, 0)) >= 0;
            default:
                // Unknown feature, assume it requires the latest version
                return cliVersion.compareTo(RECOMMENDED_VERSION) >= 0;
        }
    }
}