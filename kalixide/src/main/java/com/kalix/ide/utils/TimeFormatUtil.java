package com.kalix.ide.utils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final DateTimeFormatter SPACE_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MMDD_HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Format a timestamp as an ISO datetime at second resolution.
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
     * Parses user-entered text back into an epoch-millisecond timestamp (UTC), accepting
     * whichever of the formats {@link #formatForStepSize} produces: {@code yyyy-MM-dd},
     * {@code yyyy-MM-dd'T'HH:mm:ss}, or the same datetime with a space instead of {@code 'T'}.
     *
     * @throws DateTimeParseException if the text matches none of the accepted formats
     */
    public static long parseFlexible(String text) {
        String trimmed = text.trim();
        try {
            return LocalDateTime.parse(trimmed, ISO_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Not full ISO datetime — try the next format.
        }
        try {
            return LocalDateTime.parse(trimmed, SPACE_DATETIME).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Not space-separated datetime — try date-only. Let this last attempt's
            // exception (if any) propagate, since there is no further format to fall back to.
        }
        return LocalDate.parse(trimmed, DATE_ONLY).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static LocalDateTime toUtc(long timestampMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneOffset.UTC);
    }
}
