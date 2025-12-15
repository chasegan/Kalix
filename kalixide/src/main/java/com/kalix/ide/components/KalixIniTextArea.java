package com.kalix.ide.components;

import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Text area pre-configured for Kalix INI file editing.
 *
 * Extends KalixTextArea to inherit monospace font configuration and
 * Windows cursor alignment fix, then adds INI-specific features:
 * <ul>
 *   <li>Custom Kalix INI syntax highlighting with line continuation support</li>
 *   <li>Syntax theme from user preferences</li>
 *   <li>Theme-aware current line highlighting</li>
 *   <li>Code folding, mark occurrences, and other editing features</li>
 *   <li>Global preference update support via static methods</li>
 * </ul>
 *
 * @see KalixTextArea for base configuration
 * @see KalixPlainTextArea for plain text display
 */
public class KalixIniTextArea extends KalixTextArea {

    private static final Logger logger = LoggerFactory.getLogger(KalixIniTextArea.class);

    /** Syntax style identifier for Kalix INI format. */
    public static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    private static final List<WeakReference<KalixIniTextArea>> instances = new ArrayList<>();

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
        registerInstance();
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
     * Applies the syntax theme from user preferences.
     */
    private void applySavedSyntaxTheme() {
        try {
            String savedThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            SyntaxTheme.Theme savedTheme = SyntaxTheme.getThemeByName(savedThemeName);
            updateSyntaxTheme(savedTheme);
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

    private void registerInstance() {
        synchronized (instances) {
            instances.add(new WeakReference<>(this));
        }
    }

    /**
     * Updates syntax theme for all KalixIniTextArea instances.
     *
     * @param theme the theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme theme) {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    iterator.remove();
                } else {
                    textArea.updateSyntaxTheme(theme);
                }
            }
        }
    }

    /**
     * Updates font size for all KalixIniTextArea instances.
     *
     * @param fontSize the new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    iterator.remove();
                } else {
                    textArea.updateFontSize(fontSize);
                }
            }
        }
    }

    /**
     * Updates theme-dependent colors for all KalixIniTextArea instances.
     * Called when the application theme changes.
     */
    public static void updateAllForThemeChange() {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    iterator.remove();
                } else {
                    textArea.updateCurrentLineHighlight();
                }
            }
        }
    }
}
