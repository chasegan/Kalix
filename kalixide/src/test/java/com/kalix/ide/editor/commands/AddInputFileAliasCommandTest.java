package com.kalix.ide.editor.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the alias suggestion for "Add alias for file" (review #27): the sanitized
 * filename stem, never the raw path (which sanitisation would mangle).
 */
class AddInputFileAliasCommandTest {

    @Test
    void suggestsTheSanitizedFilenameStem() {
        assertEquals("mydata", AddInputFileAliasCommand.suggestAlias("./data/MyData.csv"));
        assertEquals("patterns", AddInputFileAliasCommand.suggestAlias("patterns.csv"));
        assertEquals("my_data", AddInputFileAliasCommand.suggestAlias("^/inputs/my.data.csv"));
        assertEquals("flow_2020", AddInputFileAliasCommand.suggestAlias("/abs/path/Flow 2020.pxb"));
    }

    @Test
    void handlesExtensionlessAndDotfileNames() {
        assertEquals("noext", AddInputFileAliasCommand.suggestAlias("./dir/noext"));
        assertEquals("_hidden", AddInputFileAliasCommand.suggestAlias(".hidden"));
    }
}
