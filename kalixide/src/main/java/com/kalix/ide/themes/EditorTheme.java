package com.kalix.ide.themes;

import com.kalix.ide.utils.ThemeUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.Token;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * The editor's colours, as one RSyntaxTextArea {@link Theme} built from the current
 * look-and-feel and the effective syntax palette.
 *
 * <p>RSyntaxTextArea hard-codes a white background and its own gutter, selection and
 * bracket colours when it is built, and the look-and-feel never restyles them. This class
 * is the one place those colours are decided: the chrome (background, caret, selection,
 * current line, gutter, folds, brackets) comes from the look-and-feel's own keys, which
 * the theme properties files feed, and the token colours come from
 * {@link ThemePreferences#effectiveSyntaxTheme()}. {@link #apply} is called by every
 * Kalix text area from {@code updateUI}, so a theme switch reaches every editor in
 * every window through the ordinary look-and-feel walk, and nothing keeps a list of
 * editors to update.
 */
public final class EditorTheme {

    private EditorTheme() {
    }

    /**
     * Applies the current theme to a text area: every colour the library's theme
     * carries, and the foreground, which it does not. The syntax palette is applied only
     * where there is syntax to colour; a plain text area paints in the foreground.
     */
    public static void apply(RSyntaxTextArea textArea, boolean withSyntaxPalette) {
        Theme theme = build(textArea, withSyntaxPalette);
        theme.apply(textArea);
        textArea.setForeground(foreground());
    }

    /**
     * Builds the theme for a text area. Starts from the text area's current state so that
     * whatever this class does not decide is left as it is.
     */
    private static Theme build(RSyntaxTextArea textArea, boolean withSyntaxPalette) {
        Color bg = background();
        Color fg = foreground();
        Color selection = color("TextArea.selectionBackground", new Color(51, 153, 255));
        Color accent = ThemeUtils.accentColor();
        Color muted = color("Label.disabledForeground", Color.GRAY);
        Color border = color("Component.borderColor", muted);

        Theme theme = new Theme(textArea);
        theme.bgColor = bg;
        theme.caretColor = color("TextArea.caretForeground", fg);
        theme.selectionBG = selection;
        // The dark themes carry the line colour their mocks designed; the others blend the selection
        theme.currentLineHighlight = color("Kalix.editor.currentLine", withAlpha(selection, 80));
        theme.marginLineColor = border;
        theme.tabLineColor = border;
        theme.markOccurrencesColor = withAlpha(selection, 70);
        theme.markOccurrencesBorder = false;
        theme.markAllHighlightColor = withAlpha(accent, 110);
        theme.matchedBracketBG = withAlpha(selection, 70);
        theme.matchedBracketFG = accent;
        theme.hyperlinkFG = color("Component.linkColor", accent);

        theme.gutterBackgroundColor = bg;
        theme.gutterBorderColor = border;
        theme.lineNumberColor = muted;
        theme.currentLineNumberColor = fg;
        theme.foldBG = bg;
        theme.armedFoldBG = withAlpha(selection, 70);
        theme.foldIndicatorFG = muted;
        theme.foldIndicatorArmedFG = fg;

        if (withSyntaxPalette) {
            theme.scheme = scheme(textArea, ThemePreferences.effectiveSyntaxTheme());
        }
        return theme;
    }

    /**
     * The token colours: the syntax palette's six, and annotations in the section
     * header's colour and italics, since an annotation belongs to the header below it.
     */
    private static SyntaxScheme scheme(RSyntaxTextArea textArea, SyntaxTheme.Theme palette) {
        SyntaxScheme scheme = (SyntaxScheme) textArea.getSyntaxScheme().clone();
        scheme.getStyle(Token.IDENTIFIER).foreground = palette.getIdentifierColor();
        scheme.getStyle(Token.OPERATOR).foreground = palette.getOperatorColor();
        scheme.getStyle(Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = palette.getStringColor();
        scheme.getStyle(Token.RESERVED_WORD).foreground = palette.getReservedWordColor();
        scheme.getStyle(Token.COMMENT_EOL).foreground = palette.getCommentColor();
        scheme.getStyle(Token.WHITESPACE).foreground = palette.getWhitespaceColor();
        Style annotation = scheme.getStyle(Token.ANNOTATION);
        annotation.foreground = palette.getReservedWordColor();
        annotation.font = textArea.getFont().deriveFont(Font.ITALIC);
        return scheme;
    }

    private static Color background() {
        return color("TextArea.background", Color.WHITE);
    }

    private static Color foreground() {
        return color("TextArea.foreground", Color.BLACK);
    }

    /**
     * A look-and-feel colour as a plain {@link Color}. The look-and-feel stores its colours
     * as {@code UIResource}s, which a component's own {@code updateUI} is entitled to
     * replace with its defaults; a gutter given one would be repainted in the panel colour
     * by its own update later in the same walk.
     */
    private static Color color(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? new Color(color.getRGB(), true) : fallback;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
