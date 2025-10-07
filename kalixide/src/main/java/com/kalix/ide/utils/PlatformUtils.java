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
     * Checks if the current operating system is macOS.
     *
     * @return true if running on macOS, false otherwise
     */
    public static boolean isMacOS() {
        return CURRENT_PLATFORM == Platform.MACOS;
    }

    /**
     * Checks if the current operating system is Windows.
     *
     * @return true if running on Windows, false otherwise
     */
    public static boolean isWindows() {
        return CURRENT_PLATFORM == Platform.WINDOWS;
    }

    /**
     * Checks if the current operating system is Linux.
     *
     * @return true if running on Linux, false otherwise
     */
    public static boolean isLinux() {
        return CURRENT_PLATFORM == Platform.LINUX;
    }

    /**
     * Checks if the current operating system is Unix-based (Linux or macOS).
     *
     * @return true if running on a Unix-based system, false otherwise
     */
    public static boolean isUnix() {
        return CURRENT_PLATFORM.isUnix();
    }

    /**
     * Gets the raw operating system name.
     *
     * @return the OS name in lowercase
     */
    public static String getOSName() {
        return OS_NAME;
    }

    /**
     * Gets a human-readable platform description.
     *
     * @return platform description (e.g., "macOS", "Windows", "Linux", "Unknown")
     */
    public static String getPlatformDescription() {
        return CURRENT_PLATFORM.getDisplayName();
    }

    /**
     * Checks if FlatLaf window decorations are supported on the current platform.
     *
     * @return true if FlatLaf window decorations are supported, false otherwise
     */
    public static boolean supportsFlatLafWindowDecorations() {
        return CURRENT_PLATFORM.supportsFlatLafWindowDecorations();
    }

    /**
     * Checks if the current platform supports native dark title bars.
     *
     * @return true if native dark title bars are supported, false otherwise
     */
    public static boolean supportsNativeDarkTitleBars() {
        return CURRENT_PLATFORM.supportsNativeDarkTitleBars();
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