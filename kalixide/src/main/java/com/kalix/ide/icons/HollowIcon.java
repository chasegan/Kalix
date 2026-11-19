package com.kalix.ide.icons;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.IkonResolverProvider;
import org.kordamp.ikonli.swing.FontIcon;
import org.kordamp.ikonli.swing.SwingFontLoader;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;

/**
 * An icon font glyph drawn as its outline only: the hollow form of a solid glyph, for an
 * "off" state where the free icon set has no outlined version (Font Awesome's free regular
 * set has no tag, for one). It takes the solid {@code FontIcon}'s box and draws the glyph
 * where that icon does, so swapping one for the other moves and resizes nothing.
 */
public final class HollowIcon implements Icon {

    private static final float STROKE_WIDTH = 1.2f;

    private final Shape outline;
    private final int width;
    private final int height;
    private final Color color;

    private HollowIcon(Shape outline, int width, int height, Color color) {
        this.outline = outline;
        this.width = width;
        this.height = height;
        this.color = color;
    }

    /** The outline of {@code ikon}'s glyph, as {@code FontIcon.of(ikon, size)} would place it. */
    public static HollowIcon of(Ikon ikon, int size, Color color) {
        FontIcon solid = FontIcon.of(ikon, size, color);
        Font font = font(ikon).deriveFont(Font.PLAIN, size); // as FontIcon derives it
        // FontIcon draws the glyph on its font's ascent, measured on an image graphics.
        Graphics2D measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        FontMetrics metrics = measure.getFontMetrics(font);
        GlyphVector glyph = font.createGlyphVector(measure.getFontRenderContext(),
            new String(Character.toChars(ikon.getCode())));
        measure.dispose();
        Shape outline = glyph.getOutline(0, metrics.getAscent());
        return new HollowIcon(outline, solid.getIconWidth(), solid.getIconHeight(), color);
    }

    /** The glyph's font, resolved and loaded as {@code FontIcon} does it. */
    private static Font font(Ikon ikon) {
        return (Font) IkonResolverProvider.getInstance(SwingFontLoader.getInstance())
            .resolve(ikon.getDescription()).getFont();
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.translate(x, y);
        g2.draw(outline);
        g2.dispose();
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }
}
