package com.kalix.ide.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tracks how many bytes have been consumed from the underlying stream, for byte-based
 * progress reporting while streaming large files (shared by {@link SourceResCsvImporter}
 * and {@link TimeSeriesCsvImporter}).
 */
final class CountingInputStream extends FilterInputStream {

    private volatile long count = 0;

    CountingInputStream(InputStream in) {
        super(in);
    }

    long getCount() {
        return count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }
}
