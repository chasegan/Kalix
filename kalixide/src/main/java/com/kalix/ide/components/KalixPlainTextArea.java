package com.kalix.ide.components;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plain text area for non-syntax-highlighted content such as logs and output.
 *
 * Extends KalixTextArea to inherit:
 * <ul>
 *   <li>Monospace font configuration</li>
 *   <li>Windows cursor alignment fix</li>
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

    private static final List<WeakReference<KalixPlainTextArea>> instances = new ArrayList<>();

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
        registerInstance();
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

    private void registerInstance() {
        synchronized (instances) {
            instances.add(new WeakReference<>(this));
        }
    }

    /**
     * Updates the font size for all KalixPlainTextArea instances.
     *
     * @param fontSize the new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (instances) {
            Iterator<WeakReference<KalixPlainTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixPlainTextArea> ref = iterator.next();
                KalixPlainTextArea textArea = ref.get();

                if (textArea == null) {
                    iterator.remove();
                } else {
                    textArea.updateFontSize(fontSize);
                }
            }
        }
    }
}
