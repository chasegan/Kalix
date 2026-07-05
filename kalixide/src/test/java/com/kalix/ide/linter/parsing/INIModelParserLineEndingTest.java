package com.kalix.ide.linter.parsing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies CRLF documents parse identically to LF documents (review #44).
 * Before the fix, parse() split on "\n" only, so a blank line in a CRLF file
 * survived as "\r" and was treated as a continuation line - indented lines
 * after a blank line glued onto the previous property, diverging from LF
 * behaviour and from IniContinuation's documented rule (a blank line
 * terminates a continuation chain).
 */
class INIModelParserLineEndingTest {

    private static final String MODEL_LF = """
            [kalix]
            version = 1.0.0

            [node.storage1]
            type = storage
            loc = 10, 20
            dims = 1, 2,
               3, 4

               5, 6
            ds_1 = outlet

            [node.outlet]
            type = confluence
            loc = 30, 40

            [outputs]
            node.outlet.dsflow
            """;

    @Test
    void crlfParsesIdenticallyToLf() {
        String modelCrlf = MODEL_LF.replace("\n", "\r\n");

        INIModelParser.ParsedModel lf = INIModelParser.parse(MODEL_LF);
        INIModelParser.ParsedModel crlf = INIModelParser.parse(modelCrlf);

        assertEquals(sectionsAsString(lf), sectionsAsString(crlf));
        assertEquals(lf.getOutputReferences(), crlf.getOutputReferences());
        assertEquals(lf.getInputFiles(), crlf.getInputFiles());
    }

    @Test
    void blankLineTerminatesContinuationChainInCrlf() {
        String modelCrlf = MODEL_LF.replace("\n", "\r\n");
        INIModelParser.ParsedModel crlf = INIModelParser.parse(modelCrlf);

        INIModelParser.NodeSection node = crlf.getNodes().get("storage1");
        assertNotNull(node);

        // The chain "dims = 1, 2, / 3, 4" ends at the blank line; the later
        // indented "5, 6" is an orphan, not part of the value.
        assertEquals("1, 2, 3, 4", node.getProperties().get("dims").getValue(),
                "blank CRLF line must terminate the continuation chain");

        // The property after the orphan chain still parses on its own line.
        assertEquals("outlet", node.getProperties().get("ds_1").getValue());
    }

    private static String sectionsAsString(INIModelParser.ParsedModel model) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, INIModelParser.Section> entry : model.getSections().entrySet()) {
            INIModelParser.Section section = entry.getValue();
            sb.append('[').append(entry.getKey()).append("]@")
              .append(section.getStartLine()).append('-').append(section.getEndLine()).append('\n');
            for (INIModelParser.Property prop : section.getAllProperties()) {
                sb.append(prop.getKey()).append('=').append(prop.getValue())
                  .append('@').append(prop.getLineNumber()).append('\n');
            }
        }
        List<String> outputs = model.getOutputReferences();
        sb.append("outputs=").append(outputs).append('\n');
        return sb.toString();
    }
}
