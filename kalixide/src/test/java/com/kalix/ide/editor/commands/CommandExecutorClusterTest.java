package com.kalix.ide.editor.commands;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every cluster edit goes through one chokepoint, so however many lines it touches, one
 * undo restores the text exactly. An edit with nothing to do leaves no undo step, and an
 * invalid name is refused with nothing changed.
 */
class CommandExecutorClusterTest {

    private static final String MODEL = """
        #@cluster = farm
        [node.a]
        type = inflow
        loc = 0, 0

        #@cluster = first
        #@cluster = farm
        [node.b]
        type = inflow
        loc = 0, 50

        # about c
        [node.c]
        type = inflow
        loc = 0, 90
        """;

    private record Fixture(RSyntaxTextArea area, CommandExecutor executor) {
    }

    private static Fixture fixture() {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setText(MODEL);
        area.discardAllEdits();
        return new Fixture(area, new CommandExecutor(area, null, null));
    }

    private static void assertOneUndoRestoresTheText(Fixture f) {
        assertFalse(MODEL.equals(f.area().getText()), "the edit must have changed something");
        f.area().undoLastAction();
        assertEquals(MODEL, f.area().getText(), "one undo must restore the original text");
    }

    @Test
    void assignIsOneUndoStep() {
        Fixture f = fixture();
        assertTrue(f.executor().assignToCluster(List.of("a", "b", "c"), "dam"));
        assertTrue(f.area().getText().contains("#@cluster = dam\n[node.c]"));
        assertOneUndoRestoresTheText(f);
    }

    @Test
    void removeIsOneUndoStep() {
        Fixture f = fixture();
        assertTrue(f.executor().removeFromCluster(List.of("a", "b")));
        assertFalse(f.area().getText().contains("#@cluster"));
        assertOneUndoRestoresTheText(f);
    }

    @Test
    void renameIsOneUndoStep() {
        Fixture f = fixture();
        assertTrue(f.executor().renameCluster("farm", "dam"));
        assertFalse(f.area().getText().contains("farm"));
        assertOneUndoRestoresTheText(f);
    }

    @Test
    void disbandIsOneUndoStep() {
        Fixture f = fixture();
        assertTrue(f.executor().disbandClusters(List.of("farm")));
        assertFalse(f.area().getText().contains("#@cluster"));
        assertOneUndoRestoresTheText(f);
    }

    @Test
    void everyChangeHappensInsideOneAtomicEdit() {
        // A single undo can't prove this: the text area merges consecutive edits itself.
        CountingTextArea area = new CountingTextArea();
        area.setText(MODEL);
        area.discardAllEdits();
        area.getDocument().addDocumentListener(area);

        assertTrue(new CommandExecutor(area, null, null).assignToCluster(List.of("a", "b", "c"), "dam"));

        assertEquals(1, area.atomicEdits, "one begin and end, however many lines change");
        assertTrue(area.changesInsideAtomicEdit > 1, "the edit should touch several lines");
        assertEquals(0, area.changesOutsideAtomicEdit);
    }

    @Test
    void anEditWithNothingToDoLeavesNoUndoStep() {
        Fixture f = fixture();
        assertFalse(f.executor().renameCluster("no_such_cluster", "dam"));
        assertEquals(MODEL, f.area().getText());
        assertFalse(f.area().canUndo());
    }

    @Test
    void assigningAnInvalidNameChangesNothing() {
        Fixture f = fixture();
        assertFalse(f.executor().assignToCluster(List.of("c"), "Not Valid"));
        assertEquals(MODEL, f.area().getText());
        assertFalse(f.area().canUndo());
    }

    @Test
    void renamingToAnInvalidNameChangesNothing() {
        Fixture f = fixture();
        assertFalse(f.executor().renameCluster("farm", "Not Valid"));
        assertEquals(MODEL, f.area().getText());
        assertFalse(f.area().canUndo());
    }

    @Test
    void aNameIsTrimmedBeforeItIsWritten() {
        Fixture f = fixture();
        assertTrue(f.executor().assignToCluster(List.of("c"), "  dam  "));
        assertTrue(f.area().getText().contains("#@cluster = dam\n[node.c]"));
    }

    @Test
    void createWithAnExistingNameInAnotherCaseJoinsWithItsSpelling() {
        Fixture f = fixture();
        assertTrue(f.executor().createCluster(List.of("c"), "FARM"));
        assertTrue(f.area().getText().contains("#@cluster = farm\n[node.c]"));
        assertOneUndoRestoresTheText(f);
    }

    @Test
    void creatingWithAnInvalidNameChangesNothing() {
        Fixture f = fixture();
        assertFalse(f.executor().createCluster(List.of("c"), "Not Valid"));
        assertEquals(MODEL, f.area().getText());
        assertFalse(f.area().canUndo());
    }

    /** Sees each document change, and whether an atomic edit was open when it happened. */
    private static final class CountingTextArea extends RSyntaxTextArea implements DocumentListener {
        private int depth;
        private int atomicEdits;
        private int changesInsideAtomicEdit;
        private int changesOutsideAtomicEdit;

        @Override
        public void beginAtomicEdit() {
            if (depth++ == 0) {
                atomicEdits++;
            }
            super.beginAtomicEdit();
        }

        @Override
        public void endAtomicEdit() {
            super.endAtomicEdit();
            depth--;
        }

        private void changed() {
            if (depth > 0) {
                changesInsideAtomicEdit++;
            } else {
                changesOutsideAtomicEdit++;
            }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            // Attribute changes aren't text.
        }
    }
}
