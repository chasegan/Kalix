package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip-compressed CSV ({@code .csv.zip}) — issue #409. A one-entry zip archive
 * holding a CSV, the convention pandas reads and writes natively. The
 * archive's own rules live here; the family's shared contract (read-only,
 * decompressed to memory, bounded) and its dispatch live in
 * {@link CompressedCsvFormat}.
 *
 * <p>Pandas parity: an archive must hold <b>exactly one file</b> (directory
 * entries ignored) — reading "the first of several" would silently guess, and
 * pandas refuses too.
 *
 * <p>Like {@link SourceResCsvFormat}, this is the one holder of the extension
 * constant and its test — a multi-part suffix, so it must be tested before
 * both {@code .csv} and (crucially) plain {@code .zip} wherever either is
 * dispatched (the {@code .res.csv} double-extension rule).
 */
public final class CsvZipFormat {

    /** The double extension, lowercase. */
    public static final String EXTENSION = ".csv.zip";

    private CsvZipFormat() {
    }

    /** Whether the file name (or path) names a zip-compressed CSV. */
    public static boolean isCsvZip(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /**
     * Opens a stream over the archive's single file entry, refusing an archive
     * that holds any other number (pandas parity). The returned stream is
     * positioned at the entry's decompressed bytes; the caller owns it.
     */
    public static InputStream openSingleEntry(File file) throws IOException {
        List<String> names = ZipArchive.fileEntryNames(file);
        if (names.size() != 1) {
            throw new IOException(String.format(
                "'%s': a .csv.zip must hold exactly one file, found %d", file.getName(), names.size()));
        }
        return toFirstFileEntry(new BufferedInputStream(Files.newInputStream(file.toPath())));
    }

    /**
     * Wraps {@code raw}, a zip archive's bytes, positioned at its first file
     * entry (directory entries skipped). The stream is only handed to the
     * caller on success; every throw path (corrupt local headers included)
     * closes it here.
     */
    static InputStream toFirstFileEntry(InputStream raw) throws IOException {
        ZipInputStream in = new ZipInputStream(raw);
        try {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    return in;
                }
            }
            throw new IOException("zip archive holds no file entry");
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    /** The single entry's name: the archive's file name minus {@code .zip} — pandas' convention. */
    public static String innerName(File file) {
        String name = file.getName();
        return name.length() > 4 ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * A UTF-8 writer for a one-entry zip archive at {@code file}, the entry
     * named by {@link #innerName}. The entry's timestamp is pinned so identical
     * content writes identical bytes, matching the engine's reproducibility pin.
     */
    static Writer newZipUtf8Writer(File file) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(
            new BufferedOutputStream(Files.newOutputStream(file.toPath())));
        ZipEntry entry = new ZipEntry(innerName(file));
        entry.setTime(0L); // pinned: reproducible bytes for identical content
        zip.putNextEntry(entry);
        return new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
    }
}
