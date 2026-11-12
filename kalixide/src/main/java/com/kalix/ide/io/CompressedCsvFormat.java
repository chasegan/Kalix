package com.kalix.ide.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Compressed CSV: the same CSV grammar inside a compression wrapper, named by
 * a double extension. The one place that dispatches on which wrapper a name
 * implies, so callers never list the extensions themselves. Archive-specific
 * rules (exactly one entry, the entry's name) live in {@link CsvZipFormat}.
 *
 * <p>The IDE's contract for the family: opened <b>read-only</b> and
 * decompressed <b>to memory</b>, bounded while it runs (never by trusting an
 * advertised size), and written by extension so a compressed name is never
 * written plaintext.
 *
 * <p>Every extension here is a multi-part suffix, so test it before
 * {@code .csv} (and before a plain {@code .zip}) wherever either is
 * dispatched — the {@code .res.csv} double-extension rule.
 */
public final class CompressedCsvFormat {

    /** The double extensions, lowercase. */
    private static final List<String> EXTENSIONS = List.of(CsvZipFormat.EXTENSION);

    private CompressedCsvFormat() {
    }

    /** The decompressed payload would exceed the in-memory limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /** Whether the file name (or path) names a compressed CSV. */
    public static boolean isCompressedCsv(String fileName) {
        return extensionOf(fileName) != null;
    }

    /** The compressed-CSV extension {@code fileName} ends with (lowercase), or null. */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return extension;
            }
        }
        return null;
    }

    /**
     * {@code fileName} minus its compressed-CSV extension, which counts as one
     * extension, not two ({@code rain.csv.zip} gives {@code rain}); any other
     * name comes back unchanged.
     */
    public static String stripExtension(String fileName) {
        String extension = extensionOf(fileName);
        return extension == null ? fileName : fileName.substring(0, fileName.length() - extension.length());
    }

    /**
     * Opens a stream over the decompressed CSV bytes of {@code file}. A zip
     * archive must hold exactly one file (see {@link CsvZipFormat#openSingleEntry}).
     * The caller owns the stream.
     */
    public static InputStream openDecompressed(File file) throws IOException {
        return CsvZipFormat.openSingleEntry(file);
    }

    /**
     * Wraps {@code raw}, the file's own bytes, so it reads the decompressed CSV.
     * For callers that must keep their own wrapper on the compressed stream
     * (the importer's progress counter); they also own any whole-file check,
     * such as a zip's single-entry rule. {@code raw} is closed if wrapping fails.
     */
    public static InputStream wrapDecompressing(String fileName, InputStream raw) throws IOException {
        if (CsvZipFormat.isCsvZip(fileName)) {
            return CsvZipFormat.toFirstFileEntry(raw);
        }
        return raw;
    }

    /**
     * Decompresses {@code file} into memory, refusing past {@code maxBytes}.
     * Blocking I/O that scales with the payload. The document open path
     * currently calls it synchronously, like the pre-existing whole-file text
     * read there — but unbounded by the editable-file gate, so a large file
     * stalls the open until data-document opens move off the EDT (known
     * follow-up). Peak allocation is ~2x the payload (chunks plus the
     * assembled array coexist briefly).
     *
     * @throws TooLargeException when the payload crosses {@code maxBytes}
     *         (message names both sizes, ready for a status strip or banner)
     * @throws IOException on I/O failure or a corrupt file, or a zip archive
     *         not holding exactly one file
     */
    public static byte[] decompressBounded(File file, long maxBytes) throws IOException {
        // An array is the eventual container, so its VM limit caps the cap.
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 8L);
        List<byte[]> chunks = new ArrayList<>();
        long total = 0;
        try (InputStream in = openDecompressed(file)) {
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
                    chunks.add(filled == chunk.length ? chunk : Arrays.copyOf(chunk, filled));
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

    /**
     * A UTF-8 writer for {@code file}, compressed when the name says so — the
     * extension is the single source of truth for the format, so a compressed
     * name is never written plaintext. Identical content writes identical
     * bytes, matching the engine's reproducibility pin.
     */
    public static Writer newUtf8Writer(File file) throws IOException {
        if (CsvZipFormat.isCsvZip(file.getName())) {
            return CsvZipFormat.newZipUtf8Writer(file);
        }
        return Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
    }
}
