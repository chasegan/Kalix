package com.kalix.ide.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Windows-specific process setup, through the Foreign Function and Memory API like
 * {@link WindowsShellReveal}.
 *
 * <p>Sets the AppUserModelID for the process so that a pinned KalixIDE shows its own taskbar
 * icon rather than being grouped with other Java applications under the Java icon.
 */
public final class WindowsIntegration {

    private static final Logger logger = LoggerFactory.getLogger(WindowsIntegration.class);

    /**
     * The AppUserModelID for KalixIDE, in the CompanyName.ProductName form. See
     * https://docs.microsoft.com/en-us/windows/win32/shell/appids
     */
    public static final String APP_USER_MODEL_ID = "Kalix.KalixIDE";

    private WindowsIntegration() {
    }

    /**
     * Applies the Windows-specific setup. Call early in startup, before any window is created.
     * On other platforms this does nothing.
     */
    public static void initialize() {
        if (PlatformUtils.getCurrentPlatform() != Platform.WINDOWS) {
            return;
        }
        int hr = setAppUserModelId(APP_USER_MODEL_ID);
        if (hr == 0) {
            logger.debug("Set AppUserModelID to {}", APP_USER_MODEL_ID);
        } else {
            logger.warn("Could not set AppUserModelID, HRESULT {}", Integer.toHexString(hr));
        }
    }

    /**
     * Calls shell32's SetCurrentProcessExplicitAppUserModelID with {@code appId}. Windows only.
     *
     * @return the HRESULT, 0 (S_OK) on success; -1 if the function could not be bound or called
     */
    static int setAppUserModelId(String appId) {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32.dll", arena);
            MethodHandle setId = linker.downcallHandle(
                shell32.find("SetCurrentProcessExplicitAppUserModelID").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // PCWSTR: UTF-16LE with a NUL terminator, which allocateFrom appends.
            MemorySegment wideId = arena.allocateFrom(appId, StandardCharsets.UTF_16LE);
            return (int) setId.invoke(wideId);
        } catch (Throwable t) {
            logger.warn("Could not call SetCurrentProcessExplicitAppUserModelID", t);
            return -1;
        }
    }
}
