package com.kalix.ide.editor;

import com.kalix.ide.components.KalixIniTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldManager;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fold parser is registered for the Kalix INI style, and the folds it builds
 * carry the ranges' lines and nesting into the text area.
 */
class KalixIniFoldParserTextAreaTest {

    @Test
    void modelTextAreaFoldsRegionsSectionsAndValuesNested() {
        KalixIniTextArea textArea = new KalixIniTextArea();
        assertTrue(textArea.getFoldManager().isCodeFoldingSupportedAndEnabled(),
            "a fold parser is registered for the Kalix INI style");

        textArea.setText("#@region = A\n[node.a]\nkc = 1,\n   2\n\n[node.b]\ntype = gauge\n");
        FoldManager folds = textArea.getFoldManager();
        folds.reparse();

        assertEquals(1, folds.getFoldCount());
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
        textArea.getFoldManager().reparse();
        assertEquals(0, textArea.getFoldManager().getFoldCount());
    }

    private static void assertLines(Fold fold, int first, int last) {
        assertEquals(first, fold.getStartLine(), "first line");
        assertEquals(last, fold.getEndLine(), "last line");
    }
}
