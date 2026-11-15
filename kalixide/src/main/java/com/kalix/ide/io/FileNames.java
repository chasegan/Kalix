package com.kalix.ide.io;

import java.io.File;

/** Naming a new file beside existing ones. */
public final class FileNames {

    private FileNames() {
    }

    /**
     * The whole extension of {@code fileName} with its leading dot, in the name's own case, or
     * "" when it has none. A compressed CSV ({@code .csv.zip}, {@code .csv.gz}) and a Source
     * result ({@code .res.csv}) carry one extension, not two; any other name is cut at its
     * last dot. A leading dot is not an extension ({@code .gitignore} has none).
     */
    public static String suffixOf(String fileName) {
        String compressed = CompressedCsvFormat.extensionOf(fileName);
        int length;
        if (compressed != null) {
            length = compressed.length();
        } else if (SourceResCsvFormat.isResCsv(fileName)) {
            length = SourceResCsvFormat.EXTENSION.length();
        } else {
            int dot = fileName.lastIndexOf('.');
            length = dot > 0 ? fileName.length() - dot : 0;
        }
        // A name that is nothing but the extension keeps it as its stem.
        return length < fileName.length() ? fileName.substring(fileName.length() - length) : "";
    }

    /**
     * {@code stem + suffix} in {@code dir}, or the first free {@code stem (n) + suffix} when
     * taken. The counter goes before the whole suffix, so {@code flows (1).csv.gz} stays a
     * {@code .csv.gz}.
     */
    public static File free(File dir, String stem, String suffix) {
        File file = new File(dir, stem + suffix);
        for (int i = 1; file.exists(); i++) {
            file = new File(dir, stem + " (" + i + ")" + suffix);
        }
        return file;
    }
}
