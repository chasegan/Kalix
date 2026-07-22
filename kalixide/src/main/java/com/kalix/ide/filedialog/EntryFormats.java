package com.kalix.ide.filedialog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Display formatting for directory entries: human-readable sizes and modified timestamps.
 * Kept dumb and static — formatting only, no I/O.
 */
final class EntryFormats {

    private static final DateTimeFormatter MODIFIED =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private EntryFormats() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** "—" for directories; otherwise B / KB / MB / GB with one decimal above bytes. */
    static String size(FsEntry entry) {
        if (entry.directory()) {
            return "—";
        }
        long b = entry.size();
        if (b < 1024) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.1f GB", mb / 1024.0);
    }

    static String modified(FsEntry entry) {
        return MODIFIED.format(Instant.ofEpochMilli(entry.lastModified()));
    }
}
