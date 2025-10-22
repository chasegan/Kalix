package com.kalix.ide.editor;

import javax.swing.text.Segment;
import org.fife.ui.rsyntaxtextarea.*;

/**
 * TokenMaker for INI format with line continuation support.
 *
 * Line continuation is determined by checking if the current line starts with whitespace.
 */
public class KalixIniTokenMaker extends AbstractTokenMaker {

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

        char[] array = text.array;
        int offset = text.offset;
        int end = offset + text.count;

        // Check if this line starts with whitespace (continuation line)
        if (offset < end && Character.isWhitespace(array[offset]) &&
            array[offset] != '\n' && array[offset] != '\r') {
            // This line starts with whitespace - it's a continuation line
            return handleContinuationLine(text, array, offset, end, startOffset);
        }

        // Check if line starts with '[' for section header
        int firstNonWhitespace = offset;
        while (firstNonWhitespace < end && Character.isWhitespace(array[firstNonWhitespace])) {
            firstNonWhitespace++;
        }

        if (firstNonWhitespace < end && array[firstNonWhitespace] == '[') {
            // Handle section header
            handleSectionHeader(text, array, offset, end, firstNonWhitespace, startOffset);
        } else {
            // Find comment position first (comments take precedence)
            int commentPos = findCommentStart(array, offset, end);

            // Look for equals sign, but only before any comment
            int searchEnd = (commentPos >= offset) ? commentPos : end;
            int equalsPos = -1;
            for (int i = offset; i < searchEnd; i++) {
                if (array[i] == '=') {
                    equalsPos = i;
                    break;
                }
            }

            if (equalsPos >= offset) {
                // Found equals sign before any comment - this is a key-value pair
                handleKeyValuePairWithComment(text, array, offset, end, equalsPos, commentPos, startOffset);
            } else {
                // No equals sign (or equals is after comment) - handle as regular line with potential comment
                handleLineWithComment(text, array, offset, end, commentPos, startOffset);
            }
        }

        addNullToken();
        return firstToken;
    }

    private Token handleContinuationLine(Segment text, char[] array, int offset, int end, int startOffset) {
        // Look for comments in the continuation line
        int commentStart = findCommentStart(array, offset, end);

        if (commentStart >= offset) {
            // Continuation line has comment
            if (commentStart > offset) {
                // Add continuation part
                addToken(text, offset, commentStart - 1, Token.LITERAL_STRING_DOUBLE_QUOTE, startOffset);
            }
            // Add comment part
            addToken(text, commentStart, end - 1, Token.COMMENT_EOL,
                    startOffset + (commentStart - offset));
        } else {
            // Entire line is continuation
            addToken(text, offset, end - 1, Token.LITERAL_STRING_DOUBLE_QUOTE, startOffset);
        }

        addNullToken();
        return firstToken;
    }

    private void handleKeyValuePair(Segment text, char[] array, int offset, int end,
                                  int equalsPos, int startOffset) {
        // Add key part (everything before =)
        if (equalsPos > offset) {
            addToken(text, offset, equalsPos - 1, Token.IDENTIFIER, startOffset);
        }

        // Add equals sign
        addToken(text, equalsPos, equalsPos, Token.OPERATOR,
                startOffset + (equalsPos - offset));

        // Handle value part (everything after =)
        int valueStart = equalsPos + 1;
        if (valueStart < end) {
            // Look for comments in the value part
            int commentStart = findCommentStart(array, valueStart, end);

            if (commentStart >= valueStart) {
                // Value has comment
                if (commentStart > valueStart) {
                    // Add value part
                    addToken(text, valueStart, commentStart - 1, Token.LITERAL_STRING_DOUBLE_QUOTE,
                            startOffset + (valueStart - offset));
                }
                // Add comment part
                addToken(text, commentStart, end - 1, Token.COMMENT_EOL,
                        startOffset + (commentStart - offset));
            } else {
                // No comment in value
                addToken(text, valueStart, end - 1, Token.LITERAL_STRING_DOUBLE_QUOTE,
                        startOffset + (valueStart - offset));
            }
        }
    }

    @Override
    public int getLastTokenTypeOnLine(Segment text, int initialTokenType) {
        // This method tells RSyntaxTextArea what state to pass to the next line
        // For line continuation, we don't need to maintain state between lines
        // because each line is processed independently based on whether it starts with whitespace

        return TokenTypes.NULL;
    }

    private void handleSectionHeader(Segment text, char[] array, int offset, int end,
                                   int firstNonWhitespace, int startOffset) {
        int closeBracket = -1;
        for (int i = firstNonWhitespace + 1; i < end; i++) {
            if (array[i] == ']') {
                closeBracket = i;
                break;
            }
        }

        if (closeBracket > firstNonWhitespace) {
            if (firstNonWhitespace > offset) {
                addToken(text, offset, firstNonWhitespace - 1, Token.WHITESPACE, startOffset);
            }
            addToken(text, firstNonWhitespace, closeBracket, Token.RESERVED_WORD,
                    startOffset + (firstNonWhitespace - offset));

            int afterBracket = closeBracket + 1;
            if (afterBracket < end) {
                handleRemainingTextWithComments(text, array, afterBracket, end, startOffset);
            }
        } else {
            addToken(text, offset, end - 1, Token.IDENTIFIER, startOffset);
        }
    }

    private void handleCommentLine(Segment text, char[] array, int offset, int end, int startOffset) {
        int commentStart = findCommentStart(array, offset, end);

        if (commentStart >= offset) {
            if (commentStart > offset) {
                addToken(text, offset, commentStart - 1, Token.IDENTIFIER, startOffset);
            }
            addToken(text, commentStart, end - 1, Token.COMMENT_EOL,
                    startOffset + (commentStart - offset));
        } else {
            addToken(text, offset, end - 1, Token.IDENTIFIER, startOffset);
        }
    }

    private void handleRemainingTextWithComments(Segment text, char[] array, int start, int end,
                                               int startOffset) {
        int commentStart = findCommentStart(array, start, end);

        if (commentStart >= start) {
            if (commentStart > start) {
                addToken(text, start, commentStart - 1, Token.IDENTIFIER,
                        startOffset + (start - text.offset));
            }
            addToken(text, commentStart, end - 1, Token.COMMENT_EOL,
                    startOffset + (commentStart - text.offset));
        } else {
            addToken(text, start, end - 1, Token.IDENTIFIER,
                    startOffset + (start - text.offset));
        }
    }

    /**
     * Finds the start position of a comment (# or ;) in the given range.
     * @return comment start position, or -1 if no comment found
     */
    private int findCommentStart(char[] array, int start, int end) {
        for (int i = start; i < end; i++) {
            if (array[i] == '#' || array[i] == ';') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Handle a key-value pair with potential inline comment.
     * Format: key = value # comment
     */
    private void handleKeyValuePairWithComment(Segment text, char[] array, int offset, int end,
                                                int equalsPos, int commentPos, int startOffset) {
        // Add key part (everything before =)
        if (equalsPos > offset) {
            addToken(text, offset, equalsPos - 1, Token.IDENTIFIER, startOffset);
        }

        // Add equals sign
        addToken(text, equalsPos, equalsPos, Token.OPERATOR,
                startOffset + (equalsPos - offset));

        // Handle value part (between = and comment, or = and end)
        int valueStart = equalsPos + 1;
        int valueEnd = (commentPos >= valueStart) ? commentPos - 1 : end - 1;

        if (valueStart <= valueEnd) {
            addToken(text, valueStart, valueEnd, Token.LITERAL_STRING_DOUBLE_QUOTE,
                    startOffset + (valueStart - offset));
        }

        // Add comment part if present
        if (commentPos >= valueStart) {
            addToken(text, commentPos, end - 1, Token.COMMENT_EOL,
                    startOffset + (commentPos - offset));
        }
    }

    /**
     * Handle a regular line (no key-value pair) with potential inline comment.
     * Format: some text # comment
     */
    private void handleLineWithComment(Segment text, char[] array, int offset, int end,
                                        int commentPos, int startOffset) {
        if (commentPos >= offset) {
            // Line has a comment - split into text and comment
            if (commentPos > offset) {
                // Add text before comment
                addToken(text, offset, commentPos - 1, Token.IDENTIFIER, startOffset);
            }
            // Add comment part
            addToken(text, commentPos, end - 1, Token.COMMENT_EOL,
                    startOffset + (commentPos - offset));
        } else {
            // No comment - entire line is regular text
            addToken(text, offset, end - 1, Token.IDENTIFIER, startOffset);
        }
    }
}