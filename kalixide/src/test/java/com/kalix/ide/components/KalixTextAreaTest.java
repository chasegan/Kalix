package com.kalix.ide.components;

import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins two contracts of the shared editor base class.
 *
 * <p>Issue #162: an editor whose cached font metrics were measured on a screen with one
 * device scale, and which is painted on a screen with another, rebuilds them at the
 * screen's scale. Runs headless, so the screen is stood in for: the test editor claims
 * to be displayable and hands RSyntaxTextArea a {@code Graphics2D} carrying the scale of
 * the pretend screen, which is what the library measures with. The paint itself is
 * stood in for by the check it delegates to.
 *
 * <p>Font-size preference: one registry reaches every live editor, plain and INI alike.
 * The session log area used to sit in a registry nothing swept. The registry is
 * process-wide and the tests run sequentially, so a sweep in one test cannot be
 * observed by another.
 */
class KalixTextAreaTest {

    /** An editor on a pretend screen, counting font applications after construction. */
    private static final class ScreenTextArea extends KalixPlainTextArea {
        private double screenScale = 1.0;
        private int fontApplications;
        private boolean constructed;

        ScreenTextArea() {
            super(5, 20);
            constructed = true;
        }

        /** Displayable once built: RSyntaxTextArea's constructor sets the font before its syntax scheme exists. */
        @Override
        public boolean isDisplayable() {
            return constructed;
        }

        /** What RSyntaxTextArea measures with: a Graphics at the pretend screen's scale. */
        @Override
        public Graphics getGraphics() {
            Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            g.scale(screenScale, screenScale);
            return g;
        }

        @Override
        public void setFont(Font font) {
            if (constructed) {
                fontApplications++;
            }
            super.setFont(font);
        }

        void movedToScreenAt(double scale) {
            screenScale = scale;
        }

        void painted() {
            remeasureUnlessMeasuredAt(screenScale);
        }

        double measuredScale() {
            return getFontMetricsForTokenType(TokenTypes.IDENTIFIER).getFontRenderContext().getTransform().getScaleX();
        }
    }

    @Test
    void metricsMeasuredOnAnotherScreenAreRebuiltAtFirstPaint() {
        ScreenTextArea area = new ScreenTextArea();
        assertEquals(1.0, area.measuredScale(), "the library's own measurement, from before the editor was shown");

        area.movedToScreenAt(1.25);
        area.painted();

        assertEquals(1.25, area.measuredScale());
        assertEquals(1, area.fontApplications);
    }

    @Test
    void paintingOnTheScreenTheMetricsCameFromDoesNothing() {
        ScreenTextArea area = new ScreenTextArea();

        area.painted();
        area.painted();

        assertEquals(0, area.fontApplications);
    }

    @Test
    void everyScreenChangeRemeasures() {
        ScreenTextArea area = new ScreenTextArea();
        Font before = area.getFont();

        area.movedToScreenAt(1.25);
        area.painted();
        area.movedToScreenAt(1.0);
        area.painted();
        area.painted();

        assertEquals(1.0, area.measuredScale());
        assertEquals(2, area.fontApplications);
        assertSame(before, area.getFont(), "the font itself is unchanged; only its metrics are rebuilt");
    }

    @Test
    void fontSizeChangeReachesEveryKindOfEditor() {
        KalixPlainTextArea plain = new KalixPlainTextArea(5, 20);
        KalixIniTextArea ini = new KalixIniTextArea(5, 20);
        int newSize = plain.getFont().getSize() + 3;
        assertNotEquals(newSize, ini.getFont().getSize());

        KalixTextArea.updateAllFontSizes(newSize);

        assertEquals(newSize, plain.getFont().getSize());
        assertEquals(newSize, ini.getFont().getSize());
    }
}
