package com.kalix.ide.dataview;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where a data view's bytes come from: a file on disk, or an in-memory copy
 * (a decompressed {@code .csv.gz}). The whole {@code dataview} engine — the
 * indexer, both stores, every probe — reads through independently positioned
 * channels from one of these, so serving a view from memory needs no other
 * change anywhere in the stack.
 */
public interface ByteSource {

    /** Opens an independent read channel (its own position). */
    SeekableByteChannel openChannel() throws IOException;

    /** Current size in bytes. A file can grow between calls; memory never does. */
    long size() throws IOException;

    /**
     * The source's identity for display, logging and neighbouring-file lookups.
     * For an in-memory source this is the file the bytes came from (the
     * {@code .csv.gz} itself), not where they live now.
     */
    Path path();

    /** A source reading the file at {@code file} directly. */
    static ByteSource ofFile(Path file) {
        return new ByteSource() {
            @Override
            public SeekableByteChannel openChannel() throws IOException {
                return Files.newByteChannel(file, StandardOpenOption.READ);
            }

            @Override
            public long size() throws IOException {
                return Files.size(file);
            }

            @Override
            public Path path() {
                return file;
            }
        };
    }

    /**
     * A source serving {@code bytes} from memory, identified as
     * {@code displayPath}. Fixed-size: it never reports growth, so the live
     * tail (append-resume) naturally never engages and any change to the
     * underlying file rebuilds the session instead.
     */
    static ByteSource ofMemory(Path displayPath, byte[] bytes) {
        return new ByteSource() {
            @Override
            public SeekableByteChannel openChannel() {
                return new InMemoryByteChannel(bytes);
            }

            @Override
            public long size() {
                return bytes.length;
            }

            @Override
            public Path path() {
                return displayPath;
            }
        };
    }
}
