package com.kalix.ide.io;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Gzip-compressed CSV ({@code .csv.gz}) — issue #374. The IDE's contract for
 * the format: opened <b>read-only</b> and decompressed <b>to memory</b>, where
 * the ordinary data-view machinery serves it through an in-memory channel.
 *
 * <p>The decompression is bounded while it runs, not by trusting the gzip
 * trailer's ISIZE field (which is only the size mod 2³², so a 4.2GB payload
 * advertises itself as 0.2GB): the byte count is enforced as the stream is
 * read, and crossing the limit aborts cleanly with {@link TooLargeException}.
 *
 * <p>Like {@link SourceResCsvFormat}, this is the one holder of the extension
 * constant and its test — a multi-part suffix, so it must be tested before
 * {@code .csv} wherever both are dispatched (the {@code .res.csv}
 * double-extension rule).
 */
public final class CsvGzFormat {

    /** The double extension, lowercase. */
    public static final String EXTENSION = ".csv.gz";

    private CsvGzFormat() {
    }

    /** Whether the file name (or path) names a gzip-compressed CSV. */
    public static boolean isCsvGz(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    /** The decompressed payload would exceed the in-memory limit. */
    public static final class TooLargeException extends IOException {
        public TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Decompresses the whole file into memory, refusing past {@code maxBytes}.
     * Blocking I/O — never call on the EDT.
     *
     * @throws TooLargeException when the payload crosses {@code maxBytes}
     *         (message names both sizes, ready for a status strip or banner)
     * @throws IOException on I/O failure or a corrupt gzip stream
     */
    public static byte[] decompressBounded(File file, long maxBytes) throws IOException {
        // An array is the eventual container, so its VM limit caps the cap.
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 8L);
        List<byte[]> chunks = new ArrayList<>();
        long total = 0;
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(Files.newInputStream(file.toPath())))) {
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
                    throw new TooLargeException(String.format(
                        "'%s' decompresses beyond the %,d MB in-memory limit",
                        file.getName(), maxBytes / (1024 * 1024)));
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
}
