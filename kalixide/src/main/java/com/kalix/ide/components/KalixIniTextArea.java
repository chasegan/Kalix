package com.kalix.ide.components;

import com.kalix.ide.editor.KalixIniFoldParser;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text area pre-configured for Kalix INI file editing.
 *
 * Extends KalixTextArea to inherit monospace font configuration and
 * screen-aware font metrics, then adds INI-specific features:
 * <ul>
 *   <li>Custom Kalix INI syntax highlighting with line continuation support</li>
 *   <li>Syntax theme from user preferences</li>
 *   <li>Theme-aware current line highlighting</li>
 *   <li>Code folding of regions, sections and multi-line values
 *       ({@link KalixIniFoldParser}), mark occurrences, and other editing features</li>
 *   <li>Syntax theme updates for every live instance via static methods</li>
 * </ul>
 *
 * @see KalixTextArea for base configuration
 * @see KalixPlainTextArea for plain text display
 */
public class KalixIniTextArea extends KalixTextArea {

    private static final Logger logger = LoggerFactory.getLogger(KalixIniTextArea.class);

    /** Syntax style identifier for Kalix INI format. */
    public static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    static {
        registerCustomTokenMaker();
        FoldParserManager.get().addFoldParserMapping(SYNTAX_STYLE_KALIX_INI, new KalixIniFoldParser());
    }

    /**
     * Creates a KalixIniTextArea with default dimensions.
     */
    public KalixIniTextArea() {
        this(20, 60);
    }

    /**
     * Creates a KalixIniTextArea with specified dimensions.
     *
     * @param rows number of visible rows
     * @param cols number of visible columns
     */
    public KalixIniTextArea(int rows, int cols) {
        super(rows, cols);
        initializeIni();
    }

    /**
     * Creates a KalixIniTextArea configured for read-only display.
     * Useful for diff views and preview panels.
     *
     * @param rows number of visible rows
     * @param cols number of visible columns
     * @return a new read-only KalixIniTextArea instance
     */
    public static KalixIniTextArea createReadOnly(int rows, int cols) {
        KalixIniTextArea textArea = new KalixIniTextArea(rows, cols);
        textArea.setEditable(false);
        return textArea;
    }

    /**
     * Initializes INI-specific settings.
     */
    @Override
    protected boolean hasSyntaxPalette() {
        return true;
    }

    private void initializeIni() {
        // Set Kalix INI syntax highlighting
        setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI);

        // Enable editing features
        setMarkOccurrences(true);
        setMarkOccurrencesDelay(300);
        setHighlightCurrentLine(true);
        setCodeFoldingEnabled(true);
    }

    /**
     * Sets the text and folds it at once. The library rebuilds folds from a timer
     * after each edit, which would leave a document just opened unfolded for a
     * second. The old document's folds are dropped first so none of their state
     * carries over by a coincidence of position.
     */
    @Override
    public void setText(String text) {
        getFoldManager().clear();
        super.setText(text);
        getFoldManager().reparse();
    }

    /**
     * Registers the custom TokenMaker for Kalix INI syntax.
     */
    private static void registerCustomTokenMaker() {
        try {
            AbstractTokenMakerFactory factory = (AbstractTokenMakerFactory) TokenMakerFactory.getDefaultInstance();
            factory.putMapping(SYNTAX_STYLE_KALIX_INI, "com.kalix.ide.editor.KalixIniTokenMaker");
        } catch (Exception e) {
            logger.error("Failed to register custom Kalix INI TokenMaker", e);
        }
    }
}
