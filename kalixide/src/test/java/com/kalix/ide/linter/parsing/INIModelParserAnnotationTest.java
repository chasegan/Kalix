package com.kalix.ide.linter.parsing;

import com.kalix.ide.linter.parsing.INIModelParser.SyntaxIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The annotation lints of ADR-0010 §2 to §4: malformed, misplaced, unknown key, no effect, a repeated cluster. */
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
    void repeatedClusterLineIsReportedOnTheLaterLineNamingTheEarlier() {
        List<SyntaxIssue> issues = issues("#@cluster = north\n#@cluster = south\n[node.a]\n");
        assertOnly(INIModelParser.RULE_ANNOTATION_DUPLICATE_CLUSTER, 2, issues);
        assertEquals("Duplicate annotation 'cluster' on [node.a] (also on line 1); the last one counts",
                issues.get(0).message());
    }

    @Test
    void eachRepeatNamesTheClusterLineJustBeforeIt() {
        List<SyntaxIssue> issues = issues("#@cluster = a\n#@aka = x\n#@cluster = b\n#@cluster = c\n[node.n]\n");
        assertEquals(2, issues.size(), issues.toString());
        assertEquals(3, issues.get(0).lineNumber());
        assertTrue(issues.get(0).message().contains("(also on line 1)"), issues.get(0).message());
        assertEquals(4, issues.get(1).lineNumber());
        assertTrue(issues.get(1).message().contains("(also on line 3)"), issues.get(1).message());
    }

    @Test
    void oneClusterLinePerNodeIsClean() {
        assertTrue(issues("#@cluster = a\n[node.m]\n#@cluster = a\n[node.n]\n").isEmpty());
    }

    @Test
    void repeatedClusterLineOnANonNodeSectionOnlyHasNoEffect() {
        List<SyntaxIssue> issues = issues("#@cluster = a\n#@cluster = b\n[outputs]\n");
        assertEquals(2, issues.size(), issues.toString());
        assertTrue(issues.stream().allMatch(i -> INIModelParser.RULE_ANNOTATION_NO_EFFECT.equals(i.ruleName())),
                issues.toString());
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
