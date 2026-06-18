package com.kalix.ide.io;

import java.io.IOException;

/**
 * Thrown when a {@code .res.csv} file's structure is malformed — a required marker
 * ({@code EOC}/{@code EOH}) is missing, the series count is not an integer, or the
 * header ends before the data begins.
 */
public class ResCsvFormatException extends IOException {
    public ResCsvFormatException(String message) {
        super(message);
    }
}
