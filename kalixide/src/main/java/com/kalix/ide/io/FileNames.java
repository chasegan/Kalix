package com.kalix.ide.io;

import java.io.File;

/** Naming a new file beside existing ones. */
public final class FileNames {

    private FileNames() {
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
