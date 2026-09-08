package com.kalix.ide.dataview;

/**
 * The format-neutral contract of the unified Find, shared by every tabular
 * data view — the CSV session answers it with a streamed byte scan, the Pixie
 * session with an in-memory pass, and a future format implements
 * {@link FindableData} and inherits the whole Find UX unchanged.
 *
 * <p>The split of responsibilities is deliberate: a format decides <em>what
 * matches</em> (its own text, its own date handling); the {@link Collector}
 * decides what a match <em>means for navigation</em> (ordinals, totals, the
 * boundary matches around the origin), so no two formats can disagree about
 * "3 of 17" or where Find Previous lands.
 */
public final class DataFind {

    private DataFind() {
    }

    /**
     * What the Find is looking for. {@code dateMillis} is non-null when the
     * query itself parses as a date (via the shared {@code CsvDates} ladder):
     * date cells then also match by <em>parsed</em> date — at day granularity
     * for a date-only query — so "1/6/2020" finds "2020-06-01" however the
     * source spells or stores it.
     */
    public record Spec(String query, boolean matchCase, boolean wholeCell,
                       boolean inDates, boolean inValues, Long dateMillis, boolean dateOnly) {
    }

    /** One matching cell (data row, model column) and its 1-based ordinal among all matches. */
    public record CellRef(long row, int column, int ordinal) {
    }

    /**
     * Everything one pass can say about a spec's matches: the total, the
     * boundary matches for navigating from the origin in either direction
     * (wrap decisions belong to the caller), and — when the query is a date —
     * the first row at or after it: the "nearest later date" fallback landing
     * ({@code -1} when none).
     */
    public record Scan(int total, CellRef firstOverall, CellRef lastOverall,
                       CellRef firstAfter, CellRef lastBefore, long nearestDateRow) {
    }

    /** Pre-folds the query per the spec's case rule (trimmed; lowercased unless Match case). */
    public static String foldNeedle(Spec spec) {
        String trimmed = spec.query().trim();
        return spec.matchCase() ? trimmed : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    /** The shared text rule: trimmed cell vs the pre-folded needle, whole-cell or contains. */
    public static boolean textMatches(String cell, String foldedNeedle, Spec spec) {
        if (cell.isEmpty() || foldedNeedle.isEmpty()) {
            return false;
        }
        String haystack = spec.matchCase() ? cell : cell.toLowerCase(java.util.Locale.ROOT);
        return spec.wholeCell() ? haystack.equals(foldedNeedle) : haystack.contains(foldedNeedle);
    }

    /** Whether a parsed timestamp matches the spec's date query at the query's granularity. */
    public static boolean dateMatches(long parsedMillis, Spec spec) {
        if (spec.dateMillis() == null) {
            return false;
        }
        return spec.dateOnly()
            ? Math.floorDiv(parsedMillis, 86_400_000L) == Math.floorDiv(spec.dateMillis(), 86_400_000L)
            : parsedMillis == spec.dateMillis();
    }

    /**
     * Folds matches into a {@link Scan}. The implementation offers every
     * matching cell in ascending (row, column) order; the collector tracks
     * totals, ordinals and the navigation boundaries around the fixed origin.
     * The exact origin cell is deliberately in neither boundary — Find must
     * strictly advance, matching the editor's far-edge-of-selection rule.
     */
    public static final class Collector {

        private final long fromRow;
        private final int fromColumn;
        private int total;
        private CellRef firstOverall;
        private CellRef lastOverall;
        private CellRef firstAfter;
        private CellRef lastBefore;
        private long nearestDateRow = -1;

        public Collector(long fromRow, int fromColumn) {
            this.fromRow = fromRow;
            this.fromColumn = fromColumn;
        }

        /** Offers one MATCHING cell; call in ascending (row, column) order. */
        public void offer(long row, int column) {
            total++;
            CellRef ref = new CellRef(row, column, total);
            if (firstOverall == null) {
                firstOverall = ref;
            }
            lastOverall = ref;
            if (row > fromRow || (row == fromRow && column > fromColumn)) {
                if (firstAfter == null) {
                    firstAfter = ref;
                }
            } else if (row < fromRow || column < fromColumn) {
                lastBefore = ref;
            }
        }

        /** Records the first row at or after a date query (only the first sticks). */
        public void offerNearestDate(long row) {
            if (nearestDateRow < 0) {
                nearestDateRow = row;
            }
        }

        public Scan finish() {
            return new Scan(total, firstOverall, lastOverall, firstAfter, lastBefore, nearestDateRow);
        }
    }
}
