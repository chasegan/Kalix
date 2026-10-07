package com.kalix.ide.document;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * The session's error log as a tab: plain text with no file behind it, fed from the
 * in-memory {@link com.kalix.ide.utils.ErrorLog} by the host and never edited. Read-only,
 * so it is never dirty, never prompts, and the save paths refuse it; text can still be
 * selected, searched and copied. Lines wrap, because error text runs long.
 */
public class ErrorLogDocument extends TextDocument {

    public ErrorLogDocument() {
        RSyntaxTextArea textArea = getEditor().getTextArea();
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
    }

    /**
     * Brings the tab up to date with the log. Nothing else writes to the tab, so what it
     * shows is a prefix of the log unless the log was cleared meanwhile: the remainder is
     * appended, which keeps the caret and scroll position when nothing has arrived, and
     * anything else replaces the text.
     */
    public void showLog(String logged) {
        String shown = getText();
        if (logged.startsWith(shown)) {
            getEditor().appendText(logged.substring(shown.length()));
        } else {
            setText(logged);
        }
    }

    @Override
    public boolean isEditable() {
        return false;
    }

    @Override
    public String getDisplayName() {
        return "Error log";
    }
}
