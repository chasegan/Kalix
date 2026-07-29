package com.kalix.ide.editor.search;

/**
 * Unwinds a scan that has been superseded.
 *
 * <p>Control flow, not an error — so it carries no message, no stack trace and no cause.
 * Capturing a stack trace here would be pure cost on a path taken every time the user
 * presses another key.</p>
 *
 * <p>It exists because {@link java.util.regex.Matcher} cannot be interrupted from
 * outside: once {@code find()} is running, the only way back out is for the
 * {@link CharSequence} it is reading to refuse to continue. {@link DocumentCharSequence}
 * throws this from {@code charAt}, and the matcher unwinds through it.</p>
 */
public final class SearchCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SearchCancelledException() {
        super(null, null, false, false);
    }
}
