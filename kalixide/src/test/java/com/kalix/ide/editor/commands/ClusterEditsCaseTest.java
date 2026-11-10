package com.kalix.ide.editor.commands;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The edits find a cluster by any spelling, since names differing only in case are one
 * cluster (ADR-0010 §6.2), but write exactly the name they are given, so an edit
 * brings the spellings of its nodes back together.
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
