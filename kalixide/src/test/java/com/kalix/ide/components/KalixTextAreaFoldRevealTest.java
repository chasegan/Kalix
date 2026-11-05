package com.kalix.ide.components;

import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A programmatic caret move into a collapsed fold opens the fold and lands where
 * it was sent; without this the library parks the caret on the next visible line.
 * Collapsing a fold around the caret still works, since the caret is parked on
 * the fold's own first line, which is not hidden.
 */
class KalixTextAreaFoldRevealTest {

    /** A region holding two sections, the first with a multi-line value. */
    private static final String MODEL = """
            #@region = A
            [node.a]
            kc = 1,
               2
            loc = 1, 2

            [node.b]
            type = gauge
            """;

    private static KalixIniTextArea textArea() {
        KalixIniTextArea textArea = new KalixIniTextArea();
        textArea.setText(MODEL);
        return textArea;
    }

    @Test
    void movingTheCaretIntoACollapsedRegionOpensItAndLandsThere() throws BadLocationException {
        KalixIniTextArea textArea = textArea();
        Fold region = textArea.getFoldManager().getFold(0);
        region.setCollapsed(true);

        int target = textArea.getLineStartOffset(7);
        textArea.setCaretPosition(target);

        assertFalse(region.isCollapsed());
        assertEquals(target, textArea.getCaretPosition());
    }

    @Test
    void selectingInsideNestedCollapsedFoldsOpensEveryOneOfThem() throws BadLocationException {
        KalixIniTextArea textArea = textArea();
        FoldManager folds = textArea.getFoldManager();
        Fold region = folds.getFold(0);
        Fold sectionA = region.getChild(0);
        Fold kc = sectionA.getChild(0);
        kc.setCollapsed(true);
        sectionA.setCollapsed(true);
        region.setCollapsed(true);

        int start = textArea.getLineStartOffset(3);
        textArea.select(start, start + 4);

        assertFalse(region.isCollapsed());
        assertFalse(sectionA.isCollapsed());
        assertFalse(kc.isCollapsed());
        assertEquals(start, textArea.getSelectionStart());
        assertEquals(start + 4, textArea.getSelectionEnd());
    }

    @Test
    void movingToACollapsedFoldsOwnFirstLineOpensOnlyWhatHidesThatLine() throws BadLocationException {
        KalixIniTextArea textArea = textArea();
        Fold region = textArea.getFoldManager().getFold(0);
        Fold sectionB = region.getChild(1);
        sectionB.setCollapsed(true);
        region.setCollapsed(true);

        textArea.setCaretPosition(textArea.getLineStartOffset(6));

        assertFalse(region.isCollapsed());
        assertTrue(sectionB.isCollapsed(), "the header line is visible once the region is open");
    }

    @Test
    void undoOpensTheFoldHidingTheUndoneEdit() throws BadLocationException, InterruptedException, InvocationTargetException {
        KalixIniTextArea textArea = textArea();
        Fold region = textArea.getFoldManager().getFold(0);
        textArea.setCaretPosition(textArea.getLineEndOffset(4) - 1);
        textArea.replaceSelection(" # note");
        region.setCollapsed(true);

        // On the event thread, as in the application: off it, the caret defers its move.
        SwingUtilities.invokeAndWait(textArea::undoLastAction);

        assertFalse(region.isCollapsed());
        assertEquals(4, textArea.getCaretLineNumber());
    }

    @Test
    void collapsingAFoldAroundTheCaretStillCollapsesIt() throws BadLocationException {
        KalixIniTextArea textArea = textArea();
        Fold region = textArea.getFoldManager().getFold(0);
        textArea.setCaretPosition(textArea.getLineStartOffset(4));

        region.setCollapsed(true);

        assertTrue(region.isCollapsed());
        assertEquals(0, textArea.getCaretLineNumber());
    }
}
