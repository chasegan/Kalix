package com.kalix.ide.themes;

import com.kalix.ide.components.KalixIniTextArea;
import com.kalix.ide.components.KalixPlainTextArea;
import com.kalix.ide.components.KalixTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every Kalix text area takes its colours from the look-and-feel through
 * {@link EditorTheme}, at construction and again on every look-and-feel walk, gutter
 * included (issue #509: the optimiser window's editors stayed white in a dark theme).
 */
class EditorThemeTest {

    private static final Color DARK_BG = new Color(0x1e, 0x1f, 0x22);
    private static final Color DARK_FG = new Color(0xbc, 0xbe, 0xc4);
    private static final Color LIGHT_BG = Color.WHITE;
    private static final Color LIGHT_FG = Color.BLACK;


    /** Headless stand-in: the library re-measures its font against the editor's graphics. */
    private static final class HeadlessEditor extends KalixIniTextArea {
        HeadlessEditor() {
            super(5, 20);
        }

        @Override
        public Graphics getGraphics() {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        }
    }

    @AfterEach
    void restoreLookAndFeelColours() {
        UIManager.put("TextArea.background", null);
        UIManager.put("TextArea.foreground", null);
    }

    /** As the look-and-feel stores them: {@code UIResource}s, which a component's own defaults may replace. */
    private static void lookAndFeel(Color bg, Color fg) {
        UIManager.put("TextArea.background", new ColorUIResource(bg));
        UIManager.put("TextArea.foreground", new ColorUIResource(fg));
    }

    @Test
    void aNewEditorTakesTheLookAndFeelColours() {
        lookAndFeel(DARK_BG, DARK_FG);
        KalixIniTextArea ini = new KalixIniTextArea(5, 20);
        KalixPlainTextArea plain = new KalixPlainTextArea(5, 20);
        for (KalixTextArea editor : new KalixTextArea[] {ini, plain}) {
            assertEquals(DARK_BG, editor.getBackground());
            assertEquals(DARK_FG, editor.getForeground());
        }
    }

    @Test
    void theGutterFollowsTheEditorOnceItHasOne() {
        lookAndFeel(DARK_BG, DARK_FG);
        KalixIniTextArea editor = new HeadlessEditor();
        RTextScrollPane scrollPane = new RTextScrollPane(editor);
        editor.addNotify();
        Gutter gutter = scrollPane.getGutter();
        assertEquals(DARK_BG, gutter.getBackground());
        assertEquals(DARK_BG, gutter.getFoldBackground(), "fold boxes are not white on a dark gutter");
        assertEquals(DARK_FG, gutter.getCurrentLineNumberColor());
    }

    @Test
    void aLookAndFeelWalkReThemesTheEditorAndItsGutter() {
        lookAndFeel(LIGHT_BG, LIGHT_FG);
        KalixIniTextArea editor = new KalixIniTextArea(5, 20);
        RTextScrollPane scrollPane = new RTextScrollPane(editor);
        assertEquals(LIGHT_BG, editor.getBackground());

        lookAndFeel(DARK_BG, DARK_FG);
        SwingUtilities.updateComponentTreeUI(scrollPane);

        assertEquals(DARK_BG, editor.getBackground());
        assertEquals(DARK_FG, editor.getForeground());
        assertEquals(DARK_BG, scrollPane.getGutter().getBackground(),
            "the gutter's own update, later in the walk, must not replace the colour with the panel's");
    }

    @Test
    void aPlainTextAreaPaintsInTheForegroundNotThePalette() {
        lookAndFeel(DARK_BG, DARK_FG);
        KalixPlainTextArea plain = new KalixPlainTextArea(5, 20);
        assertEquals(null, plain.getSyntaxScheme().getStyle(Token.IDENTIFIER).foreground,
            "no token colour, so the library paints plain text in the component foreground");
    }

    @Test
    void tokenColoursComeFromTheEffectiveSyntaxPalette() {
        lookAndFeel(LIGHT_BG, LIGHT_FG);
        KalixIniTextArea editor = new KalixIniTextArea(5, 20);
        SyntaxTheme.Theme palette = ThemePreferences.effectiveSyntaxTheme();
        assertEquals(palette.getIdentifierColor(), editor.getSyntaxScheme().getStyle(Token.IDENTIFIER).foreground);
        assertEquals(palette.getReservedWordColor(), editor.getSyntaxScheme().getStyle(Token.RESERVED_WORD).foreground);
        assertEquals(palette.getCommentColor(), editor.getSyntaxScheme().getStyle(Token.COMMENT_EOL).foreground);
        Font annotation = editor.getSyntaxScheme().getStyle(Token.ANNOTATION).font;
        assertTrue(annotation.isItalic(), "annotations are italic in the header's colour");
        assertEquals(palette.getReservedWordColor(), editor.getSyntaxScheme().getStyle(Token.ANNOTATION).foreground);
    }
}
