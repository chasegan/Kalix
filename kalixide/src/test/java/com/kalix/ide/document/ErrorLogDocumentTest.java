package com.kalix.ide.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErrorLogDocumentTest {

    @Test
    void isAReadOnlyFilelessTextTab() {
        ErrorLogDocument document = new ErrorLogDocument();
        try {
            assertEquals(DocumentKind.TEXT, document.getKind());
            assertNull(document.getFile());
            assertFalse(document.isEditable());
            assertFalse(document.getEditor().getTextArea().isEditable());
            assertEquals("Error log", document.getDisplayName());
        } finally {
            document.dispose();
        }
    }

    @Test
    void showLogAppendsOnlyWhatTheTabDoesNotYetShow() {
        ErrorLogDocument document = new ErrorLogDocument();
        try {
            document.showLog("one\n");
            document.showLog("one\n"); // nothing new: a no-op
            document.showLog("one\ntwo\nthree\n"); // two lines arrived between callbacks

            assertEquals("one\ntwo\nthree\n", document.getText());
            assertFalse(document.isDirty());
        } finally {
            document.dispose();
        }
    }

    @Test
    void showLogReplacesTheTextWhenTheLogWasCleared() {
        ErrorLogDocument document = new ErrorLogDocument();
        try {
            document.showLog("one\ntwo\n");
            document.showLog(""); // cleared
            assertEquals("", document.getText());

            document.showLog("three\n"); // and refilling
            assertEquals("three\n", document.getText());
            assertFalse(document.isDirty());
        } finally {
            document.dispose();
        }
    }
}
