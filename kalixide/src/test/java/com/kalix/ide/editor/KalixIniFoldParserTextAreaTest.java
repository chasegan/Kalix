package com.kalix.ide.editor;

import com.kalix.ide.components.KalixIniTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;
import org.junit.jupiter.api.Test;

import javax.swing.text.BadLocationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fold parser is registered for the Kalix INI style, and the folds it builds
 * carry the ranges' lines and nesting into the text area.
 */
class KalixIniFoldParserTextAreaTest {

    @Test
    void foldParserIsRegisteredForTheKalixIniStyle() {
        assertTrue(new KalixIniTextArea().getFoldManager().isCodeFoldingSupportedAndEnabled());
    }

    @Test
    void modelTextAreaFoldsRegionsSectionsAndValuesNested() {
        KalixIniTextArea textArea = new KalixIniTextArea();
        textArea.setText("#@region = A\n[node.a]\nkc = 1,\n   2\n\n[node.b]\ntype = gauge\n");
        FoldManager folds = textArea.getFoldManager();

        assertEquals(1, folds.getFoldCount(), "folds are built as soon as the text is set");
        Fold region = folds.getFold(0);
        assertEquals(KalixIniFoldParser.REGION_FOLD_TYPE, region.getFoldType());
        assertLines(region, 0, 6);
        assertEquals(2, region.getChildCount());

        Fold a = region.getChild(0);
        assertEquals(FoldType.CODE, a.getFoldType());
        assertLines(a, 1, 3);
        assertEquals(1, a.getChildCount());
        assertLines(a.getChild(0), 2, 3);

        Fold b = region.getChild(1);
        assertLines(b, 5, 6);
        assertEquals(0, b.getChildCount());
    }

    @Test
    void textWithoutCodeHasNoFolds() {
        KalixIniTextArea textArea = new KalixIniTextArea();
        textArea.setText("# nothing to fold\n");
        assertEquals(0, textArea.getFoldManager().getFoldCount());
    }

    @Test
    void collapsedFoldKeepsItsEndWhileTheCaretTypesBelowIt() throws BadLocationException {
        KalixIniTextArea textArea = new KalixIniTextArea();
        textArea.setText("[node.a]\ntype = inflow\n\n[node.b]\ntype = gauge\n");
        FoldManager folds = textArea.getFoldManager();
        folds.getFold(0).setCollapsed(true);

        textArea.setCaretPosition(textArea.getLineStartOffset(2));
        textArea.replaceSelection("loc = 1, 2");
        folds.reparse();

        Fold a = folds.getFold(0);
        assertTrue(a.isCollapsed());
        assertLines(a, 0, 1);
        assertFalse(folds.isLineHidden(2));
        assertEquals(2, textArea.getCaretLineNumber());

        textArea.setCaretPosition(textArea.getLineStartOffset(3));
        folds.reparse();
        assertTrue(folds.getFold(0).isCollapsed());
        assertLines(folds.getFold(0), 0, 2);
    }

    @Test
    void sectionTypedBelowACollapsedRegionFoldsBesideIt() throws BadLocationException {
        KalixIniTextArea textArea = new KalixIniTextArea();
        textArea.setText("#@region = A\n[node.a]\ntype = inflow\n\n");
        FoldManager folds = textArea.getFoldManager();
        folds.getFold(0).setCollapsed(true);

        textArea.setCaretPosition(textArea.getLineStartOffset(4));
        textArea.replaceSelection("[node.b]\ntype = gauge");
        folds.reparse();

        assertEquals(2, folds.getFoldCount());
        assertTrue(folds.getFold(0).isCollapsed());
        assertLines(folds.getFold(0), 0, 2);
        assertFalse(folds.getFold(1).isCollapsed());
        assertLines(folds.getFold(1), 4, 5);
    }

    private static void assertLines(Fold fold, int first, int last) {
        assertEquals(first, fold.getStartLine(), "first line");
        assertEquals(last, fold.getEndLine(), "last line");
    }
}
