package com.kalix.ide.filedialog;

import java.util.List;

/**
 * An extension filter for the file dialogs: a label shown in the filter combo and the
 * dot-suffixes it accepts (case-insensitive, matched against the whole name so multi-part
 * suffixes like {@code res.csv} work). Directories always pass — filters narrow files, never
 * navigation.
 *
 * @param label      user-facing description, e.g. "Kalix models (*.ini)"
 * @param extensions accepted suffixes without the leading dot, e.g. "ini"; empty = all files
 */
public record FileDialogFilter(String label, List<String> extensions) {

    /** A filter accepting every file. */
    public static final FileDialogFilter ALL_FILES = new FileDialogFilter("All files", List.of());

    public static FileDialogFilter of(String label, String... extensions) {
        return new FileDialogFilter(label, List.of(extensions));
    }

    public boolean accepts(FsEntry entry) {
        if (entry.directory() || extensions.isEmpty()) {
            return true;
        }
        String lower = entry.name().toLowerCase();
        return extensions.stream().anyMatch(ext -> lower.endsWith("." + ext.toLowerCase()));
    }

    /** The default extension (the first one), or null for an accept-all filter. */
    public String defaultExtension() {
        return extensions.isEmpty() ? null : extensions.get(0);
    }

    @Override
    public String toString() {
        return label; // shown directly in the filter combo
    }
}
