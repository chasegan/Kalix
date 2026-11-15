package com.kalix.ide.io;

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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Gzip-compressed CSV ({@code .csv.gz}): a CSV in a gzip stream, as {@code gzip} and pandas
 * write it. The gzip stream handling lives here; the family's shared contract (read-only,
 * decompressed to memory, bounded) and its dispatch live in {@link CompressedCsvFormat}.
 * {@link GzipFile}, the file tree's Gzip and Gunzip, reads and writes through the same streams.
 */
public final class CsvGzFormat {

    /** The double extension, lowercase. */
    public static final String EXTENSION = ".csv.gz";

    /** The JDK's default 512-byte gzip buffer is slow on network drives. */
    static final int BUFFER_BYTES = 1 << 16;

    private CsvGzFormat() {
    }

    /**
     * A gzip reader over {@code raw}; reads concatenated members as one stream,
     * like gzip and pandas. The header is read here, so {@code raw} is closed
     * if it isn't gzip.
     *
     * <p>The engine is stricter in one case. After a complete member,
     * {@link GZIPInputStream} treats anything that is not a readable member
     * header as the end of the file. So stray bytes after the last member are
     * ignored, and a later member whose header is damaged or cut short is
     * dropped along with everything after it, both without an error. The
     * engine refuses these files. A member cut short after its header is an
     * error here too. {@code CsvGzDataViewTest} pins this behaviour.
     */
    static InputStream gunzip(InputStream raw) throws IOException {
        try {
            return new NamedEof(new GZIPInputStream(raw, BUFFER_BYTES));
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
     * A UTF-8 writer that gzips to {@code file}. The JDK's gzip header always carries MTIME 0
     * and no file name, so identical content writes identical bytes.
     */
    static Writer newGzipUtf8Writer(File file) throws IOException {
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(file.toPath()));
        try {
            return new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(out, BUFFER_BYTES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            out.close();
            throw e;
        }
    }
}
