package com.kalix.ide.io;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Failure text for the tree's dialogs: never a bare path, never "null". */
class FileErrorsTest {

    @Test
    void aFileSystemFailureSaysWhatWentWrong() {
        assertEquals("x.csv: access denied", FileErrors.describe(new AccessDeniedException("x.csv")));
        assertEquals("x.csv: is busy", FileErrors.describe(new FileSystemException("x.csv", null, "is busy")));
    }

    @Test
    void aMoveNamesBothFilesLikeTheJdk() {
        // The reason belongs to the move, not to the source alone.
        assertEquals("a.csv -> b.csv: already exists",
            FileErrors.describe(new FileAlreadyExistsException("a.csv", "b.csv", null)));
    }

    @Test
    void anOtherFailureKeepsItsMessageOrNamesItsType() {
        assertEquals("disk full", FileErrors.describe(new IOException("disk full")));
        assertEquals("java.io.EOFException", FileErrors.describe(new EOFException()));
    }
}
