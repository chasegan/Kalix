package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JToggleButton;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the map toolbar: its buttons follow their own map's state, clicks go through
 * the installed actions, and an unwired toolbar refuses rather than acting alone.
 */
class MapToolBarTest {

    private static MapToolBar toolBar(MapView view) {
        return assertInstanceOf(MapToolBar.class, view.getComponent(0), "the toolbar sits first, at the top");
    }

    private static JToggleButton gridlinesButton(MapView view) {
        return (JToggleButton) toolBar(view).getComponent(0);
    }

    private static JToggleButton labelsButton(MapView view) {
        return (JToggleButton) toolBar(view).getComponent(1);
    }

    @Test
    void modelContextViewIsTheMapUnderItsToolBar() {
        ModelDocument doc = new ModelDocument();
        assertSame(doc.getMapView(), doc.getContextView());
        assertSame(doc.getMapPanel(), doc.getMapView().getComponent(1));
    }

    @Test
    void buttonsFollowTheMap() {
        ModelDocument doc = new ModelDocument();
        MapPanel map = doc.getMapPanel();
        MapView view = doc.getMapView();

        map.setShowGridlines(false);
        assertFalse(gridlinesButton(view).isSelected());
        assertEquals("Gridlines hidden - click to show", gridlinesButton(view).getToolTipText());
        map.setShowGridlines(true);
        assertTrue(gridlinesButton(view).isSelected());

        map.setLabelMode(NodeLabelMode.AKA);
        assertTrue(labelsButton(view).isSelected());
        assertEquals("Node labels: akas only - click to hide", labelsButton(view).getToolTipText());
        map.setLabelMode(NodeLabelMode.HIDDEN);
        assertFalse(labelsButton(view).isSelected());
    }

    @Test
    void labelIconChangesWithTheMode() {
        ModelDocument doc = new ModelDocument();
        Icon names = labelsButton(doc.getMapView()).getIcon();
        doc.getMapPanel().setLabelMode(NodeLabelMode.AKA);
        assertNotSame(names, labelsButton(doc.getMapView()).getIcon());
    }

    @Test
    void clicksGoThroughTheInstalledActions() {
        ModelDocument doc = new ModelDocument();
        MapPanel map = doc.getMapPanel();
        MapView view = doc.getMapView();
        map.setShowGridlines(true);
        map.setLabelMode(NodeLabelMode.NAMES);
        List<Boolean> gridlines = new ArrayList<>();
        List<NodeLabelMode> modes = new ArrayList<>();
        view.setDisplayActions(gridlines::add, modes::add);

        gridlinesButton(view).doClick();
        labelsButton(view).doClick();

        assertEquals(List.of(false), gridlines, "asks for the opposite of the map's gridlines");
        assertEquals(List.of(NodeLabelMode.AKA), modes, "asks for the mode after the map's");
        // The recording actions leave the map unchanged, so the buttons must show that.
        assertTrue(gridlinesButton(view).isSelected());
        assertTrue(labelsButton(view).isSelected());
    }

    @Test
    void everyToolBarFollowsASettingAppliedToAllMaps() {
        ModelDocument first = new ModelDocument();
        ModelDocument second = new ModelDocument();
        List<ModelDocument> docs = List.of(first, second);
        for (ModelDocument doc : docs) {
            doc.getMapView().setDisplayActions(
                show -> docs.forEach(d -> d.getMapPanel().setShowGridlines(show)),
                mode -> docs.forEach(d -> d.getMapPanel().setLabelMode(mode)));
        }

        gridlinesButton(first.getMapView()).doClick();
        labelsButton(first.getMapView()).doClick();

        assertFalse(gridlinesButton(second.getMapView()).isSelected());
        assertEquals("Node labels: akas only - click to hide", labelsButton(second.getMapView()).getToolTipText());
    }

    @Test
    void clickBeforeWiringThrows() {
        ModelDocument doc = new ModelDocument();
        assertThrows(IllegalStateException.class, () -> gridlinesButton(doc.getMapView()).doClick());
        assertTrue(doc.getMapPanel().isShowGridlines(), "the map is untouched");
    }

    @Test
    void themeChangeRebuildsTheIcons() {
        ModelDocument doc = new ModelDocument();
        Icon before = gridlinesButton(doc.getMapView()).getIcon();
        toolBar(doc.getMapView()).updateUI();
        assertNotSame(before, gridlinesButton(doc.getMapView()).getIcon());
    }
}
