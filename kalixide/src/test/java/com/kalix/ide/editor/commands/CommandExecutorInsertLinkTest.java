package com.kalix.ide.editor.commands;

import com.kalix.ide.model.ModelLink;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Auto-linking on node insertion: the ds_N text surgery (pure helpers) and the two atomic
 * behaviours built on it — link-from-selected-node and insert-into-selected-link. Atomicity
 * is proven by a single undo restoring the original text exactly.
 */
class CommandExecutorInsertLinkTest {

    private static final String MODEL = """
        [node.a]
        type = inflow
        loc = 0, 0
        ds_1 = b

        [node.b]
        type = inflow
        loc = 0, 50
        """;

    // --- Pure helpers ---

    @Test
    void firstFreeDsIndexSkipsUsedAndIgnoresCommentMentions() {
        assertEquals(1, CommandExecutor.firstFreeDsIndex("type = inflow\nloc = 0,0"));
        assertEquals(2, CommandExecutor.firstFreeDsIndex("ds_1 = x"));
        assertEquals(2, CommandExecutor.firstFreeDsIndex("ds_1 = x\nds_3 = y"));
        // "ds_2" inside a table comment (the splitter template) is not a ds property.
        assertEquals(1, CommandExecutor.firstFreeDsIndex(
            "table = 0, 0, # Inflow ML, ds_2 flow ML\n        100, 0,"));
    }

    @Test
    void dsLineInsertionLandsAtEndOfSectionWithFirstFreeIndex() {
        CommandExecutor.TextEdit edit = CommandExecutor.dsLineInsertion(MODEL, "a", "new_node");
        assertNotNull(edit);
        assertEquals(edit.start(), edit.end(), "must be a pure insertion");
        assertEquals("ds_2 = new_node\n", edit.replacement());
        // Lands directly after "ds_1 = b\n" (the section's last content line).
        assertEquals(MODEL.indexOf("ds_1 = b") + "ds_1 = b\n".length(), edit.start());

        assertNull(CommandExecutor.dsLineInsertion(MODEL, "no_such_node", "x"));
    }

    @Test
    void dsLineInsertionHandlesSectionAtEofWithoutNewline() {
        String text = "[node.a]\ntype = inflow";
        CommandExecutor.TextEdit edit = CommandExecutor.dsLineInsertion(text, "a", "n");
        assertNotNull(edit);
        assertTrue(edit.replacement().startsWith("\n"), "must open a new line at EOF");
    }

    @Test
    void dsValueReassignmentAddressesExactlyTheValue() {
        String text = "[node.a]\nds_1 = b  # goes to b\n\n[node.b]\ntype = inflow\n";
        CommandExecutor.TextEdit edit = CommandExecutor.dsValueReassignment(text, "a", "b", "mid");
        assertNotNull(edit);
        String applied = text.substring(0, edit.start()) + edit.replacement() + text.substring(edit.end());
        assertTrue(applied.contains("ds_1 = mid  # goes to b"),
            "only the value changes; key and comment survive: " + applied);

        assertNull(CommandExecutor.dsValueReassignment(text, "a", "zzz", "mid"));
        assertNull(CommandExecutor.dsValueReassignment(text, "b", "a", "mid"));
    }

    // --- Atomic behaviours (need a real text area) ---

    private record Fixture(RSyntaxTextArea area, CommandExecutor executor) {
    }

    private static Fixture fixture(String text) {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setText(text);
        area.discardAllEdits();
        return new Fixture(area, new CommandExecutor(area, null, null));
    }

    @Test
    void singleSelectedNodeAutoLinksToInsertedNode() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture f = fixture(MODEL);
        String templateId = NodeTemplateCatalog.templates().get(0).id();

        String newName = f.executor().insertNodeTemplateAtLocation(
            templateId, 10, 20, List.of("a"), null);

        assertNotNull(newName);
        String after = f.area().getText();
        assertTrue(after.contains("ds_2 = " + newName),
            "upstream 'a' must gain the first free ds specifier: " + after);
        assertTrue(after.contains("[node." + newName + "]"));

        f.area().undoLastAction();
        assertEquals(MODEL, f.area().getText(), "one undo must restore the original text");
    }

    @Test
    void selectedLinkSplicesInsertedNodeIntoIt() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture f = fixture(MODEL);
        String templateId = NodeTemplateCatalog.templates().get(0).id();

        String newName = f.executor().insertNodeTemplateAtLocation(
            templateId, 10, 20, List.of(), new ModelLink("a", "b", true));

        assertNotNull(newName);
        String after = f.area().getText();
        assertTrue(after.contains("ds_1 = " + newName),
            "upstream ds_1 must re-point at the new node: " + after);
        assertTrue(after.contains("ds_1 = b\n") || after.contains("ds_1 = b"),
            "the new node must link on to the old downstream: " + after);
        // The new node's section carries the onward link.
        int newSection = after.indexOf("[node." + newName + "]");
        int onwardLink = after.indexOf("ds_1 = b", newSection);
        assertTrue(newSection >= 0 && onwardLink > newSection,
            "onward ds_1 = b must sit inside the new node's section: " + after);

        f.area().undoLastAction();
        assertEquals(MODEL, f.area().getText(), "one undo must restore the original text");
    }
}
