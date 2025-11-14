package com.kalix.ide.utils;

/**
 * Utility class for platform and operating system detection.
 * Centralizes OS detection logic for cross-platform compatibility.
 */
public final class PlatformUtils {

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final Platform CURRENT_PLATFORM = detectPlatform();

    private PlatformUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Gets the current operating system platform.
     *
     * @return the current platform
     */
    public static Platform getCurrentPlatform() {
        return CURRENT_PLATFORM;
    }

    /**
     * Detects the current platform based on the OS name.
     *
     * @return the detected platform
     */
    private static Platform detectPlatform() {
        if (OS_NAME.contains("mac")) {
            return Platform.MACOS;
        } else if (OS_NAME.contains("win")) {
            return Platform.WINDOWS;
        } else if (OS_NAME.contains("linux")) {
            return Platform.LINUX;
        } else {
            return Platform.UNKNOWN;
        }
    }
}