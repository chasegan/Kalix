package com.kalix.ide.editor;

import com.kalix.ide.io.CsvLineStylist;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMap;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

/**
 * TokenMaker for delimited data files, parameterised by the sniffed dialect —
 * installed per-document via {@code EnhancedTextEditor.useTokenMaker}, since
 * the factory registration route cannot carry constructor arguments.
 *
 * <p>Segmentation comes from {@link CsvLineStylist} (shared with the big-file
 * virtual text view), and the roles map onto the same six token types the
 * Kalix INI grammar uses, so every existing syntax theme works unchanged:
 * delimiters → WHITESPACE (the slot every theme tunes to recede — a data line
 * has dozens of delimiters, and the accent OPERATOR slot made them the
 * loudest thing on screen), the header row and {@code .res.csv} marker lines
 * → RESERVED_WORD, the date column → LITERAL_STRING_DOUBLE_QUOTE,
 * missing-value markers → COMMENT_EOL (muted: "no data here"), values →
 * IDENTIFIER.
 *
 * <p>Per-line by design (a multi-line quoted field colours imperfectly past
 * its first line); the header row is the line at document offset 0.
 */
public class KalixCsvTokenMaker extends AbstractTokenMaker {

    private final CsvLineStylist stylist;
    private final boolean hasHeaderRow;

    public KalixCsvTokenMaker(char delimiter, char quote, boolean hasHeaderRow) {
        this.stylist = new CsvLineStylist(delimiter, quote);
        this.hasHeaderRow = hasHeaderRow;
    }

    @Override
    public TokenMap getWordsToHighlight() {
        return new TokenMap();
    }

    @Override
    public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
        resetTokenList();
        if (text == null || text.count == 0) {
            addNullToken();
            return firstToken;
        }
        String line = new String(text.array, text.offset, text.count);
        boolean headerLine = hasHeaderRow && startOffset == 0;

        int previousEnd = 0;
        for (CsvLineStylist.Span span : stylist.style(line, headerLine)) {
            if (span.start() > previousEnd) {
                // Defensive: the stylist promises contiguous coverage, but the
                // token stream must cover the line even if that ever changes.
                addSpan(text, previousEnd, span.start(), Token.IDENTIFIER, startOffset);
            }
            addSpan(text, span.start(), span.endExclusive(), tokenTypeFor(span.role()), startOffset);
            previousEnd = span.endExclusive();
        }
        if (previousEnd < line.length()) {
            addSpan(text, previousEnd, line.length(), Token.IDENTIFIER, startOffset);
        }
        addNullToken();
        return firstToken;
    }

    private void addSpan(Segment text, int start, int endExclusive, int type, int startOffset) {
        addToken(text, text.offset + start, text.offset + endExclusive - 1, type, startOffset + start);
    }

    private static int tokenTypeFor(CsvLineStylist.Role role) {
        return switch (role) {
            case DELIMITER -> Token.WHITESPACE;
            case HEADER, MARKER -> Token.RESERVED_WORD;
            case DATE_AXIS -> Token.LITERAL_STRING_DOUBLE_QUOTE;
            case MISSING -> Token.COMMENT_EOL;
            case VALUE -> Token.IDENTIFIER;
        };
    }

    @Override
    public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
        return TokenTypes.NULL; // per-line; multi-line quoted fields accepted-imperfect
    }
}
