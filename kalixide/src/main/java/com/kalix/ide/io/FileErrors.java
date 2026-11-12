package com.kalix.ide.io;

import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

/**
 * Failure text a dialog can show. NIO's file exceptions often carry only a path as their
 * message ({@code AccessDeniedException("D:\data\x.csv")}), which says nothing about what went
 * wrong.
 */
public final class FileErrors {

    private FileErrors() {
    }

    /**
     * {@code "<file>: <reason>"} (or {@code "<file> -> <other>: <reason>"}, as the JDK writes a
     * move) for a file-system failure, else the message (or the type when none).
     */
    public static String describe(Throwable e) {
        if (e instanceof FileSystemException fse) {
            if (fse.getFile() == null) {
                return reason(fse);
            }
            String files = fse.getOtherFile() != null ? fse.getFile() + " -> " + fse.getOtherFile() : fse.getFile();
            return files + ": " + reason(fse);
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** The exception's own reason, or one named after its type. */
    public static String reason(FileSystemException e) {
        if (e.getReason() != null) {
            return e.getReason();
        }
        return switch (e) {
            case AccessDeniedException a -> "access denied";
            case NoSuchFileException n -> "not found";
            case FileAlreadyExistsException f -> "already exists";
            case DirectoryNotEmptyException d -> "the folder is not empty";
            case NotDirectoryException n -> "not a folder";
            default -> e.getClass().getSimpleName();
        };
    }
}
