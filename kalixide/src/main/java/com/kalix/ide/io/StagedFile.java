package com.kalix.ide.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes a file through a hidden sibling ({@code .name.part}) renamed into place on success, so
 * the file appears only once complete and a failure leaves any existing file as it was. The
 * sibling is created like any other file (umask permissions, unlike a temp file's 0600). The
 * staging name is reserved: a file already there is taken for a crash's leftover and replaced.
 * Pure I/O, no Swing: the file tree's Zip, Gzip and Gunzip write through it.
 */
public final class StagedFile {

    /** Targets being written now: the staging name is fixed, so two writers must not share it. */
    private static final Set<Path> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private StagedFile() {
    }

    /** Writes the content, given the staging file's stream (closing it is allowed). */
    @FunctionalInterface
    public interface Body {
        void write(OutputStream out) throws IOException;
    }

    /**
     * Writes {@code target} with {@code body}. Without {@code replace} an existing target is
     * refused; with it, the target is swapped atomically where the filesystem allows. A folder
     * is never replaced. Failures name {@code target}, never the staging file.
     */
    public static void write(Path target, boolean replace, Body body) throws IOException {
        Path to = target.toAbsolutePath().normalize();
        if (Files.isDirectory(to)) {
            throw new FileSystemException(to.toString(), null, "a folder of that name is in the way");
        }
        if (!replace && Files.exists(to)) {
            throw new FileAlreadyExistsException(to.toString(), null, "already exists");
        }
        if (!IN_FLIGHT.add(to)) {
            throw new FileSystemException(to.toString(), null, "is already being written");
        }
        Path staging = stagingFor(to);
        if (Files.isDirectory(staging)) {
            IN_FLIGHT.remove(to);
            throw new FileSystemException(to.toString(), null,
                "a folder named " + staging.getFileName() + " is in the way");
        }
        try {
            // A leftover from a crash: only this method writes it, never twice at once (above).
            Files.deleteIfExists(staging);
            try (OutputStream out = Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                body.write(out);
            }
            move(staging, to, replace);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            if (e instanceof FileSystemException fse) {
                throw namingTarget(fse, staging, to);
            }
            throw e;
        } finally {
            IN_FLIGHT.remove(to);
        }
    }

    /** The hidden sibling {@code target} is staged in. */
    static Path stagingFor(Path target) {
        Path to = target.toAbsolutePath().normalize();
        return to.resolveSibling("." + to.getFileName() + ".part");
    }

    private static void move(Path staging, Path target, boolean replace) throws IOException {
        if (!replace) {
            Files.move(staging, target);
            return;
        }
        // Without ATOMIC_MOVE, Windows deletes the target before renaming: a failed rename
        // would then lose both files.
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The user never sees the staging name, so a failure on it is reported against the target. */
    private static FileSystemException namingTarget(FileSystemException e, Path staging, Path target) {
        if (!staging.toString().equals(e.getFile())) {
            return e;
        }
        FileSystemException named = new FileSystemException(target.toString(), null, FileErrors.reason(e));
        named.initCause(e);
        return named;
    }
}
