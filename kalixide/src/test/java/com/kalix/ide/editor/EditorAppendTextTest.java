package com.kalix.ide.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EnhancedTextEditor#appendText} fills a buffer on the IDE's behalf: clean, and not undoable. */
class EditorAppendTextTest {

    @Test
    void appendedTextIsPartOfTheCleanContent() {
        EnhancedTextEditor editor = new EnhancedTextEditor();
        try {
            editor.setText("one\n");
            editor.appendText("two\n");
            assertFalse(editor.isDirty());

            // An edit that is typed and then removed lands back on the appended content
            editor.getTextArea().append("x");
            assertTrue(editor.isDirty());
            int length = editor.getTextArea().getDocument().getLength();
            editor.getTextArea().replaceRange("", length - 1, length);

            assertEquals("one\ntwo\n", editor.getText());
            assertFalse(editor.isDirty(), "the appended text is part of the clean content");
        } finally {
            editor.dispose();
        }
    }

    @Test
    void appendedTextCannotBeUndone() {
        EnhancedTextEditor editor = new EnhancedTextEditor();
        try {
            editor.setText("one\n");
            editor.appendText("two\n");
            assertFalse(editor.getTextArea().canUndo());
            editor.getTextArea().undoLastAction();
            assertEquals("one\ntwo\n", editor.getText());
        } finally {
            editor.dispose();
        }
    }
}
