package com.kalix.ide.utils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Centralised timestamp formatting for timeseries display, axis labels, and exports.
 *
 * <p>Two formatting modes are exposed:
 * <ul>
 *   <li>{@link #formatForStepSize} — for resolution-aware display (hover tooltips, file
 *       exports). Pick a single format for the whole series so every row/point is
 *       consistent. Driven by the timeseries' step_size.</li>
 *   <li>{@link #formatForTickInterval} — for axis tick labels. Driven by the spacing
 *       between adjacent ticks so the format stays useful as the user zooms in and out.</li>
 * </ul>
 *
 * <p>Both modes always produce UTC timestamps; the project treats timestamps as opaque
 * numeric values without timezone conversion.
 */
public final class TimeFormatUtil {

    private TimeFormatUtil() {}

    private static final long MS_PER_DAY = 86_400_000L;
    private static final long MS_PER_HOUR = 3_600_000L;
    private static final long MS_PER_MINUTE = 60_000L;

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter MMDD_HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Format a timestamp as an ISO datetime at second resolution.
     *
     * <p>Deliberately {@link #ISO_DATETIME} rather than {@code DateTimeFormatter.ISO_DATE_TIME}:
     * the latter appends a fractional-second part whenever the timestamp carries sub-second
     * millis, and {@link #parseFlexible} -- the other half of every copy/paste round trip --
     * accepts no fraction. Viewport bounds pick up arbitrary millis from pan/zoom pixel
     * arithmetic, so that combination broke the round trip on any plot the user had touched.
     * Second is the finest resolution this class renders, so the truncation costs nothing
     * visible.</p>
     */
    public static String format(LocalDateTime dateTime) {
        return dateTime.format(ISO_DATETIME);
    }

    /**
     * Convenience overload taking a millisecond-epoch timestamp; converts to UTC LocalDateTime.
     */
    public static String format(long timestampMs) {
        return format(toUtc(timestampMs));
    }

    /**
     * Format a timestamp for resolution-aware display. Daily-or-coarser series (step_size
     * is a multiple of 86400s) format as {@code yyyy-MM-dd}; sub-daily series format as
     * full ISO datetime. A step_size of 0 (unknown) defaults to date-only.
     */
    public static String formatForStepSize(LocalDateTime dateTime, long stepSeconds) {
        DateTimeFormatter formatter = (stepSeconds <= 0 || stepSeconds % 86400 == 0)
            ? DATE_ONLY : ISO_DATETIME;
        return dateTime.format(formatter);
    }

    /**
     * Convenience overload taking a millisecond-epoch timestamp; converts to UTC LocalDateTime.
     */
    public static String formatForStepSize(long timestampMs, long stepSeconds) {
        return formatForStepSize(toUtc(timestampMs), stepSeconds);
    }

    /**
     * Format a timestamp for an axis tick label. The chosen format is driven by the spacing
     * between adjacent ticks so the label has just enough resolution to distinguish them:
     * <ul>
     *   <li>≥ 1 day → {@code yyyy-MM-dd}</li>
     *   <li>≥ 1 hour → {@code MM-dd HH:mm} (date kept since hourly ticks can span days)</li>
     *   <li>≥ 1 minute → {@code HH:mm}</li>
     *   <li>otherwise → {@code HH:mm:ss}</li>
     * </ul>
     */
    public static String formatForTickInterval(LocalDateTime dateTime, long intervalMs) {
        DateTimeFormatter formatter;
        if (intervalMs >= MS_PER_DAY) {
            formatter = DATE_ONLY;
        } else if (intervalMs >= MS_PER_HOUR) {
            formatter = MMDD_HHMM;
        } else if (intervalMs >= MS_PER_MINUTE) {
            formatter = HH_MM;
        } else {
            formatter = HH_MM_SS;
        }
        return dateTime.format(formatter);
    }

    /**
     * Convenience overload taking a millisecond-epoch timestamp; converts to UTC LocalDateTime.
     */
    public static String formatForTickInterval(long timestampMs, long intervalMs) {
        return formatForTickInterval(toUtc(timestampMs), intervalMs);
    }

    /**
     * Parses user-entered date/time text into an epoch-millisecond timestamp (UTC).
     *
     * <p>Deliberately permissive about what a modeller types, while everything this class
     * <em>writes</em> stays canonical {@code yyyy-MM-dd[THH:mm:ss]}. Accepted:</p>
     * <ul>
     *   <li><b>Year first</b> — {@code 2024-02-01}, {@code 2024-2-1} (day and month may be
     *       written with or without a leading zero)</li>
     *   <li><b>Day first</b> — {@code 01-02-2024}, {@code 1-2-2024}</li>
     *   <li><b>Separators</b> — {@code -}, {@code /} or {@code .}, e.g. {@code 1/2/2024}</li>
     *   <li><b>Optional time</b> — after a {@code T} or a space: {@code HH:mm} or
     *       {@code HH:mm:ss}. Omitted means midnight.</li>
     * </ul>
     *
     * <p>Which end carries the year is what picks the order, so the two are never ambiguous:
     * a 4-digit year leads, or it trails, or the text is rejected. That is also why 2-digit
     * years are <em>not</em> accepted — {@code 01/02/03} has no reading this method could
     * defend, and guessing one silently would put a run on the wrong dates. Day-first is
     * read as day-month-year, not month-day-year: the platform's audience writes DMY, and
     * with both orders in play {@code 01/02/2024} has to mean one thing.</p>
     *
     * @throws DateTimeParseException if the text matches none of the accepted formats, or
     *         names a date that does not exist (e.g. {@code 2024-02-31})
     */
    public static long parseFlexible(String text) {
        String trimmed = text == null ? "" : text.trim();
        // 'T' is unambiguous as the date/time separator here: no accepted format spells a
        // month or day with letters, so a T can only be the ISO one.
        String[] halves = trimmed.split("[Tt\\s]+", 2);
        LocalDate date = parseDatePart(halves[0], trimmed);
        LocalTime time = halves.length > 1 ? parseTimePart(halves[1], trimmed) : LocalTime.MIDNIGHT;
        return LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * Parses the date half: three numbers separated by {@code -}, {@code /} or {@code .},
     * with the 4-digit year either leading (year-month-day) or trailing (day-month-year).
     */
    private static LocalDate parseDatePart(String datePart, String original) {
        String[] fields = datePart.split("[-/.]");
        if (fields.length != 3) {
            throw parseFailure(original);
        }
        try {
            int first = Integer.parseInt(fields[0]);
            int month = Integer.parseInt(fields[1]);
            int last = Integer.parseInt(fields[2]);
            if (fields[0].length() == 4) {
                return LocalDate.of(first, month, last);
            }
            if (fields[2].length() == 4) {
                return LocalDate.of(last, month, first);
            }
            // Neither end is a 4-digit year, so there is nothing to anchor the order on.
            throw parseFailure(original);
        } catch (NumberFormatException | DateTimeException e) {
            throw parseFailure(original);
        }
    }

    /**
     * Parses the optional time half: {@code HH:mm} or {@code HH:mm:ss}. Sub-second parts are
     * rejected — nothing in this class writes them, and second is its finest resolution.
     */
    private static LocalTime parseTimePart(String timePart, String original) {
        String[] fields = timePart.split(":");
        if (fields.length < 2 || fields.length > 3) {
            throw parseFailure(original);
        }
        try {
            int hour = Integer.parseInt(fields[0]);
            int minute = Integer.parseInt(fields[1]);
            int second = fields.length == 3 ? Integer.parseInt(fields[2]) : 0;
            return LocalTime.of(hour, minute, second);
        } catch (NumberFormatException | DateTimeException e) {
            throw parseFailure(original);
        }
    }

    private static DateTimeParseException parseFailure(String text) {
        return new DateTimeParseException(
            "Expected a date like 2024-02-01 or 01-02-2024, optionally followed by HH:mm[:ss], "
                + "but got \"" + text + "\"", text, 0);
    }

    private static LocalDateTime toUtc(long timestampMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneOffset.UTC);
    }
}
