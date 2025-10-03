package com.kalix.ide.rendering;

import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import com.kalix.ide.themes.NodeTheme;

/**
 * Integration test for NodeShapeRenderer to verify all shapes render without errors.
 */
public class NodeShapeIntegrationTest {

    @Test
    public void testAllShapesRenderWithoutErrors() {
        NodeShapeRenderer renderer = new NodeShapeRenderer();
        NodeTheme theme = new NodeTheme();

        // Create a test graphics context
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Test each shape type
        NodeTheme.NodeShape[] shapes = NodeTheme.NodeShape.values();
        Color fillColor = Color.BLUE;
        Color borderColor = Color.BLACK;
        BasicStroke borderStroke = new BasicStroke(2.0f);

        for (int i = 0; i < shapes.length; i++) {
            NodeTheme.NodeShape shape = shapes[i];
            double x = 50 + (i * 10); // Spread shapes across image
            double y = 50;

            try {
                // Test shape rendering
                renderer.renderShape(g2d, shape, x, y, fillColor, borderColor, borderStroke);

                // Test text rendering
                renderer.renderShapeText(g2d, "AB", x, y, shape, fillColor, theme);

            } catch (Exception e) {
                throw new AssertionError("Failed to render shape: " + shape, e);
            }
        }

        g2d.dispose();
    }

    @Test
    public void testAllNodeTypesHaveValidMappings() {
        NodeTheme theme = new NodeTheme();
        String[] nodeTypes = {"inflow", "gr4j", "routing_node", "sacramento", "user", "storage", "blackhole"};

        for (String nodeType : nodeTypes) {
            // Verify each node type has valid shape and text mappings
            NodeTheme.NodeShape shape = theme.getShapeForNodeType(nodeType);
            String text = theme.getShapeTextForNodeType(nodeType);

            // Shape should not be null
            assert shape != null : "Shape is null for node type: " + nodeType;

            // Text should not be null or empty
            assert text != null && !text.trim().isEmpty() : "Text is null or empty for node type: " + nodeType;

            // Text should be 2 characters or less for visual balance
            assert text.length() <= 2 : "Text too long for node type " + nodeType + ": " + text;
        }
    }
}