package com.kalix.ide.components;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * Plain text area for non-syntax-highlighted content such as logs and output.
 *
 * Extends KalixTextArea to inherit:
 * <ul>
 *   <li>Monospace font configuration</li>
 *   <li>Font metrics that follow the screen the editor is shown on</li>
 *   <li>Common editor settings</li>
 * </ul>
 *
 * Use this class for displaying plain text content like STDIO logs,
 * command output, or any text that doesn't require syntax highlighting.
 *
 * @see KalixTextArea for base configuration
 * @see KalixIniTextArea for Kalix INI file editing
 */
public class KalixPlainTextArea extends KalixTextArea {

    /**
     * Creates a plain text area with default dimensions.
     */
    public KalixPlainTextArea() {
        this(20, 60);
    }

    /**
     * Creates a plain text area with specified dimensions.
     *
     * @param rows number of visible rows
     * @param cols number of visible columns
     */
    public KalixPlainTextArea(int rows, int cols) {
        super(rows, cols);
        initialize();
    }

    /**
     * Creates a plain text area configured for read-only display.
     *
     * @param readOnly if true, the text area will be non-editable
     * @return a new KalixPlainTextArea instance
     */
    public static KalixPlainTextArea createReadOnly(int rows, int cols) {
        KalixPlainTextArea textArea = new KalixPlainTextArea(rows, cols);
        textArea.setEditable(false);
        return textArea;
    }

    private void initialize() {
        // No syntax highlighting for plain text
        setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);

        // Disable features not needed for plain text
        setCodeFoldingEnabled(false);
        setMarkOccurrences(false);
        setHighlightCurrentLine(false);
    }
}
