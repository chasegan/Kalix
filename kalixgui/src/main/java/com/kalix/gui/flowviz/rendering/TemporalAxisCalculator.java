package com.kalix.gui.flowviz.rendering;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates temporal axis tick positions using intelligent boundary selection.
 * Prefers significant temporal boundaries (hours, days, weeks, months, years, etc.)
 * to provide intuitive and meaningful time axis labels.
 */
public class TemporalAxisCalculator {

    // Constants for tick calculation
    private static final int MIN_TARGET_TICKS = 3;
    private static final int MAX_TARGET_TICKS = 8;
    private static final int PIXELS_PER_TICK = 120;

    // Pre-computed temporal intervals to avoid recreation
    private static final TemporalInterval[] TEMPORAL_INTERVALS = {
        new TemporalInterval("hour", 3600000L),
        new TemporalInterval("6hour", 6 * 3600000L),
        new TemporalInterval("12hour", 12 * 3600000L),
        new TemporalInterval("day", 86400000L),
        new TemporalInterval("week", 7 * 86400000L),
        new TemporalInterval("month", 30L * 86400000L),
        new TemporalInterval("quarter", 90L * 86400000L),
        new TemporalInterval("year", 365L * 86400000L),
        new TemporalInterval("5year", 5 * 365L * 86400000L),
        new TemporalInterval("decade", 10 * 365L * 86400000L),
        new TemporalInterval("25year", 25 * 365L * 86400000L),
        new TemporalInterval("50year", 50 * 365L * 86400000L),
        new TemporalInterval("century", 100 * 365L * 86400000L),
        new TemporalInterval("250year", 250 * 365L * 86400000L),
        new TemporalInterval("500year", 500 * 365L * 86400000L),
        new TemporalInterval("millennium", 1000 * 365L * 86400000L),
        new TemporalInterval("2500year", 2500 * 365L * 86400000L),
        new TemporalInterval("5millennium", 5000 * 365L * 86400000L),
        new TemporalInterval("10millennium", 10000 * 365L * 86400000L),
        new TemporalInterval("25millennium", 25000 * 365L * 86400000L),
        new TemporalInterval("50millennium", 50000 * 365L * 86400000L),
        new TemporalInterval("100millennium", 100000 * 365L * 86400000L)
    };

    /**
     * Represents a temporal interval with its type and duration.
     */
    public static class TemporalInterval {
        final String type;
        final long durationMs;

        public TemporalInterval(String type, long durationMs) {
            this.type = type;
            this.durationMs = durationMs;
        }
    }

    /**
     * Calculates optimal temporal boundary tick positions for the given time range.
     *
     * @param startTimeMs Start time in milliseconds
     * @param endTimeMs End time in milliseconds
     * @param plotWidth Width of plot area in pixels
     * @return List of tick times in milliseconds
     */
    public List<Long> calculateTemporalBoundaryTicks(long startTimeMs, long endTimeMs, int plotWidth) {
        List<Long> ticks = new ArrayList<>();
        long timeRangeMs = endTimeMs - startTimeMs;

        // Target labels depending on plot width
        int targetTicks = Math.max(MIN_TARGET_TICKS, Math.min(MAX_TARGET_TICKS, plotWidth / PIXELS_PER_TICK));

        // Find the best interval that gives us the right number of ticks
        TemporalInterval bestInterval = TEMPORAL_INTERVALS[TEMPORAL_INTERVALS.length - 1]; // Default to longest interval
        for (TemporalInterval interval : TEMPORAL_INTERVALS) {
            long expectedTicks = timeRangeMs / interval.durationMs;
            if (expectedTicks >= targetTicks / 2 && expectedTicks <= targetTicks * 2) {
                bestInterval = interval;
                break;
            }
        }

        // Generate ticks at temporal boundaries
        LocalDateTime startDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(startTimeMs), ZoneOffset.UTC);
        LocalDateTime endDateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(endTimeMs), ZoneOffset.UTC);

        LocalDateTime current = findNextBoundary(startDateTime, bestInterval);

        while (current.isBefore(endDateTime) || current.isEqual(endDateTime)) {
            long tickTimeMs = current.toInstant(ZoneOffset.UTC).toEpochMilli();
            if (tickTimeMs >= startTimeMs && tickTimeMs <= endTimeMs) {
                ticks.add(tickTimeMs);
            }
            current = advanceToBoundary(current, bestInterval);

            // Safety check to prevent infinite loops
            if (ticks.size() > 20) break;
        }

        // Ensure we have at least 2 ticks - add start/end if needed
        if (ticks.size() < 2) {
            ticks.clear();
            ticks.add(startTimeMs);
            if (timeRangeMs > 3600000) { // If range > 1 hour, add middle point
                ticks.add(startTimeMs + timeRangeMs / 2);
            }
            ticks.add(endTimeMs);
        }

        return ticks;
    }

    private LocalDateTime findNextBoundary(LocalDateTime dateTime, TemporalInterval interval) {
        switch (interval.type) {
            case "hour":
                return dateTime.withMinute(0).withSecond(0).withNano(0).plusHours(1);
            case "6hour":
                int hour6 = (dateTime.getHour() / 6) * 6;
                LocalDateTime next6h = dateTime.withHour(hour6).withMinute(0).withSecond(0).withNano(0);
                return next6h.isAfter(dateTime) ? next6h : next6h.plusHours(6);
            case "12hour":
                int hour12 = (dateTime.getHour() / 12) * 12;
                LocalDateTime next12h = dateTime.withHour(hour12).withMinute(0).withSecond(0).withNano(0);
                return next12h.isAfter(dateTime) ? next12h : next12h.plusHours(12);
            case "day":
                return dateTime.withHour(0).withMinute(0).withSecond(0).withNano(0).plusDays(1);
            case "week":
                // Find next Monday
                LocalDateTime nextWeek = dateTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
                while (nextWeek.getDayOfWeek().getValue() != 1) { // Monday = 1
                    nextWeek = nextWeek.plusDays(1);
                }
                return nextWeek.isAfter(dateTime) ? nextWeek : nextWeek.plusWeeks(1);
            case "month":
                return dateTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0).plusMonths(1);
            case "quarter":
                int quarter = ((dateTime.getMonthValue() - 1) / 3) * 3 + 1;
                LocalDateTime nextQuarter = dateTime.withMonth(quarter).withDayOfMonth(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0);
                return nextQuarter.isAfter(dateTime) ? nextQuarter : nextQuarter.plusMonths(3);
            case "year":
                return dateTime.withDayOfYear(1).withHour(0).withMinute(0).withSecond(0).withNano(0).plusYears(1);
            case "5year":
                int year5 = (dateTime.getYear() / 5) * 5;
                LocalDateTime next5y = LocalDateTime.of(year5, 1, 1, 0, 0, 0);
                return next5y.isAfter(dateTime) ? next5y : next5y.plusYears(5);
            case "decade":
                int decade = (dateTime.getYear() / 10) * 10;
                LocalDateTime nextDecade = LocalDateTime.of(decade, 1, 1, 0, 0, 0);
                return nextDecade.isAfter(dateTime) ? nextDecade : nextDecade.plusYears(10);
            case "25year":
                int year25 = (dateTime.getYear() / 25) * 25;
                LocalDateTime next25y = LocalDateTime.of(year25, 1, 1, 0, 0, 0);
                return next25y.isAfter(dateTime) ? next25y : next25y.plusYears(25);
            case "50year":
                int year50 = (dateTime.getYear() / 50) * 50;
                LocalDateTime next50y = LocalDateTime.of(year50, 1, 1, 0, 0, 0);
                return next50y.isAfter(dateTime) ? next50y : next50y.plusYears(50);
            case "century":
                int century = (dateTime.getYear() / 100) * 100;
                LocalDateTime nextCentury = LocalDateTime.of(century, 1, 1, 0, 0, 0);
                return nextCentury.isAfter(dateTime) ? nextCentury : nextCentury.plusYears(100);
            case "250year":
                int year250 = (dateTime.getYear() / 250) * 250;
                LocalDateTime next250y = LocalDateTime.of(year250, 1, 1, 0, 0, 0);
                return next250y.isAfter(dateTime) ? next250y : next250y.plusYears(250);
            case "500year":
                int year500 = (dateTime.getYear() / 500) * 500;
                LocalDateTime next500y = LocalDateTime.of(year500, 1, 1, 0, 0, 0);
                return next500y.isAfter(dateTime) ? next500y : next500y.plusYears(500);
            case "millennium":
                int millennium = (dateTime.getYear() / 1000) * 1000;
                LocalDateTime nextMillennium = LocalDateTime.of(millennium, 1, 1, 0, 0, 0);
                return nextMillennium.isAfter(dateTime) ? nextMillennium : nextMillennium.plusYears(1000);
            case "2500year":
                int year2500 = (dateTime.getYear() / 2500) * 2500;
                LocalDateTime next2500y = LocalDateTime.of(year2500, 1, 1, 0, 0, 0);
                return next2500y.isAfter(dateTime) ? next2500y : next2500y.plusYears(2500);
            case "5millennium":
                int millennium5 = (dateTime.getYear() / 5000) * 5000;
                LocalDateTime next5millennium = LocalDateTime.of(millennium5, 1, 1, 0, 0, 0);
                return next5millennium.isAfter(dateTime) ? next5millennium : next5millennium.plusYears(5000);
            case "10millennium":
                int millennium10 = (dateTime.getYear() / 10000) * 10000;
                LocalDateTime next10millennium = LocalDateTime.of(millennium10, 1, 1, 0, 0, 0);
                return next10millennium.isAfter(dateTime) ? next10millennium : next10millennium.plusYears(10000);
            case "25millennium":
                int millennium25 = (dateTime.getYear() / 25000) * 25000;
                LocalDateTime next25millennium = LocalDateTime.of(millennium25, 1, 1, 0, 0, 0);
                return next25millennium.isAfter(dateTime) ? next25millennium : next25millennium.plusYears(25000);
            case "50millennium":
                int millennium50 = (dateTime.getYear() / 50000) * 50000;
                LocalDateTime next50millennium = LocalDateTime.of(millennium50, 1, 1, 0, 0, 0);
                return next50millennium.isAfter(dateTime) ? next50millennium : next50millennium.plusYears(50000);
            case "100millennium":
                int millennium100 = (dateTime.getYear() / 100000) * 100000;
                LocalDateTime next100millennium = LocalDateTime.of(millennium100, 1, 1, 0, 0, 0);
                return next100millennium.isAfter(dateTime) ? next100millennium : next100millennium.plusYears(100000);
            default:
                return dateTime.plusHours(1);
        }
    }

    private LocalDateTime advanceToBoundary(LocalDateTime dateTime, TemporalInterval interval) {
        switch (interval.type) {
            case "hour":
                return dateTime.plusHours(1);
            case "6hour":
                return dateTime.plusHours(6);
            case "12hour":
                return dateTime.plusHours(12);
            case "day":
                return dateTime.plusDays(1);
            case "week":
                return dateTime.plusWeeks(1);
            case "month":
                return dateTime.plusMonths(1);
            case "quarter":
                return dateTime.plusMonths(3);
            case "year":
                return dateTime.plusYears(1);
            case "5year":
                return dateTime.plusYears(5);
            case "decade":
                return dateTime.plusYears(10);
            case "25year":
                return dateTime.plusYears(25);
            case "50year":
                return dateTime.plusYears(50);
            case "century":
                return dateTime.plusYears(100);
            case "250year":
                return dateTime.plusYears(250);
            case "500year":
                return dateTime.plusYears(500);
            case "millennium":
                return dateTime.plusYears(1000);
            case "2500year":
                return dateTime.plusYears(2500);
            case "5millennium":
                return dateTime.plusYears(5000);
            case "10millennium":
                return dateTime.plusYears(10000);
            case "25millennium":
                return dateTime.plusYears(25000);
            case "50millennium":
                return dateTime.plusYears(50000);
            case "100millennium":
                return dateTime.plusYears(100000);
            default:
                return dateTime.plusHours(1);
        }
    }
}