package com.kalix.ide.dataview;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

/**
 * A read-only {@link SeekableByteChannel} over a byte array — how a
 * decompressed {@code .csv.zip} is served to the {@code dataview} engine, whose
 * every reader (indexer, stores, probes) speaks this interface. The backing
 * array is shared, never copied; each channel carries only its own position.
 *
 * <p>Not thread-safe, matching file channels' practical use here: every reader
 * opens its own channel.
 */
final class InMemoryByteChannel implements SeekableByteChannel {

    private final byte[] bytes;
    private long position;
    /** Volatile: close() may come from another thread (session teardown), like a file channel's. */
    private volatile boolean open = true;

    InMemoryByteChannel(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public int read(ByteBuffer dst) throws ClosedChannelException {
        ensureOpen();
        if (position >= bytes.length) {
            return -1;
        }
        int n = (int) Math.min(dst.remaining(), bytes.length - position);
        dst.put(bytes, (int) position, n);
        position += n;
        return n;
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public long position() throws ClosedChannelException {
        ensureOpen();
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("negative position: " + newPosition);
        }
        // Positioning past the end is legal (like a file); reads there hit EOF.
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws ClosedChannelException {
        ensureOpen();
        return bytes.length;
    }

    @Override
    public SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
