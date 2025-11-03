package com.kalix.ide.components;

import com.kalix.ide.preferences.PreferenceManager;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.themes.SyntaxTheme;
import com.kalix.ide.managers.FontManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom RSyntaxTextArea pre-configured for Kalix INI files.
 *
 * Features:
 * - Custom Kalix INI syntax highlighting with line continuation support
 * - Monospace font (JetBrains Mono with fallback)
 * - Syntax theme from user preferences
 * - Theme-aware current line highlighting
 * - Code folding, anti-aliasing, and other defaults
 * - Automatic updates when preferences change
 */
public class KalixIniTextArea extends RSyntaxTextArea {
    private static final Logger logger = LoggerFactory.getLogger(KalixIniTextArea.class);

    // Custom syntax style for Kalix INI with line continuation
    private static final String SYNTAX_STYLE_KALIX_INI = "text/kalixini";

    // Track all open instances for preference updates (using WeakReference to avoid memory leaks)
    private static final List<WeakReference<KalixIniTextArea>> instances = new ArrayList<>();

    // Static block to register custom TokenMaker
    static {
        registerCustomTokenMaker();
    }

    /**
     * Creates a KalixIniTextArea with default configuration.
     */
    public KalixIniTextArea() {
        this(20, 60);
    }

    /**
     * Creates a KalixIniTextArea with specified dimensions.
     *
     * @param rows Number of rows
     * @param cols Number of columns
     */
    public KalixIniTextArea(int rows, int cols) {
        super(rows, cols);
        initialize();
        registerInstance();
    }

    /**
     * Initializes the text area with Kalix INI defaults.
     */
    private void initialize() {
        // Set Kalix INI syntax highlighting
        setSyntaxEditingStyle(SYNTAX_STYLE_KALIX_INI);

        // Enable INI mode features
        setMarkOccurrences(true);
        setMarkOccurrencesDelay(300); // 300ms delay before highlighting
        setHighlightCurrentLine(true);

        // Code editing defaults
        setCodeFoldingEnabled(true);
        setAntiAliasingEnabled(true);
        setTabSize(4);
        setTabsEmulated(true);
        setLineWrap(false);

        // Configure monospace font
        configureMonospaceFont();

        // Apply saved syntax theme
        applySavedSyntaxTheme();

        // Set current line highlight color based on theme
        updateCurrentLineHighlight();
    }

    /**
     * Configures a monospace font for the text area.
     * Uses the embedded JetBrains Mono font with automatic fallback to system fonts.
     */
    private void configureMonospaceFont() {
        int fontSize = PreferenceManager.getFileInt(PreferenceKeys.EDITOR_FONT_SIZE, 12);
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        setFont(monoFont);
    }

    /**
     * Applies the saved syntax theme from preferences.
     */
    private void applySavedSyntaxTheme() {
        try {
            String savedThemeName = PreferenceManager.getFileString(PreferenceKeys.UI_SYNTAX_THEME, "LIGHT");
            SyntaxTheme.Theme savedTheme = SyntaxTheme.getThemeByName(savedThemeName);
            updateSyntaxTheme(savedTheme);
        } catch (Exception e) {
            // Fallback to Light theme if anything goes wrong
            updateSyntaxTheme(SyntaxTheme.Theme.LIGHT);
        }
    }

    /**
     * Updates the syntax highlighting theme for the text editor.
     *
     * @param syntaxTheme The syntax theme to apply
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
     * Updates the current line highlight color based on the current theme.
     */
    private void updateCurrentLineHighlight() {
        Color selectionBgColor = UIManager.getColor("TextArea.selectionBackground");

        if (selectionBgColor != null) {
            // Create a more subtle version of the selection color for line highlight
            int alpha = 80; // More visible for better navigation feedback
            Color lineHighlightColor = new Color(
                selectionBgColor.getRed(),
                selectionBgColor.getGreen(),
                selectionBgColor.getBlue(),
                alpha
            );
            setCurrentLineHighlightColor(lineHighlightColor);
        } else {
            // Fallback: determine if dark theme and set appropriate color
            if (isDarkTheme()) {
                setCurrentLineHighlightColor(new Color(255, 255, 255, 25)); // Light highlight for dark theme
            } else {
                setCurrentLineHighlightColor(new Color(0, 0, 0, 25)); // Dark highlight for light theme
            }
        }
    }

    /**
     * Determines if the current theme is dark based on the background color.
     *
     * @return true if dark theme, false if light theme
     */
    private boolean isDarkTheme() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        // Consider theme dark if the sum of RGB values is less than 384 (128 * 3)
        return (bg.getRed() + bg.getGreen() + bg.getBlue()) < 384;
    }

    /**
     * Updates the font size of the text editor.
     *
     * @param fontSize The new font size in points
     */
    public void updateFontSize(int fontSize) {
        Font monoFont = FontManager.getMonospaceFont(fontSize);
        setFont(monoFont);
    }

    /**
     * Registers the custom TokenMaker for Kalix INI format with line continuation support.
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
     * Registers this instance for global preference updates.
     */
    private void registerInstance() {
        synchronized (instances) {
            instances.add(new WeakReference<>(this));
        }
    }

    /**
     * Updates the syntax theme for all open KalixIniTextArea instances.
     * Called when syntax theme preference changes.
     *
     * @param theme The new syntax theme to apply
     */
    public static void updateAllSyntaxThemes(SyntaxTheme.Theme theme) {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    // Instance has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update the syntax theme
                    textArea.updateSyntaxTheme(theme);
                }
            }
        }
    }

    /**
     * Updates the font size for all open KalixIniTextArea instances.
     * Called when font size preference changes.
     *
     * @param fontSize The new font size in points
     */
    public static void updateAllFontSizes(int fontSize) {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    // Instance has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update the font size
                    textArea.updateFontSize(fontSize);
                }
            }
        }
    }

    /**
     * Updates theme-dependent colors for all open KalixIniTextArea instances.
     * Called when the application theme changes (e.g., light to dark).
     */
    public static void updateAllForThemeChange() {
        synchronized (instances) {
            Iterator<WeakReference<KalixIniTextArea>> iterator = instances.iterator();
            while (iterator.hasNext()) {
                WeakReference<KalixIniTextArea> ref = iterator.next();
                KalixIniTextArea textArea = ref.get();

                if (textArea == null) {
                    // Instance has been garbage collected, remove the reference
                    iterator.remove();
                } else {
                    // Update current line highlight color based on new theme
                    textArea.updateCurrentLineHighlight();
                }
            }
        }
    }
}
