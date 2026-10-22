package com.kalix.ide.editor;

import org.fife.ui.rsyntaxtextarea.Token;
import org.junit.jupiter.api.Test;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An annotation line ({@code #@} at column zero, ADR-0010 §2) is one
 * {@code ANNOTATION} token; a disabled one is a plain comment.
 */
class KalixIniTokenMakerAnnotationTest {

    private static List<Integer> types(String line) {
        Segment segment = new Segment(line.toCharArray(), 0, line.length());
        Token token = new KalixIniTokenMaker().getTokenList(segment, Token.NULL, 0);
        List<Integer> types = new ArrayList<>();
        for (Token t = token; t != null && t.isPaintable(); t = t.getNextToken()) {
            types.add(t.getType());
        }
        return types;
    }

    @Test
    void annotationLineIsOneAnnotationToken() {
        assertEquals(List.of(Token.ANNOTATION), types("#@aka = Teemburra Dam # not a comment"));
        assertEquals(List.of(Token.ANNOTATION), types("#@"));
    }

    @Test
    void disabledOrIndentedAnnotationIsAComment() {
        assertEquals(List.of(Token.COMMENT_EOL), types("##@aka = x"));
        assertEquals(List.of(Token.COMMENT_EOL), types("# #@aka = x"));
        assertEquals(List.of(Token.LITERAL_STRING_DOUBLE_QUOTE, Token.COMMENT_EOL), types("  #@aka = x"));
    }

    @Test
    void ordinaryLinesAreUnchanged() {
        assertEquals(List.of(Token.COMMENT_EOL), types("# comment"));
        assertEquals(List.of(Token.IDENTIFIER, Token.OPERATOR, Token.LITERAL_STRING_DOUBLE_QUOTE), types("a = 1"));
    }
}
