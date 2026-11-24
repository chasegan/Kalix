package com.kalix.ide.editor.commands;

import com.kalix.ide.linter.parsing.AnnotationKey;
import com.kalix.ide.model.ModelNode;
import com.kalix.ide.model.ModelParser;
import com.kalix.ide.model.NodeSectionLocator;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cluster edits as pure functions from text to edits. A node ends with at most one
 * {@code #@cluster} line: the last line is rewritten in place and any others deleted, and
 * a node with none gets one directly above its header, below any comments, so a section
 * spans the same text it did (ADR-0010 §3). Everything else stays byte for byte.
 */
class ClusterEditsTest {

    private static String body(String name) {
        return "[node." + name + "]\ntype = storage\nloc = 1, 2\n";
    }

    private static String applied(String text, List<CommandExecutor.TextEdit> edits) {
        List<CommandExecutor.TextEdit> backToFront = edits.stream()
                .sorted(Comparator.comparingInt(CommandExecutor.TextEdit::start).reversed())
                .toList();
        String result = text;
        for (CommandExecutor.TextEdit edit : backToFront) {
            result = result.substring(0, edit.start()) + edit.replacement() + result.substring(edit.end());
        }
        return result;
    }

    private static String assign(String text, String node, String cluster) {
        return applied(text, ClusterEdits.assign(text, List.of(node), cluster));
    }

    private static String clear(String text, String node) {
        return applied(text, ClusterEdits.clear(text, List.of(node)));
    }

    private static String clusterOf(String text, String node) {
        String cluster = null;
        for (ModelNode candidate : ModelParser.parse(text).getNodes()) {
            if (candidate.getName().equals(node)) {
                cluster = candidate.getCluster();
            }
        }
        return cluster;
    }

    // --- Create and Add ---

    @Test
    void aNodeWithNoLineGetsOneDirectlyAboveItsHeader() {
        assertEquals("#@cluster = farm\n" + body("a"), assign(body("a"), "a", "farm"));
    }

    @Test
    void aNewLineGoesBelowAnyCommentsSoTheSectionSpansTheSameText() {
        String text = body("a") + "\n# about b\n" + body("b");
        String edited = assign(text, "b", "farm");
        assertEquals(body("a") + "\n# about b\n#@cluster = farm\n" + body("b"), edited);

        NodeSectionLocator.NodeSection before = NodeSectionLocator.find(text, "b");
        NodeSectionLocator.NodeSection after = NodeSectionLocator.find(edited, "b");
        assertEquals(text.substring(before.start(), before.end()),
                edited.substring(after.start(), after.end()).replace("#@cluster = farm\n", ""),
                "a later Cut takes the same text plus the annotation");
    }

    @Test
    void aNewLineGoesBelowOtherAnnotations() {
        assertEquals("#@aka = Farm Dam\n# note\n#@cluster = farm\n" + body("a"),
                assign("#@aka = Farm Dam\n# note\n" + body("a"), "a", "farm"));
    }

    @Test
    void anExistingLineIsRewrittenInPlaceKeepingACommentBelowItInTheSection() {
        assertEquals("#@cluster = dam\n# about a\n" + body("a"),
                assign("#@cluster = farm\n# about a\n" + body("a"), "a", "dam"));
    }

    @Test
    void anExistingLineKeepsItsPlaceAmongOtherAnnotations() {
        assertEquals("#@cluster = dam\n#@aka = Farm Dam\n" + body("a"),
                assign("#@cluster = farm\n#@aka = Farm Dam\n" + body("a"), "a", "dam"));
    }

    @Test
    void twoLinesBecomeOneTheLastRewritten() {
        assertEquals("#@aka = x\n#@cluster = new\n" + body("a"),
                assign("#@cluster = first\n#@aka = x\n#@cluster = second\n" + body("a"), "a", "new"));
    }

    @Test
    void anInvalidLineIsReplaced() {
        String edited = assign("#@cluster = Not Valid\n" + body("a"), "a", "farm");
        assertEquals("#@cluster = farm\n" + body("a"), edited);
    }

    @Test
    void aNodeAlreadyInTheClusterNeedsNoEdit() {
        assertTrue(ClusterEdits.assign("#@cluster = farm\n" + body("a"), List.of("a"), "farm").isEmpty());
    }

    @Test
    void aNodeInTheClusterWithASecondLineLosesOnlyThatLine() {
        assertEquals("#@cluster = farm\n" + body("a"),
                assign("#@cluster = old\n#@cluster = farm\n" + body("a"), "a", "farm"));
    }

    @Test
    void assignMovesAMemberOutOfItsOldCluster() {
        String edited = assign("#@cluster = old\n" + body("a") + "#@cluster = old\n" + body("b"), "a", "new");
        assertEquals("new", clusterOf(edited, "a"));
        assertEquals("old", clusterOf(edited, "b"));
    }

    @Test
    void assignEditsEveryNamedNodeAndNoOther() {
        String text = body("a") + body("b") + body("c");
        assertEquals("#@cluster = farm\n" + body("a") + body("b") + "#@cluster = farm\n" + body("c"),
                applied(text, ClusterEdits.assign(text, List.of("a", "c"), "farm")));
    }

    @Test
    void aNodeNamedTwiceIsEditedOnce() {
        String text = body("a");
        assertEquals("#@cluster = farm\n" + body("a"),
                applied(text, ClusterEdits.assign(text, List.of("a", "a"), "farm")));
    }

    @Test
    void anUnknownNodeIsSkipped() {
        assertTrue(ClusterEdits.assign(body("a"), List.of("missing"), "farm").isEmpty());
    }

    @Test
    void aDuplicatedNodeEditsItsLastSectionAsEveryOtherEditDoes() {
        assertEquals(body("a") + "#@cluster = farm\n" + body("a"), assign(body("a") + body("a"), "a", "farm"));
    }

    @Test
    void anIndentedHeaderStillGetsALineAtColumnZero() {
        String text = "  [node.a]\ntype = storage\nloc = 1, 2\n";
        String edited = assign(text, "a", "farm");
        assertEquals("#@cluster = farm\n" + text, edited);
        assertEquals("farm", clusterOf(edited, "a"));
    }

    @Test
    void aDisabledAnnotationIsLeftAlone() {
        assertEquals("##@cluster = old\n#@cluster = farm\n" + body("a"),
                assign("##@cluster = old\n" + body("a"), "a", "farm"));
    }

    @Test
    void crlfTextGetsACrlfLine() {
        String text = body("a").replace("\n", "\r\n");
        assertEquals("#@cluster = farm\r\n" + text, assign(text, "a", "farm"));
    }

    @Test
    void crlfTextKeepsItsLineEndingsWhenRewritten() {
        String text = ("#@cluster = old\n#@cluster = older\n" + body("a")).replace("\n", "\r\n");
        assertEquals(("#@cluster = farm\n" + body("a")).replace("\n", "\r\n"), assign(text, "a", "farm"));
    }

    @Test
    void aHeaderOnTheLastLineWithNoNewlineWorks() {
        assertEquals("#@cluster = farm\n[node.a]", assign("[node.a]", "a", "farm"));
    }

    @Test
    void aHeaderOnALastCrlfLineWithNoNewlineTakesTheFilesLineEnding() {
        String text = "[kalix]\r\n[node.a]";
        assertEquals("[kalix]\r\n#@cluster = farm\r\n[node.a]", assign(text, "a", "farm"));
    }

    // --- Remove ---

    @Test
    void clearDeletesTheLineAndNothingElse() {
        assertEquals("# note\n" + body("a"), clear("# note\n#@cluster = farm\n" + body("a"), "a"));
    }

    @Test
    void clearDeletesEveryLineSoNoEarlierLineComesBack() {
        String edited = clear("#@cluster = first\n#@aka = x\n#@cluster = second\n" + body("a"), "a");
        assertEquals("#@aka = x\n" + body("a"), edited);
        assertNull(clusterOf(edited, "a"));
    }

    @Test
    void clearKeepsCrlf() {
        String text = ("#@cluster = farm\n" + body("a")).replace("\n", "\r\n");
        assertEquals(body("a").replace("\n", "\r\n"), clear(text, "a"));
    }

    @Test
    void clearOnALooseNodeNeedsNoEdit() {
        assertTrue(ClusterEdits.clear(body("a"), List.of("a")).isEmpty());
    }

    @Test
    void clearOnANodeWithOnlyAnInvalidLineRemovesIt() {
        assertEquals(body("a"), clear("#@cluster = Not Valid\n" + body("a"), "a"));
    }

    @Test
    void assignThenClearRestoresTheTextExactly() {
        String text = body("a") + "\n# about b\n#@aka = Bee\n" + body("b");
        assertEquals(text, clear(assign(text, "b", "farm"), "b"));
    }

    // --- Rename ---

    @Test
    void renameRewritesEveryMemberAndNoOtherCluster() {
        String text = "#@cluster = old\n" + body("a") + "#@cluster = other\n" + body("b")
                + "#@cluster = old\n" + body("c") + body("d");
        assertEquals("#@cluster = new\n" + body("a") + "#@cluster = other\n" + body("b")
                + "#@cluster = new\n" + body("c") + body("d"),
                applied(text, ClusterEdits.rename(text, "old", "new")));
    }

    @Test
    void renameToAnExistingNameMerges() {
        String text = "#@cluster = old\n" + body("a") + "#@cluster = target\n" + body("b");
        String edited = applied(text, ClusterEdits.rename(text, "old", "target"));
        assertEquals("target", clusterOf(edited, "a"));
        assertEquals("target", clusterOf(edited, "b"));
    }

    @Test
    void renameReachesASectionThatIsNotOnTheMap() {
        // No loc, so the parser doesn't count it; left behind, it would keep the old name.
        String text = "#@cluster = old\n[node.a]\ntype = storage\n";
        assertEquals("#@cluster = new\n[node.a]\ntype = storage\n",
                applied(text, ClusterEdits.rename(text, "old", "new")));
    }

    @Test
    void renameReachesAnEarlierSectionOfADuplicatedNode() {
        String text = "#@cluster = old\n" + body("a") + "#@cluster = old\n" + body("a");
        assertEquals("#@cluster = new\n" + body("a") + "#@cluster = new\n" + body("a"),
                applied(text, ClusterEdits.rename(text, "old", "new")));
    }

    @Test
    void renameGoesByTheLineThatCounts() {
        String text = "#@cluster = old\n#@cluster = other\n" + body("a");
        assertTrue(ClusterEdits.rename(text, "old", "new").isEmpty(), "this node is in 'other'");
    }

    @Test
    void renameLeavesAMemberWithOneLine() {
        String text = "#@cluster = first\n#@cluster = old\n" + body("a");
        assertEquals("#@cluster = new\n" + body("a"), applied(text, ClusterEdits.rename(text, "old", "new")));
    }

    @Test
    void aNodeWithAnInvalidNameIsLooseSoRenameAndDisbandDoNotReachIt() {
        String text = "#@cluster = farm-3\n" + body("a");
        assertTrue(ClusterEdits.rename(text, "farm-3", "new").isEmpty());
        assertTrue(ClusterEdits.disband(text, List.of("farm-3")).isEmpty());
    }

    @Test
    void renameOfAClusterThatDoesNotExistNeedsNoEdit() {
        assertTrue(ClusterEdits.rename(body("a"), "old", "new").isEmpty());
    }

    // --- Disband ---

    @Test
    void disbandClearsEveryMemberOfEveryNamedCluster() {
        String text = "#@cluster = one\n" + body("a") + "#@cluster = two\n" + body("b")
                + "#@cluster = three\n" + body("c") + "#@cluster = one\n" + body("d");
        assertEquals(body("a") + body("b") + "#@cluster = three\n" + body("c") + body("d"),
                applied(text, ClusterEdits.disband(text, List.of("one", "two"))));
    }

    @Test
    void disbandStepsOverALooseNode() {
        String text = body("a") + "#@cluster = one\n" + body("b");
        assertEquals(body("a") + body("b"), applied(text, ClusterEdits.disband(text, List.of("one"))));
    }

    @Test
    void disbandKeepsTheNodes() {
        String text = "#@cluster = one\n" + body("a");
        String edited = applied(text, ClusterEdits.disband(text, List.of("one")));
        assertNotNull(NodeSectionLocator.find(edited, "a"));
        assertNull(clusterOf(edited, "a"));
    }

    // --- Names ---

    @Test
    void aNameUnderTheNodeNameRuleHasNoError() {
        for (String name : List.of("a", "farm", "farm_3", "Teemburra", "0202_dam", "_spare")) {
            assertNull(ClusterEdits.nameError(name), name);
        }
    }

    @Test
    void anythingElseHasAnErrorStatingTheRule() {
        assertEquals("Missing cluster name", ClusterEdits.nameError(""));
        for (String name : List.of("farm 3", "farm.north", "farm-3")) {
            String error = ClusterEdits.nameError(name);
            assertNotNull(error, name);
            assertTrue(error.contains(AnnotationKey.CLUSTER.valueRule()), error);
            assertTrue(error.contains("'" + name + "'"), error);
        }
    }
}
