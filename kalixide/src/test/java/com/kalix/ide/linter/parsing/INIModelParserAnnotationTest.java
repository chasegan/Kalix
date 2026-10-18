package com.kalix.ide.linter.parsing;

import com.kalix.ide.linter.parsing.INIModelParser.SyntaxIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The annotation lints of ADR-0010 §2 to §4: malformed, misplaced, unknown key, no effect. */
class INIModelParserAnnotationTest {

    private static List<SyntaxIssue> issues(String model) {
        return INIModelParser.parse(model).getSyntaxIssues();
    }

    private static void assertOnly(String rule, int line, List<SyntaxIssue> issues) {
        assertEquals(1, issues.size(), issues.toString());
        assertEquals(rule, issues.get(0).ruleName());
        assertEquals(line, issues.get(0).lineNumber());
    }

    @Test
    void wellFormedAnnotationsOnANodeAreClean() {
        assertTrue(issues("""
                #@aka = Teemburra Dam
                #@cluster = teemburra
                #@loc = 1, 2
                #@pycalib.bounds = {"x": 1}
                [node.teemburra]
                type = storage
                """).isEmpty());
    }

    @Test
    void regionMayIntroduceAnySection() {
        assertTrue(issues("#@region = Inputs\n[data]\nrain.csv\n").isEmpty());
    }

    @Test
    void annotationWithoutKeyEqualsValueIsMalformed() {
        assertOnly(INIModelParser.RULE_ANNOTATION_MALFORMED, 1, issues("#@aka\n[node.a]\n"));
        assertOnly(INIModelParser.RULE_ANNOTATION_MALFORMED, 1, issues("#@ = x\n[node.a]\n"));
    }

    @Test
    void annotationAboveACodeLineIsMisplaced() {
        assertOnly(INIModelParser.RULE_ANNOTATION_MISPLACED, 2, issues("[node.a]\n#@aka = x\ntype = gauge\n"));
    }

    @Test
    void annotationAtEndOfTextIsMisplaced() {
        assertOnly(INIModelParser.RULE_ANNOTATION_MISPLACED, 3, issues("[node.a]\ntype = gauge\n#@aka = x\n"));
    }

    @Test
    void unknownBareKeyIsReported() {
        List<SyntaxIssue> issues = issues("#@nickname = x\n[node.a]\n");
        assertOnly(INIModelParser.RULE_ANNOTATION_UNKNOWN_KEY, 1, issues);
        assertEquals("Unknown annotation 'nickname'", issues.get(0).message());
    }

    @Test
    void wrongCaseKeyIsUnknownAndTheMessageSaysSo() {
        List<SyntaxIssue> issues = issues("#@AKA = x\n[node.a]\n");
        assertOnly(INIModelParser.RULE_ANNOTATION_UNKNOWN_KEY, 1, issues);
        assertTrue(issues.get(0).message().endsWith("annotation keys are lowercase: 'aka'"));
    }

    @Test
    void dottedKeyBelongsToAnotherToolAndIsNotChecked() {
        assertTrue(issues("#@mytool.anything = {\"x\": 1}\n[outputs]\n").isEmpty());
    }

    @Test
    void nodeKeyOnANonNodeSectionHasNoEffect() {
        List<SyntaxIssue> issues = issues("#@aka = The outputs\n[outputs]\nnode.a.dsflow\n");
        assertOnly(INIModelParser.RULE_ANNOTATION_NO_EFFECT, 1, issues);
        assertEquals("'aka' has no effect on [outputs]", issues.get(0).message());
    }

    @Test
    void disabledAnnotationIsAComment() {
        assertTrue(issues("# #@nickname = x\n##@aka\n[node.a]\n").isEmpty());
    }

    @Test
    void malformedHeaderStillConsumesTheAnnotations() {
        List<SyntaxIssue> issues = issues("#@aka = x\n[node.a\n");
        assertEquals(1, issues.size(), issues.toString());
        assertEquals(INIModelParser.RULE_MALFORMED_SECTION_HEADER, issues.get(0).ruleName());
    }
}
