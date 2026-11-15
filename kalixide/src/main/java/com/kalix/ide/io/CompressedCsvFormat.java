package com.kalix.ide.io;

import java.io.BufferedInputStream;
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
 * a double extension — {@code .csv.zip} or {@code .csv.gz}. The one place
 * that dispatches on which wrapper a name implies, so callers never list the
 * extensions themselves. Each wrapper's own rules and stream handling live
 * with it: {@link CsvZipFormat} (exactly one entry, the entry's name) and
 * {@link CsvGzFormat} (members, the cut-short messages).
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

    /** How a CSV is wrapped, named by its double extension (lowercase); {@link #NONE} is plain. */
    public enum Compression {
        NONE(null, null),
        ZIP(CsvZipFormat.EXTENSION, "Zipped CSV"),
        GZIP(CsvGzFormat.EXTENSION, "Gzipped CSV");

        /**
         * The compressed formats, in declaration order, which is the order dialogs list them.
         * Taken from the constants themselves, so a new format cannot be left out.
         */
        public static final List<Compression> COMPRESSED =
            Arrays.stream(values()).filter(compression -> compression != NONE).toList();

        private final String extension;
        private final String label;

        Compression(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        /** The double extension with its leading dot, lowercase; null for {@link #NONE}. */
        public String extension() {
            return extension;
        }

        /** The format's name in a file dialog, e.g. "Gzipped CSV"; null for {@link #NONE}. */
        public String label() {
            return label;
        }

        /**
         * The engine's name for the format, as {@code save_results} takes it: {@code "csv"},
         * {@code "csv.zip"} or {@code "csv.gz"} (see {@code docs/kalixcli-stdio-spec.md}).
         */
        public String engineFormat() {
            return extension == null ? "csv" : extension.substring(1);
        }

        /** The extension a file of this format is saved with: {@code .csv} when plain. */
        public String saveExtension() {
            return extension == null ? ".csv" : extension;
        }
    }

    private CompressedCsvFormat() {
    }

    /** The decompressed payload would exceed the in-memory limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /** Shown wherever a compressed Source result is refused. */
    public static final String COMPRESSED_SOURCE_RESULT_NOT_SUPPORTED =
        "Compressed Source result files (.res.csv.zip, .res.csv.gz) are not supported."
            + " Unzip or gunzip the file, then use the .res.csv.";

    /**
     * The wrapper {@code fileName} (or a path) names; {@link Compression#NONE} for anything else.
     * A compressed Source result ({@code .res.csv.zip}, {@code .res.csv.gz}) is not a compressed
     * CSV: the IDE does not read one, so it is {@link Compression#NONE} here and is refused by name
     * (see {@link #isCompressedSourceResult}).
     */
    public static Compression compressionOf(String fileName) {
        return isCompressedSourceResult(fileName) ? Compression.NONE : wrapperOf(fileName);
    }

    /** Whether the name is a Source result inside a zip or gzip wrapper, which Kalix does not read. */
    public static boolean isCompressedSourceResult(String fileName) {
        Compression wrapper = wrapperOf(fileName);
        return wrapper != Compression.NONE && wrapsSourceResult(fileName, wrapper);
    }

    /** The wrapper the name's suffix alone implies, whatever sits inside it. */
    private static Compression wrapperOf(String fileName) {
        if (fileName == null) {
            return Compression.NONE;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Compression compression : Compression.COMPRESSED) {
            if (lower.endsWith(compression.extension())) {
                return compression;
            }
        }
        return Compression.NONE;
    }

    /** Whether the name minus the wrapper's own suffix ({@code .zip}, {@code .gz}) is a {@code .res.csv}. */
    private static boolean wrapsSourceResult(String fileName, Compression wrapper) {
        int wrapperSuffix = wrapper.extension().length() - ".csv".length();
        return SourceResCsvFormat.isResCsv(fileName.substring(0, fileName.length() - wrapperSuffix));
    }

    /** Whether the file name (or path) names a compressed CSV. */
    public static boolean isCompressedCsv(String fileName) {
        return compressionOf(fileName) != Compression.NONE;
    }

    /**
     * Whether the name is a CSV, plain or compressed. A {@code .res.csv} matches too, so
     * dispatchers test it first.
     */
    public static boolean isCsvOrCompressedCsv(String fileName) {
        return fileName != null
            && (fileName.toLowerCase(Locale.ROOT).endsWith(".csv") || isCompressedCsv(fileName));
    }

    /** The compressed-CSV extension {@code fileName} ends with (lowercase), or null. */
    public static String extensionOf(String fileName) {
        return compressionOf(fileName).extension();
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
     * Opens a stream over the CSV bytes of {@code file}, decompressed when its name says so; a
     * plain name is read as it is. A zip archive must hold exactly one file (see
     * {@link CsvZipFormat#openSingleEntry}). The caller owns the stream.
     */
    public static InputStream openDecompressed(File file) throws IOException {
        return switch (compressionOf(file.getName())) {
            case ZIP -> CsvZipFormat.openSingleEntry(file);
            case GZIP -> CsvGzFormat.gunzip(new BufferedInputStream(Files.newInputStream(file.toPath())));
            case NONE -> new BufferedInputStream(Files.newInputStream(file.toPath()));
        };
    }

    /**
     * Wraps {@code raw}, the file's own bytes, so it reads the decompressed CSV.
     * For callers that must keep their own wrapper on the compressed stream
     * (the importer's progress counter); they also own any whole-file check,
     * such as a zip's single-entry rule. {@code raw} is closed if wrapping fails.
     */
    public static InputStream wrapDecompressing(String fileName, InputStream raw) throws IOException {
        return switch (compressionOf(fileName)) {
            case ZIP -> CsvZipFormat.toFirstFileEntry(raw);
            case GZIP -> CsvGzFormat.gunzip(raw);
            case NONE -> raw;
        };
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
     * name is never written plaintext; a compressed Source result name is
     * refused. Identical content writes identical
     * bytes, matching the engine's reproducibility pin (the JDK's gzip header
     * always carries MTIME 0 and no file name).
     */
    public static Writer newUtf8Writer(File file) throws IOException {
        if (isCompressedSourceResult(file.getName())) {
            // NONE below would write plain text under a compressed name.
            throw new IOException(COMPRESSED_SOURCE_RESULT_NOT_SUPPORTED);
        }
        return switch (compressionOf(file.getName())) {
            case ZIP -> CsvZipFormat.newZipUtf8Writer(file);
            case GZIP -> CsvGzFormat.newGzipUtf8Writer(file);
            case NONE -> Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        };
    }
}
