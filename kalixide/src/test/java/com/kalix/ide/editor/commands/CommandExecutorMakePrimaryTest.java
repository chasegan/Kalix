package com.kalix.ide.editor.commands;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Make primary" on a link: the ds_N text surgery that moves a link onto ds_1.
 */
class CommandExecutorMakePrimaryTest {

    /** Applies the edits in the order given, as the executor does. */
    private static String applied(String text, List<CommandExecutor.TextEdit> edits) {
        for (CommandExecutor.TextEdit edit : edits) {
            text = text.substring(0, edit.start()) + edit.replacement() + text.substring(edit.end());
        }
        return text;
    }

    private static String makePrimary(String text, String upstream, String downstream) {
        return applied(text, CommandExecutor.makePrimaryEdits(text, upstream, downstream));
    }

    @Test
    void swapsTheTargetsAndKeepsTheKeysInPlace() {
        String text = "[node.s]\ntype = storage\nds_1 = a\nds_2 = b\n";
        assertEquals("[node.s]\ntype = storage\nds_1 = b\nds_2 = a\n", makePrimary(text, "s", "b"));
    }

    @Test
    void trailingCommentsTravelWithTheirTargets() {
        String text = "[node.s]\nds_1 = a  # river\nds_2 = b  # weir\n";
        assertEquals("[node.s]\nds_1 = b  # weir\nds_2 = a  # river\n", makePrimary(text, "s", "b"));
    }

    @Test
    void linesBetweenAndAroundAreUntouched() {
        String text = "[node.s]\nds_3 = c\n# the main channel\nds_1 = a\nlevel = 2\nds_2 = b\n";
        assertEquals("[node.s]\nds_3 = a\n# the main channel\nds_1 = c\nlevel = 2\nds_2 = b\n",
            makePrimary(text, "s", "c"));
    }

    @Test
    void renumbersInPlaceWhenThereIsNoPrimary() {
        String text = "[node.s]\nds_2 = a\n  ds_10 = b  # weir\n";
        assertEquals("[node.s]\nds_2 = a\n  ds_1 = b  # weir\n", makePrimary(text, "s", "b"));
    }

    @Test
    void onlyTheUpstreamNodesSectionChanges() {
        String text = "[node.s]\nds_1 = a\nds_2 = b\n\n[node.t]\nds_1 = a\nds_2 = b\n";
        assertEquals("[node.s]\nds_1 = a\nds_2 = b\n\n[node.t]\nds_1 = b\nds_2 = a\n",
            makePrimary(text, "t", "b"));
    }

    @Test
    void windowsLineEndingsSurvive() {
        String text = "[node.s]\r\nds_1 = a\r\nds_2 = b # weir\r\n";
        assertEquals("[node.s]\r\nds_1 = b # weir\r\nds_2 = a\r\n", makePrimary(text, "s", "b"));
    }

    @Test
    void lastLineWithoutNewlineSwaps() {
        String text = "[node.s]\nds_1 = a\nds_2 = b";
        assertEquals("[node.s]\nds_1 = b\nds_2 = a", makePrimary(text, "s", "b"));
    }

    @Test
    void nothingToDoGivesNoEdits() {
        String text = "[node.s]\nds_1 = a\nds_2 = b\n";
        assertTrue(CommandExecutor.makePrimaryEdits(text, "s", "a").isEmpty(), "already primary");
        assertTrue(CommandExecutor.makePrimaryEdits(text, "s", "zzz").isEmpty(), "no such link");
        assertTrue(CommandExecutor.makePrimaryEdits(text, "no_such_node", "b").isEmpty(), "no section");
        // The same target on two outlets is one link on the map, and it is already primary.
        assertTrue(CommandExecutor.makePrimaryEdits("[node.s]\nds_1 = a\nds_2 = a\n", "s", "a").isEmpty());
    }

    // --- Atomic behaviour (needs a real text area) ---

    @Test
    void makePrimaryIsOneUndoStep() {
        String text = "[node.s]\nds_1 = a  # river\nds_2 = b  # weir\n";
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setText(text);
        area.discardAllEdits();
        CommandExecutor executor = new CommandExecutor(area, null, null);

        assertTrue(executor.makePrimary("s", "b"));
        assertEquals("[node.s]\nds_1 = b  # weir\nds_2 = a  # river\n", area.getText());

        area.undoLastAction();
        assertEquals(text, area.getText(), "one undo must restore the original text");
    }
}
