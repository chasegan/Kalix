package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compressed CSV: the same CSV grammar inside a compression wrapper, named by
 * a double extension — {@code .csv.zip} or {@code .csv.gz}. The one place
 * that dispatches on which wrapper a name implies, so callers never list the
 * extensions themselves. Archive-specific
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

    /** How a CSV is wrapped, named by its double extension (lowercase); {@link #NONE} is plain. */
    public enum Compression {
        NONE(null, null),
        ZIP(CsvZipFormat.EXTENSION, "Zipped CSV"),
        GZIP(".csv.gz", "Gzipped CSV");

        /** The compressed formats, in the order dialogs list them. */
        public static final List<Compression> COMPRESSED = List.of(ZIP, GZIP);

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
    }

    /** GZIPInputStream's default 512-byte buffer is slow on network drives. */
    static final int GZIP_BUFFER_BYTES = 1 << 16;

    private CompressedCsvFormat() {
    }

    /** The decompressed payload would exceed the in-memory limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /** The wrapper {@code fileName} (or a path) names; {@link Compression#NONE} for anything else. */
    public static Compression compressionOf(String fileName) {
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
            case GZIP -> gunzip(new BufferedInputStream(Files.newInputStream(file.toPath())));
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
            case GZIP -> gunzip(raw);
            case NONE -> raw;
        };
    }

    /**
     * A gzip reader over {@code raw}; reads concatenated members as one stream,
     * like gzip and pandas. Unlike the engine, trailing bytes that are not a
     * gzip member end the stream rather than raising an error. The header is
     * read here, so {@code raw} is closed if it isn't gzip.
     */
    static InputStream gunzip(InputStream raw) throws IOException {
        try {
            return new NamedEof(new GZIPInputStream(raw, GZIP_BUFFER_BYTES));
        } catch (IOException e) {
            raw.close();
            throw e instanceof EOFException eof ? named(eof, "not a readable gzip file (empty or cut short)") : e;
        }
    }

    /** GZIPInputStream reports a cut-short header or trailer as an EOFException with no message. */
    private static EOFException named(EOFException e, String message) {
        if (e.getMessage() != null) {
            return e;
        }
        EOFException named = new EOFException(message);
        named.initCause(e);
        return named;
    }

    /** Gives GZIPInputStream's message-less EOFException a message a dialog can show. */
    private static final class NamedEof extends FilterInputStream {
        NamedEof(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (EOFException e) {
                throw named(e, "gzip data is cut short");
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (EOFException e) {
                throw named(e, "gzip data is cut short");
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return in.skip(n);
            } catch (EOFException e) {
                throw named(e, "gzip data is cut short");
            }
        }
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
     * bytes, matching the engine's reproducibility pin (the JDK's gzip header
     * always carries MTIME 0 and no file name).
     */
    public static Writer newUtf8Writer(File file) throws IOException {
        return switch (compressionOf(file.getName())) {
            case ZIP -> CsvZipFormat.newZipUtf8Writer(file);
            case GZIP -> newGzipUtf8Writer(file);
            case NONE -> Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        };
    }

    private static Writer newGzipUtf8Writer(File file) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()));
        try {
            return new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(out, GZIP_BUFFER_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.close();
            throw e;
        }
    }
}
