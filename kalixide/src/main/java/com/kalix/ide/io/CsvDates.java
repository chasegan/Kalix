package com.kalix.ide.io;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The single authority on what a date looks like in Kalix CSV data: the
 * format-detection ladder and epoch conversion shared by
 * {@link TimeSeriesCsvImporter} and the data viewer's column extractor, so the
 * two can never disagree about whether a file's first column is dates.
 *
 * <p>Detection is per-value: try the ladder once on an early data value,
 * capture the winning formatter <em>and</em> whether it is date-only, then
 * parse every remaining value down the captured branch —
 * steady-state parsing throws no exceptions on well-formed files.
 */
public final class CsvDates {

    /**
     * The detected date format: the formatter plus whether values are date-only
     * (parsed via {@link LocalDate}) or full date-times. Capturing date-only-ness
     * at detection time means steady-state parsing takes the right branch directly
     * instead of throwing and catching a {@link DateTimeParseException} on every
     * row of a date-only file (the common daily case).
     */
    public record Spec(DateTimeFormatter formatter, boolean dateOnly) {
    }

    /** Sentinel for "could not parse" from {@link #parseMillis}. */
    public static final long INVALID_TS = Long.MIN_VALUE;

    /**
     * Common date/time patterns, tried in order until one succeeds.
     * Non-standard month-first formats (M/d/yyyy) are demoted to the end.
     *
     * <p>Day/month fields use single-letter tokens ({@code d}, {@code M}) rather
     * than {@code dd}/{@code MM} so that both zero-padded ("01/06/2007") and
     * unpadded ("1/06/2007") values parse — Java's parser treats {@code dd} as
     * requiring exactly two digits, which rejects single-digit days.</p>
     */
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-M-d HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("d/M/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("d/M/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("M/d/yyyy HH:mm"),
        DateTimeFormatter.ofPattern("M/d/yyyy")
    };

    private CsvDates() {
        // Utility class - no instantiation
    }

    /**
     * Runs the ladder against one value. Returns the winning {@link Spec}, or
     * {@code null} when no supported format matches.
     */
    public static Spec detect(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDateTime.parse(value, formatter);
                return new Spec(formatter, false);
            } catch (DateTimeParseException e) {
                // fall through to date-only probe
            }
            try {
                LocalDate.parse(value, formatter);
                return new Spec(formatter, true);
            } catch (DateTimeParseException e) {
                // try next formatter
            }
        }
        return null;
    }

    /**
     * Parses a date/time string to epoch millis (UTC) using the detected format,
     * taking the date-only or date-time branch directly. The opposite branch is
     * kept as a rare fallback for mixed files; unparseable values yield
     * {@link #INVALID_TS}.
     */
    public static long parseMillis(String value, Spec spec) {
        if (value.isEmpty()) {
            return INVALID_TS;
        }
        if (spec.dateOnly()) {
            try {
                return LocalDate.parse(value, spec.formatter()).toEpochDay() * 86_400_000L;
            } catch (DateTimeParseException e) {
                try {
                    return LocalDateTime.parse(value, spec.formatter())
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (DateTimeParseException e2) {
                    return INVALID_TS;
                }
            }
        } else {
            try {
                return LocalDateTime.parse(value, spec.formatter())
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e) {
                try {
                    return LocalDate.parse(value, spec.formatter()).toEpochDay() * 86_400_000L;
                } catch (DateTimeParseException e2) {
                    return INVALID_TS;
                }
            }
        }
    }
}
