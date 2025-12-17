package com.kalix.ide.utils;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-specific integration utilities using JNA.
 *
 * This class provides functionality for proper Windows integration, including:
 * - Setting the AppUserModelID for correct taskbar pinning behavior
 *
 * The AppUserModelID is crucial for Windows 7+ taskbar functionality.
 * Without it, pinned Java applications may show the default Java icon
 * instead of the application's custom icon.
 */
public final class WindowsIntegration {

    private static final Logger logger = LoggerFactory.getLogger(WindowsIntegration.class);

    /**
     * The AppUserModelID for KalixIDE.
     * This should be a unique identifier in the format: CompanyName.ProductName.SubProduct.VersionInfo
     * See: https://docs.microsoft.com/en-us/windows/win32/shell/appids
     */
    public static final String APP_USER_MODEL_ID = "Kalix.KalixIDE";

    private WindowsIntegration() {
        // Utility class - prevent instantiation
    }

    /**
     * JNA interface for Shell32.dll functions.
     */
    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);

        /**
         * Sets the Application User Model ID for the current process.
         *
         * @param appID The application user model ID string
         * @return HRESULT - S_OK (0) on success
         */
        int SetCurrentProcessExplicitAppUserModelID(WString appID);
    }

    /**
     * Initializes Windows-specific integration features.
     * This should be called early in application startup, before any UI is created.
     *
     * On non-Windows platforms, this method does nothing.
     */
    public static void initialize() {
        if (PlatformUtils.getCurrentPlatform() != Platform.WINDOWS) {
            return;
        }

        setAppUserModelID();
    }

    /**
     * Sets the AppUserModelID for the current process.
     *
     * This is essential for proper taskbar icon behavior on Windows 7+.
     * Without this, Windows may group the application with other Java apps
     * and show the wrong icon when the application is pinned to the taskbar.
     */
    private static void setAppUserModelID() {
        try {
            int result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(
                new WString(APP_USER_MODEL_ID)
            );

            if (result == 0) {
                logger.debug("Successfully set AppUserModelID to: {}", APP_USER_MODEL_ID);
            } else {
                logger.warn("Failed to set AppUserModelID, HRESULT: {}", result);
            }
        } catch (UnsatisfiedLinkError e) {
            logger.warn("Could not set AppUserModelID - Shell32 not available: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Error setting AppUserModelID: {}", e.getMessage());
        }
    }
}
