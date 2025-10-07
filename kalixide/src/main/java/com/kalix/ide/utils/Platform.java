package com.kalix.ide.utils;

/**
 * Enumeration of supported operating system platforms.
 */
public enum Platform {
    MACOS("macOS"),
    WINDOWS("Windows"),
    LINUX("Linux"),
    UNKNOWN("Unknown");

    private final String displayName;

    Platform(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Gets the human-readable display name for this platform.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Checks if this platform supports FlatLaf window decorations.
     * Window decorations are supported on Windows and Linux, but not on macOS.
     *
     * @return true if FlatLaf window decorations are supported, false otherwise
     */
    public boolean supportsFlatLafWindowDecorations() {
        return this == WINDOWS || this == LINUX;
    }

    /**
     * Checks if this platform supports native dark title bars.
     * macOS supports this through system appearance settings.
     *
     * @return true if native dark title bars are supported, false otherwise
     */
    public boolean supportsNativeDarkTitleBars() {
        return this == MACOS;
    }

    /**
     * Checks if this platform is Unix-based (Linux or macOS).
     *
     * @return true if this is a Unix-based platform, false otherwise
     */
    public boolean isUnix() {
        return this == LINUX || this == MACOS;
    }
}