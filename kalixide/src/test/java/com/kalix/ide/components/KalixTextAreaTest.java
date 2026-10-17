package com.kalix.ide.components;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the contract behind issue #162: an editor that moves to a screen with a
 * different graphics configuration re-applies its font, which is RSyntaxTextArea's
 * public route to rebuilding the font metrics it caches per screen. Runs headless, so
 * the configurations are stand-ins and the change is delivered through the same
 * property the JDK fires.
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

        void screenChanged(GraphicsConfiguration from, GraphicsConfiguration to) {
            firePropertyChange("graphicsConfiguration", from, to);
        }
    }

    /** A configuration that stands for one screen; identity is all the test needs. */
    private static GraphicsConfiguration screen() {
        return new GraphicsConfiguration() {
            @Override public GraphicsDevice getDevice() { return null; }
            @Override public ColorModel getColorModel() { return null; }
            @Override public ColorModel getColorModel(int transparency) { return null; }
            @Override public AffineTransform getDefaultTransform() { return new AffineTransform(); }
            @Override public AffineTransform getNormalizingTransform() { return new AffineTransform(); }
            @Override public Rectangle getBounds() { return new Rectangle(); }
        };
    }

    @Test
    void movingToAnotherScreenReappliesTheCurrentFont() {
        CountingTextArea area = new CountingTextArea();
        Font before = area.getFont();

        area.screenChanged(screen(), screen());

        assertEquals(1, area.fontApplications);
        assertSame(before, area.getFont(), "the font itself is unchanged; only its metrics are rebuilt");
    }

    @Test
    void firstScreenIsLeftToTheFirstPaint() {
        CountingTextArea area = new CountingTextArea();

        area.screenChanged(null, screen());

        assertEquals(0, area.fontApplications);
    }

    @Test
    void leavingTheHierarchyDoesNothing() {
        CountingTextArea area = new CountingTextArea();

        area.screenChanged(screen(), null);

        assertEquals(0, area.fontApplications);
    }
}
