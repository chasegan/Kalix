package com.kalix.ide.editor.commands;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The edits find a cluster by any spelling, since names differing only in case are one
 * cluster (ADR-0010 §6.2), and bring the spellings of the nodes they touch back together.
 * Joining an existing cluster, by Create or by a Rename that merges, writes that
 * cluster's spelling; a Rename within one cluster writes the name as typed.
 */
class ClusterEditsCaseTest {

    private static String body(String name) {
        return "[node." + name + "]\ntype = storage\nloc = 1, 2\n";
    }

    private static String member(String cluster, String name) {
        return "#@cluster = " + cluster + "\n" + body(name);
    }

    private static String applied(String text, List<CommandExecutor.TextEdit> edits) {
        String result = text;
        for (CommandExecutor.TextEdit edit : edits.stream()
                .sorted(Comparator.comparingInt(CommandExecutor.TextEdit::start).reversed())
                .toList()) {
            result = result.substring(0, edit.start()) + edit.replacement() + result.substring(edit.end());
        }
        return result;
    }

    @Test
    void renameReachesEverySpelling() {
        String text = member("north", "a") + member("North", "b") + member("south", "c");
        assertEquals(member("east", "a") + member("east", "b") + member("south", "c"),
                applied(text, ClusterEdits.rename(text, "north", "east")));
    }

    @Test
    void renameFindsTheClusterFromAnySpelling() {
        String text = member("north", "a") + member("North", "b");
        assertEquals(member("east", "a") + member("east", "b"),
                applied(text, ClusterEdits.rename(text, "NORTH", "east")));
    }

    @Test
    void aRenameThatOnlyChangesCaseRewritesEveryMemberToTheNewSpelling() {
        String text = member("north", "a") + member("North", "b");
        assertEquals(member("NORTH", "a") + member("NORTH", "b"),
                applied(text, ClusterEdits.rename(text, "north", "NORTH")));
    }

    @Test
    void aRenameThatMergesWritesTheTargetsSpelling() {
        // As Create: joining an existing cluster never rewrites its members' spelling.
        String text = member("south", "a") + member("north", "b") + member("south", "c");
        assertEquals(member("north", "a") + member("north", "b") + member("north", "c"),
                applied(text, ClusterEdits.rename(text, "south", "NORTH")));
    }

    @Test
    void aRenameThatMergesTakesTheTargetsFirstMention() {
        String text = member("North", "a") + member("north", "b") + member("south", "c");
        assertEquals(member("North", "a") + member("north", "b") + member("North", "c"),
                applied(text, ClusterEdits.rename(text, "south", "NORTH")));
    }

    @Test
    void aRenameToANewNameWritesItAsTyped() {
        String text = member("south", "a") + member("north", "b");
        assertEquals(member("East", "a") + member("north", "b"),
                applied(text, ClusterEdits.rename(text, "south", "East")));
    }

    @Test
    void aRenameToTheShownSpellingTidiesOnlyTheStrayLines() {
        String text = member("North", "a") + member("north", "b");
        List<CommandExecutor.TextEdit> edits = ClusterEdits.rename(text, "North", "North");
        assertEquals(1, edits.size(), edits.toString());
        assertEquals(member("North", "a") + member("North", "b"), applied(text, edits));
    }

    @Test
    void disbandReachesEverySpelling() {
        String text = member("north", "a") + member("North", "b") + member("south", "c");
        assertEquals(body("a") + body("b") + member("south", "c"),
                applied(text, ClusterEdits.disband(text, List.of("north"))));
    }

    @Test
    void createWithAnExistingNameInAnotherCaseWritesTheClusterSpelling() {
        // Never a rewrite of the other members: a mistyped case must not bulk out the diff.
        String text = member("north", "a") + body("b");
        assertEquals(member("north", "a") + member("north", "b"),
                applied(text, ClusterEdits.create(text, List.of("b"), "North")));
    }

    @Test
    void createWithANewNameWritesItAsTyped() {
        String text = member("north", "a") + body("b");
        assertEquals(member("north", "a") + member("South", "b"),
                applied(text, ClusterEdits.create(text, List.of("b"), "South")));
    }

    @Test
    void createTakesTheSpellingOfTheFirstMention() {
        String text = member("North", "a") + member("north", "b") + body("c");
        assertEquals(member("North", "a") + member("North", "b") + member("North", "c"),
                applied(text, ClusterEdits.create(text, List.of("b", "c"), "NORTH")));
    }

    @Test
    void assignRewritesALineSpelledDifferently() {
        // The same cluster, but the line is rewritten so the spellings come back together.
        String text = member("North", "a");
        assertEquals(member("north", "a"), applied(text, ClusterEdits.assign(text, List.of("a"), "north")));
    }
}
