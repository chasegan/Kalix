package com.kalix.ide.themes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for NodeTheme shape and text functionality.
 */
public class NodeThemeShapeTest {

    @Test
    public void testNodeTypeShapeMappings() {
        NodeTheme theme = new NodeTheme();

        // Test known node types
        assertEquals(NodeTheme.NodeShape.ARROW_DOWN, theme.getShapeForNodeType("inflow"));
        assertEquals(NodeTheme.NodeShape.WATER_DROP, theme.getShapeForNodeType("gr4j"));
        assertEquals(NodeTheme.NodeShape.DIAMOND, theme.getShapeForNodeType("routing"));
        assertEquals(NodeTheme.NodeShape.WATER_DROP, theme.getShapeForNodeType("sacramento"));
        assertEquals(NodeTheme.NodeShape.PODIUM, theme.getShapeForNodeType("user"));
        assertEquals(NodeTheme.NodeShape.TRIANGLE_UP, theme.getShapeForNodeType("storage"));
        assertEquals(NodeTheme.NodeShape.CIRCLE, theme.getShapeForNodeType("blackhole"));
        assertEquals(NodeTheme.NodeShape.CIRCLE, theme.getShapeForNodeType("confluence"));
        assertEquals(NodeTheme.NodeShape.SQUARE, theme.getShapeForNodeType("loss"));
        assertEquals(NodeTheme.NodeShape.CIRCLE, theme.getShapeForNodeType("gauge"));
        assertEquals(NodeTheme.NodeShape.CIRCLE, theme.getShapeForNodeType("splitter"));

        // Test unknown node type falls back to circle
        assertEquals(NodeTheme.NodeShape.CIRCLE, theme.getShapeForNodeType("unknown_type"));
    }

    @Test
    public void testAllTriangleShapesExist() {
        // Test that all four triangle orientations are available
        assertNotNull(NodeTheme.NodeShape.TRIANGLE_DOWN);  // ▽ flat bottom
        assertNotNull(NodeTheme.NodeShape.TRIANGLE_UP);    // △ flat top
        assertNotNull(NodeTheme.NodeShape.TRIANGLE_RIGHT); // ▷ flat left
        assertNotNull(NodeTheme.NodeShape.TRIANGLE_LEFT);  // ◁ flat right
    }

    @Test
    public void testCustomShapesExist() {
        // Test that custom shapes are available
        assertNotNull(NodeTheme.NodeShape.WATER_DROP);     // 💧 water drop
        assertNotNull(NodeTheme.NodeShape.PODIUM);         // 🏆 podium
        assertNotNull(NodeTheme.NodeShape.ARROW_DOWN);     // ⬇ arrow down
    }

    @Test
    public void testAllShapeTypesCount() {
        // Test that we have the expected number of shape types
        NodeTheme.NodeShape[] shapes = NodeTheme.NodeShape.values();
        assertEquals(10, shapes.length); // 4 triangles + circle + square + diamond + water drop + podium + arrow down
    }

    @Test
    public void testNodeTypeTextMappings() {
        NodeTheme theme = new NodeTheme();

        // Test known node types
        assertEquals("In", theme.getShapeTextForNodeType("inflow"));
        assertEquals("G4", theme.getShapeTextForNodeType("gr4j"));
        assertEquals("Rt", theme.getShapeTextForNodeType("routing"));
        assertEquals("Sc", theme.getShapeTextForNodeType("sacramento"));
        assertEquals("Us", theme.getShapeTextForNodeType("user"));
        assertEquals("St", theme.getShapeTextForNodeType("storage"));
        assertEquals("Bh", theme.getShapeTextForNodeType("blackhole"));
        assertEquals("Co", theme.getShapeTextForNodeType("confluence"));
        assertEquals("Lo", theme.getShapeTextForNodeType("loss"));
        assertEquals("Ga", theme.getShapeTextForNodeType("gauge"));
        assertEquals("Sp", theme.getShapeTextForNodeType("splitter"));

        // Test unknown node type generates abbreviation
        assertEquals("UT", theme.getShapeTextForNodeType("unknown_type")); // "Unknown" + "Type"
        assertEquals("RE", theme.getShapeTextForNodeType("reservoir"));      // "REservoir"
        assertEquals("PS", theme.getShapeTextForNodeType("pump_station"));   // "Pump" + "Station"
        assertEquals("??", theme.getShapeTextForNodeType("")); // Empty string fallback
    }

    @Test
    public void testShapeTextStyle() {
        NodeTheme theme = new NodeTheme();
        NodeTheme.ShapeTextStyle style = theme.getShapeTextStyle();

        assertNotNull(style);
        assertEquals(8, style.getFontSize());
        assertTrue(style.isBold());

        // Test font creation
        assertNotNull(style.createFont());
        assertEquals("SansSerif", style.createFont().getName());
    }

    @Test
    public void testShapeTextMapping() {
        NodeTheme theme = new NodeTheme();

        // Test complete mapping
        NodeTheme.ShapeTextMapping mapping = theme.getShapeTextMapping("storage");
        assertNotNull(mapping);
        assertEquals(NodeTheme.NodeShape.TRIANGLE_UP, mapping.getShape());
        assertEquals("St", mapping.getText());

        // Test null for unknown type
        assertNull(theme.getShapeTextMapping("unknown_type"));
    }
}