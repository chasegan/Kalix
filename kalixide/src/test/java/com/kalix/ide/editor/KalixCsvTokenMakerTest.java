package com.kalix.ide.editor;

import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the CSV token maker: header-row detection by document offset, the
 * role→token-type mapping onto the six INI theme slots, and lossless lexeme
 * coverage of the line.
 */
class KalixCsvTokenMakerTest {

    private static Segment segment(String line) {
        return new Segment(line.toCharArray(), 0, line.length());
    }

    private static List<Integer> types(Token token) {
        List<Integer> types = new ArrayList<>();
        for (Token t = token; t != null && t.isPaintable(); t = t.getNextToken()) {
            types.add(t.getType());
        }
        return types;
    }

    private static String lexemes(Token token) {
        StringBuilder text = new StringBuilder();
        for (Token t = token; t != null && t.isPaintable(); t = t.getNextToken()) {
            text.append(t.getLexeme());
        }
        return text.toString();
    }

    @Test
    void headerRowIsReservedWords() {
        KalixCsvTokenMaker maker = new KalixCsvTokenMaker(',', '"', true);
        Token tokens = maker.getTokenList(segment("date,flow"), TokenTypes.NULL, 0);
        assertEquals(List.of(Token.RESERVED_WORD, Token.WHITESPACE, Token.RESERVED_WORD), types(tokens));
    }

    @Test
    void dataRowMapsDateValueAndMissing() {
        KalixCsvTokenMaker maker = new KalixCsvTokenMaker(',', '"', true);
        // Non-zero offset: not the header line.
        Token tokens = maker.getTokenList(segment("2020-01-01,1.5,na"), TokenTypes.NULL, 10);
        assertEquals(List.of(
            Token.LITERAL_STRING_DOUBLE_QUOTE, // date axis
            Token.WHITESPACE,                  // delimiter (the recede slot)
            Token.IDENTIFIER,                  // value
            Token.WHITESPACE,
            Token.COMMENT_EOL),                // missing marker
            types(tokens));
    }

    @Test
    void headerlessFilesTreatLineZeroAsData() {
        KalixCsvTokenMaker maker = new KalixCsvTokenMaker(',', '"', false);
        Token tokens = maker.getTokenList(segment("2020-01-01,1"), TokenTypes.NULL, 0);
        assertEquals(Token.LITERAL_STRING_DOUBLE_QUOTE, types(tokens).get(0),
            "no header row: line zero is data, its first field the date axis");
    }

    @Test
    void lexemesReconstructTheLine() {
        KalixCsvTokenMaker maker = new KalixCsvTokenMaker(';', '"', true);
        String line = "a;;\"x;y\";na";
        Token tokens = maker.getTokenList(segment(line), TokenTypes.NULL, 42);
        assertEquals(line, lexemes(tokens), "the token stream must cover every character");
    }

    @Test
    void resCsvMarkerLineIsOneReservedToken() {
        KalixCsvTokenMaker maker = new KalixCsvTokenMaker(',', '"', false);
        Token tokens = maker.getTokenList(segment("EOH"), TokenTypes.NULL, 100);
        assertEquals(List.of(Token.RESERVED_WORD), types(tokens));
    }
}
