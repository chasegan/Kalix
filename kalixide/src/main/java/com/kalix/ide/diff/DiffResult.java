package com.kalix.ide.diff;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.DeltaType;
import com.github.difflib.patch.Patch;
import com.github.difflib.text.DiffRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for diff computation results.
 * Holds diff rows for display and statistics about changes.
 */
public class DiffResult {
    private final List<DiffRow> rows;
    private final int totalChanges;
    private final List<Integer> changeLineNumbers;

    /**
     * Creates a DiffResult from diff rows and patch.
     *
     * @param rows The diff rows for display
     * @param patch The patch containing change deltas (can be null)
     */
    public DiffResult(List<DiffRow> rows, Patch<String> patch) {
        this.rows = rows != null ? rows : List.of();
        this.changeLineNumbers = new ArrayList<>();

        // Count changes and track line numbers
        int changes = 0;
        for (int i = 0; i < this.rows.size(); i++) {
            DiffRow row = this.rows.get(i);
            if (row.getTag() != DiffRow.Tag.EQUAL) {
                changes++;
                changeLineNumbers.add(i);
            }
        }

        this.totalChanges = changes;
    }

    /**
     * Gets the diff rows for display.
     */
    public List<DiffRow> getRows() {
        return rows;
    }

    /**
     * Gets the total number of changes (lines that differ).
     */
    public int getTotalChanges() {
        return totalChanges;
    }

    /**
     * Gets the line numbers (0-indexed) where changes occur.
     */
    public List<Integer> getChangeLineNumbers() {
        return changeLineNumbers;
    }

    /**
     * Checks if there are any changes.
     */
    public boolean hasChanges() {
        return totalChanges > 0;
    }
}
