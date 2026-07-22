package com.kalix.ide.filedialog;

import com.kalix.ide.preferences.PreferenceKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistence for the file dialogs' navigation memory: the last accepted directory (the
 * default start for the next dialog) and the recent-folders list shown in the sidebar.
 * Both are machine-specific OS preferences; pinned folders, by contrast, live in the
 * shareable file preferences (see {@link PreferenceKeys#FILE_DIALOG_PINNED}).
 */
final class FileDialogHistory {

    private static final int MAX_RECENTS = 8;

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

    /** Records an accepted directory: becomes the next default start and the top recent. */
    static void recordAccepted(Path dir) {
        if (dir == null) {
            return;
        }
        PreferenceKeys.FILE_DIALOG_LAST_DIR.set(dir.toString());
        List<String> recents = new ArrayList<>(recentPaths());
        recents.remove(dir.toString());
        recents.add(0, dir.toString());
        if (recents.size() > MAX_RECENTS) {
            recents = recents.subList(0, MAX_RECENTS);
        }
        PreferenceKeys.FILE_DIALOG_RECENTS.set(String.join("\n", recents));
    }

    /** Recent folder paths, most recent first (existence is not re-checked here). */
    static List<String> recentPaths() {
        String stored = PreferenceKeys.FILE_DIALOG_RECENTS.get();
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split("\n"));
    }
}
