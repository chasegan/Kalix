package com.kalix.ide.linter.ui;

import com.kalix.ide.linter.model.ValidationResult;
import com.kalix.ide.linter.model.ValidationRule;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies error navigation against a deliberately out-of-order ValidationResult
 * (review #42): validators append issues in validator order, not line order, so
 * next/previous must sort by line before scanning, and wraparound must land on
 * the first/last error by line - not by insertion position.
 *
 * Constructs a Swing text area, so display-gated like {@code EditorDisposalTest}.
 */
class ErrorNavigationManagerTest {

    private RSyntaxTextArea textArea;
    private ErrorNavigationManager navigator;
    private ValidationResult result;

    @BeforeEach
    void setUp() {
        textArea = new RSyntaxTextArea();
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            text.append("line ").append(i).append('\n');
        }
        textArea.setText(text.toString());
        navigator = new ErrorNavigationManager(textArea);

        // Errors deliberately NOT in line order (insertion order: 12, 3, 18, 7),
        // plus a warning that must be ignored by error navigation.
        result = new ValidationResult();
        result.addIssue(12, "error on 12", ValidationRule.Severity.ERROR, "r");
        result.addIssue(3, "error on 3", ValidationRule.Severity.ERROR, "r");
        result.addIssue(5, "warning on 5", ValidationRule.Severity.WARNING, "r");
        result.addIssue(18, "error on 18", ValidationRule.Severity.ERROR, "r");
        result.addIssue(7, "error on 7", ValidationRule.Severity.ERROR, "r");
    }

    private void placeCaretAtLine(int oneBasedLine) throws Exception {
        textArea.setCaretPosition(textArea.getLineStartOffset(oneBasedLine - 1));
    }

    private int caretLine() {
        return textArea.getCaretLineNumber() + 1;
    }

    @Test
    void nextErrorVisitsErrorsInLineOrder() throws Exception {
        placeCaretAtLine(1);

        navigator.goToNextError(result);
        assertEquals(3, caretLine());

        navigator.goToNextError(result);
        assertEquals(7, caretLine(), "must not skip line 7 (inserted last)");

        navigator.goToNextError(result);
        assertEquals(12, caretLine());

        navigator.goToNextError(result);
        assertEquals(18, caretLine());
    }

    @Test
    void nextErrorWrapsToFirstErrorByLine() throws Exception {
        placeCaretAtLine(19); // past the last error
        navigator.goToNextError(result);
        assertEquals(3, caretLine(), "wrap must land on the lowest-line error, not insertion order's first (12)");
    }

    @Test
    void previousErrorVisitsErrorsInReverseLineOrder() throws Exception {
        placeCaretAtLine(20);

        navigator.goToPreviousError(result);
        assertEquals(18, caretLine());

        navigator.goToPreviousError(result);
        assertEquals(12, caretLine());

        navigator.goToPreviousError(result);
        assertEquals(7, caretLine());

        navigator.goToPreviousError(result);
        assertEquals(3, caretLine());
    }

    @Test
    void previousErrorWrapsToLastErrorByLine() throws Exception {
        placeCaretAtLine(2); // before the first error
        navigator.goToPreviousError(result);
        assertEquals(18, caretLine(), "wrap must land on the highest-line error, not insertion order's last (7)");
    }

    @Test
    void navigationIgnoresWarnings() throws Exception {
        placeCaretAtLine(3);
        navigator.goToNextError(result);
        assertEquals(7, caretLine(), "warning on line 5 must be skipped");
    }

    @Test
    void noErrorsIsANoOp() throws Exception {
        placeCaretAtLine(4);
        ValidationResult warningsOnly = new ValidationResult();
        warningsOnly.addIssue(9, "warning", ValidationRule.Severity.WARNING, "r");

        navigator.goToNextError(warningsOnly);
        assertEquals(4, caretLine());

        navigator.goToPreviousError(null);
        assertEquals(4, caretLine());
    }
}
