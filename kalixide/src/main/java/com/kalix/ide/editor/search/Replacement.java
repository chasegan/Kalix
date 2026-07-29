package com.kalix.ide.editor.search;

/**
 * One edit to make: swap the document text in {@code [start, end)} for {@code text}.
 *
 * <p>Offsets refer to the document as it stood when the plan was built, which is why a
 * plan must be applied back to front — see {@code ChunkedReplacer}.</p>
 */
public record Replacement(int start, int end, String text) {

    public Replacement {
        if (end < start) {
            throw new IllegalArgumentException("end " + end + " precedes start " + start);
        }
        if (text == null) {
            throw new IllegalArgumentException("text");
        }
    }

    public int length() {
        return end - start;
    }
}
