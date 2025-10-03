package com.kalix.ide.rendering;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import com.kalix.ide.themes.NodeTheme;
import com.kalix.ide.constants.UIConstants;

/**
 * Dedicated renderer for node shapes with consistent sizing and positioning.
 *
 * This class handles rendering of different node shapes (circles, triangles, squares, diamonds,
 * water drops, and podiums) with 2-character text labels inside each shape. All shapes are
 * rendered within a consistent bounding box for uniform appearance.
 *
 * @author Claude Code Assistant
 * @version 1.0
 */
public class NodeShapeRenderer {

    // Shape sizing constants - all shapes fit within NODE_SIZE x NODE_SIZE box
    private static final int NODE_SIZE = UIConstants.Map.NODE_SIZE;
    private static final int NODE_RADIUS = UIConstants.Map.NODE_RADIUS;

    // Shape-specific sizing for visual balance
    private static final int SQUARE_SIZE = 18; // Slightly smaller than full box for visual balance
    private static final int DIAMOND_SIZE = 18; // Same as square before rotation
    private static final double TRIANGLE_HEIGHT = 17.3 * 1.3; // 30% larger than original
    private static final double TRIANGLE_WIDTH = 20.0 * 1.3; // 30% larger than original

    // Polar offset system for shape positioning (distance in pixels, angle in degrees)
    // Angle: 0° = right, 90° = up, 180° = left, 270° = down

    // Shape positioning offsets (corrected naming)
    private static final double TRIANGLE_DOWN_OFFSET_DISTANCE = 0.0; // No offset
    private static final double TRIANGLE_DOWN_OFFSET_ANGLE = 270.0;  // Down (toward flat edge)

    private static final double TRIANGLE_UP_OFFSET_DISTANCE = 0.0;   // No offset
    private static final double TRIANGLE_UP_OFFSET_ANGLE = 90.0;     // Up (toward flat edge)

    private static final double TRIANGLE_RIGHT_OFFSET_DISTANCE = 0.0; // No offset
    private static final double TRIANGLE_RIGHT_OFFSET_ANGLE = 0.0;    // Right (toward flat edge)

    private static final double TRIANGLE_LEFT_OFFSET_DISTANCE = 0.0;  // No offset
    private static final double TRIANGLE_LEFT_OFFSET_ANGLE = 180.0;   // Left (toward flat edge)

    private static final double CIRCLE_OFFSET_DISTANCE = 0.0;        // No offset needed
    private static final double CIRCLE_OFFSET_ANGLE = 0.0;

    private static final double SQUARE_OFFSET_DISTANCE = 0.0;        // No offset needed
    private static final double SQUARE_OFFSET_ANGLE = 0.0;

    private static final double DIAMOND_OFFSET_DISTANCE = 0.0;       // No offset needed
    private static final double DIAMOND_OFFSET_ANGLE = 0.0;

    private static final double WATER_DROP_OFFSET_DISTANCE = 0.0;    // No offset needed
    private static final double WATER_DROP_OFFSET_ANGLE = 0.0;

    private static final double PODIUM_OFFSET_DISTANCE = 1.0; // 1px offset
    private static final double PODIUM_OFFSET_ANGLE = 90.0;   // 90 degrees (upward)

    /**
     * Calculates X,Y offset from polar coordinates
     */
    private static double[] calculateOffset(double distance, double angleDegrees) {
        double angleRadians = Math.toRadians(angleDegrees);
        double offsetX = distance * Math.cos(angleRadians);
        double offsetY = -distance * Math.sin(angleRadians); // Negative because screen Y increases downward
        return new double[]{offsetX, offsetY};
    }

    // Pre-calculated offset values for performance
    private static final double[] TRIANGLE_DOWN_OFFSET = calculateOffset(TRIANGLE_DOWN_OFFSET_DISTANCE, TRIANGLE_DOWN_OFFSET_ANGLE);
    private static final double[] TRIANGLE_UP_OFFSET = calculateOffset(TRIANGLE_UP_OFFSET_DISTANCE, TRIANGLE_UP_OFFSET_ANGLE);
    private static final double[] TRIANGLE_RIGHT_OFFSET = calculateOffset(TRIANGLE_RIGHT_OFFSET_DISTANCE, TRIANGLE_RIGHT_OFFSET_ANGLE);
    private static final double[] TRIANGLE_LEFT_OFFSET = calculateOffset(TRIANGLE_LEFT_OFFSET_DISTANCE, TRIANGLE_LEFT_OFFSET_ANGLE);
    private static final double[] CIRCLE_OFFSET = calculateOffset(CIRCLE_OFFSET_DISTANCE, CIRCLE_OFFSET_ANGLE);
    private static final double[] SQUARE_OFFSET = calculateOffset(SQUARE_OFFSET_DISTANCE, SQUARE_OFFSET_ANGLE);
    private static final double[] DIAMOND_OFFSET = calculateOffset(DIAMOND_OFFSET_DISTANCE, DIAMOND_OFFSET_ANGLE);
    private static final double[] WATER_DROP_OFFSET = calculateOffset(WATER_DROP_OFFSET_DISTANCE, WATER_DROP_OFFSET_ANGLE);
    private static final double[] PODIUM_OFFSET = calculateOffset(PODIUM_OFFSET_DISTANCE, PODIUM_OFFSET_ANGLE);

    // Pre-calculated triangle points for performance (relative to geometric center)
    // All triangles are equilateral and positioned so their centroid is at (0,0)

    // For equilateral triangle: centroid is at 1/3 height from base, 2/3 height from opposite vertex
    private static final double TRIANGLE_CENTROID_OFFSET = TRIANGLE_HEIGHT / 3.0;

    // Calculate offsets using polar coordinates (already defined above)

    // Triangle with flat top (pointing down ▽) - equilateral, centered at centroid with polar offset
    private static final int[] TRIANGLE_DOWN_X = {0, (int)(TRIANGLE_WIDTH/2), (int)(-TRIANGLE_WIDTH/2)};
    private static final int[] TRIANGLE_DOWN_Y = {
        (int)(TRIANGLE_CENTROID_OFFSET * 2 + TRIANGLE_DOWN_OFFSET[1]),  // Bottom vertex (2/3 height below centroid)
        (int)(-TRIANGLE_CENTROID_OFFSET + TRIANGLE_DOWN_OFFSET[1]),     // Top right vertex (1/3 height above centroid)
        (int)(-TRIANGLE_CENTROID_OFFSET + TRIANGLE_DOWN_OFFSET[1])      // Top left vertex (1/3 height above centroid)
    };

    // Triangle with flat bottom (pointing up ▲) - equilateral, centered at centroid with polar offset
    private static final int[] TRIANGLE_UP_X = {0, (int)(TRIANGLE_WIDTH/2), (int)(-TRIANGLE_WIDTH/2)};
    private static final int[] TRIANGLE_UP_Y = {
        (int)(-TRIANGLE_CENTROID_OFFSET * 2 + TRIANGLE_UP_OFFSET[1]), // Top vertex (2/3 height above centroid)
        (int)(TRIANGLE_CENTROID_OFFSET + TRIANGLE_UP_OFFSET[1]),      // Bottom right vertex (1/3 height below centroid)
        (int)(TRIANGLE_CENTROID_OFFSET + TRIANGLE_UP_OFFSET[1])       // Bottom left vertex (1/3 height below centroid)
    };

    // Triangle with flat left (pointing right) - equilateral, centered at centroid with polar offset
    private static final int[] TRIANGLE_RIGHT_X = {
        (int)(TRIANGLE_CENTROID_OFFSET * 2 + TRIANGLE_RIGHT_OFFSET[0]), // Right vertex (2/3 height right of centroid)
        (int)(-TRIANGLE_CENTROID_OFFSET + TRIANGLE_RIGHT_OFFSET[0]),    // Left top vertex (1/3 height left of centroid)
        (int)(-TRIANGLE_CENTROID_OFFSET + TRIANGLE_RIGHT_OFFSET[0])     // Left bottom vertex (1/3 height left of centroid)
    };
    private static final int[] TRIANGLE_RIGHT_Y = {0, (int)(-TRIANGLE_WIDTH/2), (int)(TRIANGLE_WIDTH/2)};

    // Triangle with flat right (pointing left) - equilateral, centered at centroid with polar offset
    private static final int[] TRIANGLE_LEFT_X = {
        (int)(-TRIANGLE_CENTROID_OFFSET * 2 + TRIANGLE_LEFT_OFFSET[0]), // Left vertex (2/3 height left of centroid)
        (int)(TRIANGLE_CENTROID_OFFSET + TRIANGLE_LEFT_OFFSET[0]),      // Right top vertex (1/3 height right of centroid)
        (int)(TRIANGLE_CENTROID_OFFSET + TRIANGLE_LEFT_OFFSET[0])       // Right bottom vertex (1/3 height right of centroid)
    };
    private static final int[] TRIANGLE_LEFT_Y = {0, (int)(-TRIANGLE_WIDTH/2), (int)(TRIANGLE_WIDTH/2)};

    // Water drop shape - symmetric teardrop (wider at bottom, pointed at top)
    // Higher resolution (20 vertices), 20% larger, with convex curvature near point
    private static final int[] WATER_DROP_X = {
        0,    // top point
        1,    // upper convex curve right 1
        2,    // upper convex curve right 2
        4,    // upper right transition
        5,    // upper right curve
        7,    // mid-upper right curve
        9,    // mid-right curve
        10,   // lower right curve
        9,    // lower right transition
        7,    // bottom right
        4,    // bottom right inner
        2,    // bottom right center
        0,    // bottom center
        -2,   // bottom left center
        -4,   // bottom left inner
        -7,   // bottom left
        -9,   // lower left transition
        -10,  // lower left curve
        -9,   // mid-right curve
        -7,   // mid-upper left curve
        -5,   // upper left curve
        -4,   // upper left transition
        -2,   // upper convex curve left 2
        -1    // upper convex curve left 1
    };
    private static final int[] WATER_DROP_Y = {
        -16,  // top point (extended much further for very prominent tip)
        -13,  // upper convex curve right 1
        -11,  // upper convex curve right 2
        -9,   // upper right transition
        -7,   // upper right curve
        -5,   // mid-upper right curve
        -2,   // mid-right curve
        1,    // lower right curve
        4,    // lower right transition
        7,    // bottom right
        9,    // bottom right inner
        10,   // bottom right center
        10,   // bottom center
        10,   // bottom left center
        9,    // bottom left inner
        7,    // bottom left
        4,    // lower left transition
        1,    // lower left curve
        -2,   // mid-left curve
        -5,   // mid-upper left curve
        -7,   // upper left curve
        -9,   // upper left transition
        -11,  // upper convex curve left 2
        -13   // upper convex curve left 1
    };

    // Podium - 3-step podium with wider center step (5px + 7px + 6px = 18px, roughly centered)
    // Left step (2nd place), center step (1st place - tallest, 1px wider), right step (3rd place)
    private static final int[] PODIUM_X = {
        -9,   // bottom left
        9,    // bottom right
        9,    // right edge up to 3rd place
        9,    // right step top right
        3,    // right step top left / center step top right boundary
        3,    // center step top right
        -4,   // center step top left / left step top right boundary (moved 1px left for center expansion)
        -4,   // left step top right (moved 1px left for center expansion)
        -9,   // left step top left
        -9    // back to bottom left (closes shape)
    };
    private static final int[] PODIUM_Y = {
        9,    // bottom left
        9,    // bottom right
        -5,   // right edge up to 3rd place step
        -5,   // right step top (3rd place - shortest)
        -5,   // right/center boundary at 3rd place height
        -9,   // center step top (1st place - tallest)
        -9,   // center/left boundary at 1st place height
        -7,   // left step top (2nd place - medium)
        -7,   // left step top left
        9     // back to bottom left (closes shape)
    };

    /**
     * Renders a node shape with the specified parameters.
     */
    public void renderShape(Graphics2D g2d, NodeTheme.NodeShape shape, double centerX, double centerY,
                           Color fillColor, Color borderColor, BasicStroke borderStroke) {

        // Render fill
        g2d.setColor(fillColor);
        renderShapeInternal(g2d, shape, centerX, centerY, true);

        // Render border if specified
        if (borderColor != null) {
            BasicStroke originalStroke = (BasicStroke) g2d.getStroke();
            g2d.setColor(borderColor);
            if (borderStroke != null) {
                g2d.setStroke(borderStroke);
            }

            renderShapeInternal(g2d, shape, centerX, centerY, false);
            g2d.setStroke(originalStroke);
        }
    }

    /**
     * Internal method to render shapes with unified logic
     */
    private void renderShapeInternal(Graphics2D g2d, NodeTheme.NodeShape shape,
                                   double centerX, double centerY, boolean fill) {
        switch (shape) {
            case CIRCLE:
                renderCircleInternal(g2d, centerX, centerY, fill);
                break;
            case SQUARE:
                renderSquareInternal(g2d, centerX, centerY, fill);
                break;
            case DIAMOND:
                renderDiamondInternal(g2d, centerX, centerY, fill);
                break;
            case TRIANGLE_DOWN:
                renderPolygon(g2d, centerX, centerY, TRIANGLE_DOWN_X, TRIANGLE_DOWN_Y, fill);
                break;
            case TRIANGLE_UP:
                renderPolygon(g2d, centerX, centerY, TRIANGLE_UP_X, TRIANGLE_UP_Y, fill);
                break;
            case TRIANGLE_RIGHT:
                renderPolygon(g2d, centerX, centerY, TRIANGLE_RIGHT_X, TRIANGLE_RIGHT_Y, fill);
                break;
            case TRIANGLE_LEFT:
                renderPolygon(g2d, centerX, centerY, TRIANGLE_LEFT_X, TRIANGLE_LEFT_Y, fill);
                break;
            case WATER_DROP:
                renderPolygon(g2d, centerX, centerY, WATER_DROP_X, WATER_DROP_Y, fill);
                break;
            case PODIUM:
                renderPodiumInternal(g2d, centerX, centerY, fill);
                break;
        }
    }

    /**
     * Renders text inside a shape with automatic color contrast.
     *
     * @param g2d Graphics context for rendering
     * @param text Text to render (typically 2 characters)
     * @param centerX Center X coordinate of the shape
     * @param centerY Center Y coordinate of the shape
     * @param shape The shape type (affects text positioning)
     * @param backgroundColor Background color for contrast calculation
     * @param textStyle Text styling configuration
     */
    public void renderShapeText(Graphics2D g2d, String text, double centerX, double centerY,
                               NodeTheme.NodeShape shape, Color backgroundColor,
                               NodeTheme.ShapeTextStyle textStyle) {

        if (text == null || text.isEmpty()) {
            return;
        }

        // Set up font and color
        Font originalFont = g2d.getFont();
        g2d.setFont(textStyle.createFont());
        g2d.setColor(textStyle.getContrastingColor(backgroundColor));

        // Calculate text positioning
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getHeight();
        int ascent = fm.getAscent();

        // Center text in shape (with slight adjustments for visual balance)
        double textX = centerX - (textWidth / 2.0);
        double textY = centerY + (ascent / 2.0) - 1; // Slight upward adjustment for visual centering

        // Render text
        g2d.drawString(text, (float)textX, (float)textY);

        // Restore original font
        g2d.setFont(originalFont);
    }

    /**
     * Convenience method that renders shape text using theme's default text style.
     */
    public void renderShapeText(Graphics2D g2d, String text, double centerX, double centerY,
                               NodeTheme.NodeShape shape, Color backgroundColor, NodeTheme nodeTheme) {
        renderShapeText(g2d, text, centerX, centerY, shape, backgroundColor, nodeTheme.getShapeTextStyle());
    }

    // Private shape rendering methods

    /**
     * Generic polygon renderer to eliminate duplication
     */
    private void renderPolygon(Graphics2D g2d, double centerX, double centerY,
                              int[] xCoords, int[] yCoords, boolean fill) {
        int[] xPoints = new int[xCoords.length];
        int[] yPoints = new int[yCoords.length];

        for (int i = 0; i < xCoords.length; i++) {
            xPoints[i] = (int)(centerX + xCoords[i]);
            yPoints[i] = (int)(centerY + yCoords[i]);
        }

        if (fill) {
            g2d.fillPolygon(xPoints, yPoints, xCoords.length);
        } else {
            g2d.drawPolygon(xPoints, yPoints, xCoords.length);
        }
    }

    private void renderCircleInternal(Graphics2D g2d, double centerX, double centerY, boolean fill) {
        int diameter = 2 * NODE_RADIUS;
        int x = (int)(centerX - NODE_RADIUS);
        int y = (int)(centerY - NODE_RADIUS);

        if (fill) {
            g2d.fillOval(x, y, diameter, diameter);
        } else {
            g2d.drawOval(x, y, diameter, diameter);
        }
    }

    private void renderSquareInternal(Graphics2D g2d, double centerX, double centerY, boolean fill) {
        int halfSize = SQUARE_SIZE / 2;
        int x = (int)(centerX - halfSize);
        int y = (int)(centerY - halfSize);

        if (fill) {
            g2d.fillRect(x, y, SQUARE_SIZE, SQUARE_SIZE);
        } else {
            g2d.drawRect(x, y, SQUARE_SIZE, SQUARE_SIZE);
        }
    }

    private void renderDiamondInternal(Graphics2D g2d, double centerX, double centerY, boolean fill) {
        AffineTransform originalTransform = g2d.getTransform();
        g2d.translate(centerX, centerY);
        g2d.rotate(Math.PI / 4); // 45 degrees

        int halfSize = DIAMOND_SIZE / 2;
        if (fill) {
            g2d.fillRect(-halfSize, -halfSize, DIAMOND_SIZE, DIAMOND_SIZE);
        } else {
            g2d.drawRect(-halfSize, -halfSize, DIAMOND_SIZE, DIAMOND_SIZE);
        }

        g2d.setTransform(originalTransform);
    }

    private void renderPodiumInternal(Graphics2D g2d, double centerX, double centerY, boolean fill) {
        double[] offset = PODIUM_OFFSET;
        double offsetCenterX = centerX + offset[0];
        double offsetCenterY = centerY + offset[1];

        renderPolygon(g2d, offsetCenterX, offsetCenterY, PODIUM_X, PODIUM_Y, fill);
    }

}