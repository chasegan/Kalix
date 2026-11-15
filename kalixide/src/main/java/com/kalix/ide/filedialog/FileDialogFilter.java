package com.kalix.ide.filedialog;

import com.kalix.ide.io.CompressedCsvFormat.Compression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    /** Plain CSV, then one filter per compressed CSV format, then {@code more}. */
    public static FileDialogFilter[] csvThen(FileDialogFilter... more) {
        List<FileDialogFilter> filters = new ArrayList<>();
        filters.add(of("CSV Files (*.csv)", "csv"));
        for (Compression compression : Compression.COMPRESSED) {
            String extension = compression.extension().substring(1);
            filters.add(of(compression.label() + " (*." + extension + ")", extension));
        }
        filters.addAll(List.of(more));
        return filters.toArray(FileDialogFilter[]::new);
    }

    /**
     * {@code filters}, led by an "All Supported" filter accepting each of their extensions. An
     * extension another one already covers ({@code res.csv} by {@code csv}) is left out.
     */
    public static FileDialogFilter[] withAllSupported(FileDialogFilter... filters) {
        List<String> all = Arrays.stream(filters).flatMap(f -> f.extensions().stream()).distinct().toList();
        List<String> kept = all.stream()
            .filter(ext -> all.stream().noneMatch(other -> ext.endsWith("." + other)))
            .toList();
        String label = kept.stream().map(ext -> "*." + ext).collect(Collectors.joining(", ", "All Supported (", ")"));
        List<FileDialogFilter> result = new ArrayList<>();
        result.add(new FileDialogFilter(label, kept));
        result.addAll(List.of(filters));
        return result.toArray(FileDialogFilter[]::new);
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
