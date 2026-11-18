package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JToggleButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the map toolbar: its buttons change and follow their own map, and no other. */
class MapToolBarTest {

    private static MapView view(ModelDocument doc) {
        return assertInstanceOf(MapView.class, doc.getContextView());
    }

    private static MapToolBar toolBar(ModelDocument doc) {
        return assertInstanceOf(MapToolBar.class, view(doc).getComponent(0), "the toolbar sits first, at the top");
    }

    private static JToggleButton gridlinesButton(ModelDocument doc) {
        return (JToggleButton) toolBar(doc).getComponent(0);
    }

    private static JToggleButton labelsButton(ModelDocument doc) {
        return (JToggleButton) toolBar(doc).getComponent(1);
    }

    @Test
    void modelContextViewIsTheMapUnderItsToolBar() {
        ModelDocument doc = new ModelDocument();
        assertSame(doc.getMapPanel(), view(doc).getComponent(1));
    }

    @Test
    void buttonsFollowTheMap() {
        ModelDocument doc = new ModelDocument();
        MapPanel map = doc.getMapPanel();

        map.setShowGridlines(false);
        assertFalse(gridlinesButton(doc).isSelected());
        assertEquals("Gridlines hidden - click to show", gridlinesButton(doc).getToolTipText());
        map.setShowGridlines(true);
        assertTrue(gridlinesButton(doc).isSelected());

        map.setLabelMode(NodeLabelMode.AKA);
        assertTrue(labelsButton(doc).isSelected());
        assertEquals("Node labels: akas only - click to hide", labelsButton(doc).getToolTipText());
        map.setLabelMode(NodeLabelMode.HIDDEN);
        assertFalse(labelsButton(doc).isSelected());
    }

    @Test
    void labelIconChangesWithTheMode() {
        ModelDocument doc = new ModelDocument();
        Icon names = labelsButton(doc).getIcon();
        doc.getMapPanel().setLabelMode(NodeLabelMode.AKA);
        assertNotSame(names, labelsButton(doc).getIcon());
    }

    @Test
    void clicksChangeTheirOwnMap() {
        ModelDocument doc = new ModelDocument();
        MapPanel map = doc.getMapPanel();
        map.setShowGridlines(true);
        map.setLabelMode(NodeLabelMode.NAMES);

        gridlinesButton(doc).doClick();
        labelsButton(doc).doClick();

        assertFalse(map.isShowGridlines());
        assertFalse(gridlinesButton(doc).isSelected());
        assertEquals(NodeLabelMode.AKA, map.getLabelMode());
        assertTrue(labelsButton(doc).isSelected());
    }

    @Test
    void clicksLeaveOtherMapsAlone() {
        ModelDocument clicked = new ModelDocument();
        ModelDocument other = new ModelDocument();

        gridlinesButton(clicked).doClick();
        labelsButton(clicked).doClick();

        assertTrue(other.getMapPanel().isShowGridlines());
        assertTrue(gridlinesButton(other).isSelected());
        assertEquals(NodeLabelMode.NAMES, other.getMapPanel().getLabelMode());
        assertEquals("Node labels: names - click to show akas only", labelsButton(other).getToolTipText());
    }

    @Test
    void themeChangeRebuildsTheIcons() {
        ModelDocument doc = new ModelDocument();
        Icon before = gridlinesButton(doc).getIcon();
        toolBar(doc).updateUI();
        assertNotSame(before, gridlinesButton(doc).getIcon());
    }
}
