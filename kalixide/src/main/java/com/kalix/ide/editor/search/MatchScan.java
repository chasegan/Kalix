package com.kalix.ide.editor.search;

import java.util.List;

/**
 * The outcome of scanning a document for one {@link SearchQuery}.
 *
 * @param matches   every match found, in document order, non-overlapping
 * @param truncated true if scanning stopped at the cap rather than at the end of the
 *                  document, so {@code matches} is a prefix and the total is unknown
 */
public record MatchScan(List<MatchRange> matches, boolean truncated) {

    private static final MatchScan EMPTY = new MatchScan(List.of(), false);

    public MatchScan {
        matches = List.copyOf(matches);
    }

    public static MatchScan empty() {
        return EMPTY;
    }

    public int count() {
        return matches.size();
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    /**
     * The 1-based ordinal of the match starting at {@code offset}, or 0 if no match
     * starts there. Binary search over the starts, which are ascending by construction —
     * this runs for every match the user steps onto, so it should not walk the list.
     */
    public int ordinalOfMatchStartingAt(int offset) {
        int low = 0;
        int high = matches.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int start = matches.get(mid).start();
            if (start < offset) {
                low = mid + 1;
            } else if (start > offset) {
                high = mid - 1;
            } else {
                return mid + 1;
            }
        }
        return 0;
    }

    /**
     * Index of the first match starting at or after {@code offset}, or -1 if there is
     * none — the forward step of Find Next, before any wrap is considered.
     */
    public int indexOfFirstStartingAtOrAfter(int offset) {
        int low = 0;
        int high = matches.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (matches.get(mid).start() >= offset) {
                found = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found;
    }

    /**
     * Index of the last match starting strictly before {@code offset}, or -1 if there is
     * none — the backward step of Find Previous, before any wrap is considered.
     */
    public int indexOfLastStartingBefore(int offset) {
        int low = 0;
        int high = matches.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (matches.get(mid).start() < offset) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    /** A half-open match range, in document offsets. */
    public record MatchRange(int start, int end) {
        public MatchRange {
            if (end < start) {
                throw new IllegalArgumentException("end " + end + " precedes start " + start);
            }
        }

        public int length() {
            return end - start;
        }
    }
}
