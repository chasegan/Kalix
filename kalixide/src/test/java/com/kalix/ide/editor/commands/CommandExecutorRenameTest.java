package com.kalix.ide.editor.commands;

import com.kalix.ide.editor.EnhancedTextEditor;
import com.kalix.ide.linter.parsing.INIModelParser;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Verifies that node/alias rename produces range-anchored replacements (review #20):
 * renaming node {@code s} on a line like {@code ds_1 = s} must not rewrite the key
 * ({@code dx_1}), and occurrences of the name in same-line comments must survive.
 */
class CommandExecutorRenameTest {

    /** Executor with no editor: fine for the find* methods, which take the text explicitly. */
    private static CommandExecutor detector() {
        return new CommandExecutor(null, null, null);
    }

    /** Applies range-anchored replacements to the text the same way the editor does (bottom-up, right-to-left). */
    private static String apply(String text, List<CommandExecutor.TextReplacement> replacements) {
        String[] lines = text.split("\n", -1);
        replacements.stream()
            .sorted(java.util.Comparator
                .comparingInt(CommandExecutor.TextReplacement::getLineNumber)
                .thenComparingInt(CommandExecutor.TextReplacement::getStartColumn)
                .reversed())
            .forEach(r -> {
                String line = lines[r.getLineNumber() - 1];
                int start = r.getStartColumn();
                assertTrue(line.regionMatches(start, r.getOldText(), 0, r.getOldText().length()),
                    "replacement range must address the expected text: " + r.getOldText() + " @ " + start + " in '" + line + "'");
                lines[r.getLineNumber() - 1] =
                    line.substring(0, start) + r.getNewText() + line.substring(start + r.getOldText().length());
            });
        return String.join("\n", lines);
    }

    @Test
    void renamingSingleCharNodeDoesNotRewriteKeysOrComments() {
        String model = """
            [node.s]
            type = user
            loc = 0, 0

            [node.down]
            type = user
            ds_1 = s  # s feeds down; ds is short for downstream
            """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<CommandExecutor.TextReplacement> reps =
            detector().findNodeReferences(model, "s", "x", parsed);
        String result = apply(model, reps);

        String expected = """
            [node.x]
            type = user
            loc = 0, 0

            [node.down]
            type = user
            ds_1 = x  # s feeds down; ds is short for downstream
            """;
        assertEquals(expected, result);
    }

    @Test
    void renameTouchesExpressionAndOutputReferencesButNotComments() {
        String model = """
            [node.s]
            type = user

            [node.calc]
            type = user
            flow = node.s.dsflow * 2 + node.s.usflow  # uses node.s.dsflow

            [outputs]
            node.s.dsflow  # record node.s.dsflow
            """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<CommandExecutor.TextReplacement> reps =
            detector().findNodeReferences(model, "s", "sup", parsed);
        String result = apply(model, reps);

        String expected = """
            [node.sup]
            type = user

            [node.calc]
            type = user
            flow = node.sup.dsflow * 2 + node.sup.usflow  # uses node.s.dsflow

            [outputs]
            node.sup.dsflow  # record node.s.dsflow
            """;
        assertEquals(expected, result);
    }

    @Test
    void renameLeavesLookAlikeNamesAlone() {
        // "storage" must not be renamed when renaming "s"; nor "node.storage." refs.
        String model = """
            [node.s]
            type = user

            [node.storage]
            type = user
            ds_1 = s
            flow = node.storage.dsflow + node.s.dsflow
            """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<CommandExecutor.TextReplacement> reps =
            detector().findNodeReferences(model, "s", "z", parsed);
        String result = apply(model, reps);

        String expected = """
            [node.z]
            type = user

            [node.storage]
            type = user
            ds_1 = z
            flow = node.storage.dsflow + node.z.dsflow
            """;
        assertEquals(expected, result);
    }

    @Test
    void aliasRenameIsAnchoredToTheKeyNotThePath() {
        // The path contains the alias text; only the key must be renamed.
        String model = """
            [inputs]
            rain = ./rain/rain.csv

            [node.a]
            type = user
            flow = data.rain.by_name.rainfall
            """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<CommandExecutor.TextReplacement> reps =
            detector().findInputFileAliasReferences(model, "rain", "climate", parsed);
        String result = apply(model, reps);

        String expected = """
            [inputs]
            climate = ./rain/rain.csv

            [node.a]
            type = user
            flow = data.climate.by_name.rainfall
            """;
        assertEquals(expected, result);
    }

    @Test
    void inputFilePathRenameMatchesThePathPortionOfAnAliasedLine() {
        String model = """
            [inputs]
            data = ./data

            [outputs]
            data.data.by_index.1
            """;
        INIModelParser.ParsedModel parsed = INIModelParser.parse(model);

        List<CommandExecutor.TextReplacement> reps =
            detector().findInputFileReferences(model, "./data", "./data2", parsed);
        String result = apply(model, reps);

        // The alias key "data" (same text as the path stem) must be untouched.
        assertTrue(result.contains("data = ./data2"), result);
    }

    @Test
    void endToEndRenameThroughTheEditorAppliesRanges() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");

        String model = """
            [node.s]
            type = user

            [node.down]
            type = user
            ds_1 = s # s
            """;
        EnhancedTextEditor editor = new EnhancedTextEditor();
        editor.setText(model);

        CommandExecutor executor =
            new CommandExecutor(editor.getTextArea(), null, editor::applyAtomicReplacements);
        boolean ok = executor.renameNode("s", "x", INIModelParser.parse(model));

        assertTrue(ok);
        String expected = """
            [node.x]
            type = user

            [node.down]
            type = user
            ds_1 = x # s
            """;
        assertEquals(expected, editor.getText());
        editor.dispose();
    }
}
