package com.kalix.ide.filedialog;

import com.kalix.ide.preferences.PreferenceKeys;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistence for the file dialogs' navigation memory: the last accepted directory, used as
 * the default start for the next dialog. Machine-specific by design. (The sidebar's Recent
 * section deliberately reuses the main window's Recent Files / Recent Folders tracking —
 * see {@code KalixFileDialog.setRecentFoldersProvider} — rather than keeping its own list.)
 */
final class FileDialogHistory {

    private FileDialogHistory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The last accepted directory, if it still exists. */
    static Path lastDirectory() {
        String stored = PreferenceKeys.FILE_DIALOG_LAST_DIR.get();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        Path path = Path.of(stored);
        return Files.isDirectory(path) ? path : null;
    }

    /** Records an accepted directory as the next dialog's default start. */
    static void recordAccepted(Path dir) {
        if (dir != null) {
            PreferenceKeys.FILE_DIALOG_LAST_DIR.set(dir.toString());
        }
    }
}
