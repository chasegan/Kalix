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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip-compressed CSV ({@code .csv.zip}) — issue #409. A one-entry zip archive
 * holding a CSV, the convention pandas reads and writes natively. The IDE's
 * contract for the format: opened <b>read-only</b> and decompressed
 * <b>to memory</b>, where the ordinary data-view machinery serves it through
 * an in-memory channel.
 *
 * <p>Pandas parity: an archive must hold <b>exactly one file</b> (directory
 * entries ignored) — reading "the first of several" would silently guess, and
 * pandas refuses too. The decompression is bounded while it runs, never by
 * trusting the entry's advertised size: the byte count is enforced as the
 * stream is read, and crossing the limit aborts cleanly with
 * {@link TooLargeException}.
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

    /** The decompressed payload would exceed the in-memory limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Names of the archive's file entries (directories skipped) — the
     * exactly-one rule's evidence. Reads only the central directory.
     */
    public static List<String> fileEntryNames(File file) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /**
     * Opens a stream over the archive's single file entry, refusing an archive
     * that holds any other number (pandas parity). The returned stream is
     * positioned at the entry's decompressed bytes; the caller owns it.
     */
    public static InputStream openSingleEntry(File file) throws IOException {
        List<String> names = fileEntryNames(file);
        if (names.size() != 1) {
            throw new IOException(String.format(
                "'%s': a .csv.zip must hold exactly one file, found %d", file.getName(), names.size()));
        }
        ZipInputStream in = new ZipInputStream(
            new BufferedInputStream(Files.newInputStream(file.toPath())));
        ZipEntry entry;
        while ((entry = in.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                return in;
            }
        }
        in.close();
        throw new IOException("'" + file.getName() + "': zip entry vanished between scans");
    }

    /**
     * Decompresses the archive's single entry into memory, refusing past
     * {@code maxBytes}. Blocking I/O that scales with the payload. The
     * document open path currently calls it synchronously, like the
     * pre-existing whole-file text read there — but unbounded by the
     * editable-file gate, so a large archive stalls the open until
     * data-document opens move off the EDT (known follow-up). Peak allocation
     * is ~2x the payload (chunks plus the assembled array coexist briefly).
     *
     * @throws TooLargeException when the payload crosses {@code maxBytes}
     *         (message names both sizes, ready for a status strip or banner)
     * @throws IOException on I/O failure, a corrupt archive, or an archive
     *         not holding exactly one file
     */
    public static byte[] decompressBounded(File file, long maxBytes) throws IOException {
        // An array is the eventual container, so its VM limit caps the cap.
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 8L);
        List<byte[]> chunks = new ArrayList<>();
        long total = 0;
        try (InputStream in = openSingleEntry(file)) {
            while (true) {
                byte[] chunk = new byte[1 << 20];
                int filled = 0;
                while (filled < chunk.length) {
                    int n = in.read(chunk, filled, chunk.length - filled);
                    if (n < 0) {
                        break;
                    }
                    filled += n;
                }
                total += filled;
                if (total > limit) {
                    // Report the limit actually enforced (maxBytes may exceed
                    // the VM's array ceiling and be clamped above).
                    throw new TooLargeException(String.format(
                        "'%s' decompresses beyond the %,d MB in-memory limit",
                        file.getName(), limit / (1024 * 1024)));
                }
                if (filled > 0) {
                    chunks.add(filled == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, filled));
                }
                if (filled < chunk.length) {
                    break; // EOF
                }
            }
        }
        byte[] all = new byte[(int) total];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, all, at, chunk.length);
            at += chunk.length;
        }
        return all;
    }

    /** The single entry's name: the archive's file name minus {@code .zip} — pandas' convention. */
    public static String innerName(File file) {
        String name = file.getName();
        return name.length() > 4 ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * A UTF-8 writer for {@code file}, zip-wrapped when the name says
     * {@code .csv.zip} — the extension is the single source of truth for the
     * format, so a .csv.zip name is never written plaintext. The entry's
     * timestamp is pinned so identical content writes identical bytes,
     * matching the engine's reproducibility pin.
     */
    public static Writer newUtf8Writer(File file) throws IOException {
        if (isCsvZip(file.getName())) {
            ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file.toPath())));
            ZipEntry entry = new ZipEntry(innerName(file));
            entry.setTime(0L); // pinned: reproducible bytes for identical content
            zip.putNextEntry(entry);
            return new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        }
        return Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }
}
