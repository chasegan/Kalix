package com.kalix.ide.components;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins two contracts of the shared editor base class.
 *
 * <p>Issue #162: an editor painted at a device scale other than the one its font
 * metrics were measured at re-applies its font, which is RSyntaxTextArea's public
 * route to rebuilding the metrics it caches per screen. Runs headless, so the paint
 * is stood in for by the scale check it delegates to, fed a {@code Graphics2D} with
 * the transform a scaled screen would carry.
 *
 * <p>Font-size preference: one registry reaches every live editor, plain and INI alike.
 * The session log area used to sit in a registry nothing swept. The registry is
 * process-wide and the tests run sequentially, so a sweep in one test cannot be
 * observed by another.
 */
class KalixTextAreaTest {

    /** Counts font applications after construction. */
    private static final class CountingTextArea extends KalixPlainTextArea {
        private int fontApplications;
        private boolean constructed;

        CountingTextArea() {
            super(5, 20);
            constructed = true;
        }

        @Override
        public void setFont(Font font) {
            if (constructed) {
                fontApplications++;
            }
            super.setFont(font);
        }

        void paintedAt(double scale) {
            Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            g.scale(scale, scale);
            remeasureIfScaleChanged(g);
            g.dispose();
        }
    }

    @Test
    void firstPaintLeavesTheMeasurementToTheLibrary() {
        CountingTextArea area = new CountingTextArea();

        area.paintedAt(1.25);

        assertEquals(0, area.fontApplications);
    }

    @Test
    void repaintingAtTheSameScaleDoesNothing() {
        CountingTextArea area = new CountingTextArea();

        area.paintedAt(1.25);
        area.paintedAt(1.25);

        assertEquals(0, area.fontApplications);
    }

    @Test
    void paintingAtAnotherScaleReappliesTheCurrentFont() {
        CountingTextArea area = new CountingTextArea();
        Font before = area.getFont();

        area.paintedAt(1.25);
        area.paintedAt(1.5);

        assertEquals(1, area.fontApplications);
        assertSame(before, area.getFont(), "the font itself is unchanged; only its metrics are rebuilt");
    }

    @Test
    void everyScaleChangeRemeasures() {
        CountingTextArea area = new CountingTextArea();

        area.paintedAt(1.25);
        area.paintedAt(1.5);
        area.paintedAt(1.25);

        assertEquals(2, area.fontApplications);
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
