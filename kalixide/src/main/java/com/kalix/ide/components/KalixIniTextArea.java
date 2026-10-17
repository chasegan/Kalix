package com.kalix.ide.components;

import com.kalix.ide.themes.SyntaxTheme;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
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
 *   <li>Code folding, mark occurrences, and other editing features</li>
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
    private void initializeIni() {
        // Set Kalix INI syntax highlighting
        setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI);

        // Enable editing features
        setMarkOccurrences(true);
        setMarkOccurrencesDelay(300);
        setHighlightCurrentLine(true);
        setCodeFoldingEnabled(true);

        // Apply saved syntax theme
        applySavedSyntaxTheme();

        // Set theme-aware line highlight
        updateCurrentLineHighlight();
    }

    /**
     * Applies the syntax theme from user preferences (an explicit choice, or
     * the application theme's linked palette when in follow mode).
     */
    private void applySavedSyntaxTheme() {
        try {
            updateSyntaxTheme(com.kalix.ide.themes.ThemePreferences.effectiveSyntaxTheme());
        } catch (Exception e) {
            updateSyntaxTheme(SyntaxTheme.Theme.LIGHT);
        }
    }

    /**
     * Updates the syntax highlighting colors.
     *
     * @param syntaxTheme the theme to apply
     */
    public void updateSyntaxTheme(SyntaxTheme.Theme syntaxTheme) {
        org.fife.ui.rsyntaxtextarea.SyntaxScheme syntaxScheme = getSyntaxScheme();

        if (syntaxScheme != null && syntaxTheme != null) {
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.IDENTIFIER).foreground = syntaxTheme.getIdentifierColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.OPERATOR).foreground = syntaxTheme.getOperatorColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = syntaxTheme.getStringColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD).foreground = syntaxTheme.getReservedWordColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL).foreground = syntaxTheme.getCommentColor();
            syntaxScheme.getStyle(org.fife.ui.rsyntaxtextarea.Token.WHITESPACE).foreground = syntaxTheme.getWhitespaceColor();

            updateCurrentLineHighlight();
            repaint();
        }
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

    /**
     * Updates syntax theme for all KalixIniTextArea instances.
     *
     * @param theme the theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme theme) {
        forEachInstance(KalixIniTextArea.class, textArea -> textArea.updateSyntaxTheme(theme));
    }

    /**
     * Updates theme-dependent colors for all KalixIniTextArea instances.
     * Called when the application theme changes.
     */
    public static void updateAllForThemeChange() {
        forEachInstance(KalixIniTextArea.class, KalixTextArea::updateCurrentLineHighlight);
    }
}
