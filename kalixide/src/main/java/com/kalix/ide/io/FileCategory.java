package com.kalix.ide.io;

import java.io.File;

/**
 * The file categories the project tree colour-codes: Kalix model files, data/input files, and
 * everything else. Classification is by filename only (no disk access), so it is safe to call
 * from the cell renderer on every repaint.
 *
 * <p>Case-insensitive, matching {@code TreeFileOperations.isZip}: common desktop filesystems
 * are case-insensitive, so {@code MODEL.INI} is just as much a model as {@code model.ini}.
 */
public enum FileCategory {

    /** A Kalix model file ({@code *.ini}). */
    MODEL,

    /** A data/input file ({@code *.csv}, {@code *.pxt}, {@code *.pxb}). */
    DATA,

    /** An eWater Source result export ({@code *.res.csv}) — Source's format, not Kalix's. */
    SOURCE_RESULT,

    /** Any other file. */
    OTHER;

    public static FileCategory of(File file) {
        return ofName(file.getName());
    }

    public static FileCategory ofName(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".ini")) {
            return MODEL;
        }
        // The double-extension trap: .res.csv must be tested before .csv (as everywhere else
        // in the codebase — dispatch, file filters, reader order).
        if (lower.endsWith(".res.csv")) {
            return SOURCE_RESULT;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".pxt") || lower.endsWith(".pxb")) {
            return DATA;
        }
        return OTHER;
    }
}
