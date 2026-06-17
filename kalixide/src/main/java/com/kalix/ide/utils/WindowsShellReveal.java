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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reveal-and-select a file in Windows Explorer via the Shell API
 * ({@code SHParseDisplayName} + {@code SHOpenFolderAndSelectItems}) — the same mechanism Explorer
 * and VS Code use — bound through the Foreign Function &amp; Memory API (stable since Java 22).
 *
 * <p>Unlike the {@code explorer /select,<path>} command-line hack, this takes the path as a proper
 * wide (UTF-16) string and a shell item id list, so spaces and Unicode work, with no console
 * window. It is defensive: any binding or call failure returns {@code false} so the caller can
 * fall back to opening the containing folder. Windows-only — the native libraries are looked up
 * lazily, so loading this class on other platforms is harmless until
 * {@link #selectInExplorer(String)} is actually invoked.</p>
 */
final class WindowsShellReveal {

    private static final Logger logger = LoggerFactory.getLogger(WindowsShellReveal.class);

    /** COINIT_APARTMENTTHREADED — SHOpenFolderAndSelectItems expects an STA thread. */
    private static final int COINIT_APARTMENTTHREADED = 0x2;

    private static volatile Bindings bindings;
    private static volatile boolean unavailable;

    private WindowsShellReveal() {
    }

    /**
     * Opens Explorer with {@code path} selected. Returns {@code true} on success, {@code false} if
     * the Shell API could not be bound or the call failed — the caller should then fall back to
     * opening the containing folder.
     *
     * <p>The COM sequence runs on a short-lived STA thread so the caller's (typically the EDT) COM
     * apartment is left untouched; we wait briefly for the result so the fallback stays reliable.</p>
     */
    static boolean selectInExplorer(String path) {
        Bindings b = bindings();
        if (b == null) {
            return false;
        }
        AtomicBoolean ok = new AtomicBoolean(false);
        Thread t = new Thread(() -> ok.set(b.select(path)), "reveal-in-explorer");
        t.setDaemon(true);
        t.start();
        try {
            t.join(3000);  // the call is quick; this is just a safety cap before falling back
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    private static Bindings bindings() {
        if (bindings == null && !unavailable) {
            synchronized (WindowsShellReveal.class) {
                if (bindings == null && !unavailable) {
                    try {
                        bindings = new Bindings();
                    } catch (Throwable t) {
                        unavailable = true;
                        logger.warn("Windows Shell reveal API unavailable; will open folder instead", t);
                    }
                }
            }
        }
        return bindings;
    }

    /** Lazily-built native method handles, valid for the process lifetime. */
    private static final class Bindings {

        private final MethodHandle coInitializeEx;
        private final MethodHandle coUninitialize;
        private final MethodHandle shParseDisplayName;
        private final MethodHandle shOpenFolderAndSelectItems;
        private final MethodHandle ilFree;

        Bindings() {
            Linker linker = Linker.nativeLinker();
            // The global arena never frees its symbols; shell32/ole32 stay loaded for the process.
            Arena arena = Arena.global();
            SymbolLookup ole32 = SymbolLookup.libraryLookup("ole32.dll", arena);
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32.dll", arena);

            coInitializeEx = linker.downcallHandle(ole32.find("CoInitializeEx").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            coUninitialize = linker.downcallHandle(ole32.find("CoUninitialize").orElseThrow(),
                FunctionDescriptor.ofVoid());
            shParseDisplayName = linker.downcallHandle(shell32.find("SHParseDisplayName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            shOpenFolderAndSelectItems = linker.downcallHandle(
                shell32.find("SHOpenFolderAndSelectItems").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            ilFree = linker.downcallHandle(shell32.find("ILFree").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        }

        /** Runs SHParseDisplayName + SHOpenFolderAndSelectItems for {@code path}. Never throws. */
        boolean select(String path) {
            try (Arena arena = Arena.ofConfined()) {
                // Wide (UTF-16LE) path; allocate() zero-fills, so the trailing two bytes are the
                // UTF-16 NUL terminator.
                byte[] utf16 = path.getBytes(StandardCharsets.UTF_16LE);
                MemorySegment widePath = arena.allocate(utf16.length + 2L);
                MemorySegment.copy(utf16, 0, widePath, ValueLayout.JAVA_BYTE, 0, utf16.length);

                MemorySegment pidlOut = arena.allocate(ValueLayout.ADDRESS); // PIDLIST_ABSOLUTE*

                int coHr = (int) coInitializeEx.invoke(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
                // S_OK / S_FALSE: we initialised (or bumped the ref count), so balance it.
                // RPC_E_CHANGED_MODE (< 0): a different apartment already exists — don't uninitialise.
                boolean balanceCom = coHr >= 0;
                try {
                    int hr = (int) shParseDisplayName.invoke(
                        widePath, MemorySegment.NULL, pidlOut, 0, MemorySegment.NULL);
                    if (hr < 0) {
                        return false;
                    }
                    MemorySegment pidl = pidlOut.get(ValueLayout.ADDRESS, 0);
                    try {
                        // A fully-qualified item PIDL with zero children selects that item.
                        int hr2 = (int) shOpenFolderAndSelectItems.invoke(
                            pidl, 0, MemorySegment.NULL, 0);
                        return hr2 >= 0;
                    } finally {
                        ilFree.invoke(pidl);
                    }
                } finally {
                    if (balanceCom) {
                        coUninitialize.invoke();
                    }
                }
            } catch (Throwable t) {
                logger.warn("Native Explorer select failed", t);
                return false;
            }
        }
    }
}
