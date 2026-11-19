package com.kalix.ide;

import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.icons.HollowIcon;
import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.Icon;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;

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
        return assertInstanceOf(MapToolBar.class, view(doc).getComponent(0), "the toolbar is the top layer");
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
    void mapFillsTheViewAndTheToolBarFloatsTopRight() {
        ModelDocument doc = new ModelDocument();
        MapView view = view(doc);
        view.setSize(600, 400);
        view.doLayout();

        assertEquals(new Rectangle(0, 0, 600, 400), doc.getMapPanel().getBounds());
        Dimension size = toolBar(doc).getPreferredSize();
        assertEquals(new Rectangle(600 - size.width - MapView.TOOLBAR_INSET, MapView.TOOLBAR_INSET,
            size.width, size.height), toolBar(doc).getBounds());
        assertTrue(view.getLayer(toolBar(doc)) > view.getLayer(doc.getMapPanel()), "the toolbar paints over the map");
    }

    @Test
    void wheelOverTheToolBarReachesTheMapInMapCoordinates() {
        ModelDocument doc = new ModelDocument();
        MapView view = view(doc);
        view.setSize(600, 400);
        view.doLayout();
        List<MouseWheelEvent> received = new ArrayList<>();
        doc.getMapPanel().addMouseWheelListener(received::add);
        MapToolBar bar = toolBar(doc);

        bar.dispatchEvent(new MouseWheelEvent(bar, MouseEvent.MOUSE_WHEEL, 0L, 0, 5, 7, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1));

        assertEquals(1, received.size());
        assertEquals(new Point(bar.getX() + 5, bar.getY() + 7), received.get(0).getPoint());
        assertSame(doc.getMapPanel(), received.get(0).getComponent());
    }

    @Test
    void toolBarPaddingFallsThroughToTheMap() {
        ModelDocument doc = new ModelDocument();
        MapView view = view(doc);
        view.setSize(600, 400);
        view.doLayout();
        toolBar(doc).doLayout();
        MapToolBar bar = toolBar(doc);

        assertFalse(bar.contains(1, 1), "the padding is not the toolbar's");
        assertSame(doc.getMapPanel(), SwingUtilities.getDeepestComponentAt(view, bar.getX() + 1, bar.getY() + 1));
    }

    @Test
    void buttonsStillTakeTheMouse() {
        ModelDocument doc = new ModelDocument();
        MapView view = view(doc);
        view.setSize(600, 400);
        view.doLayout();
        toolBar(doc).doLayout();
        MapToolBar bar = toolBar(doc);
        JToggleButton button = labelsButton(doc);
        Point centre = new Point(button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2);

        assertTrue(bar.contains(centre.x, centre.y));
        assertSame(button, SwingUtilities.getDeepestComponentAt(view, bar.getX() + centre.x, bar.getY() + centre.y));
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
    void labelButtonKeepsItsSizeInEveryMode() {
        ModelDocument doc = new ModelDocument();
        Dimension names = labelsButton(doc).getPreferredSize();
        for (NodeLabelMode mode : NodeLabelMode.values()) {
            doc.getMapPanel().setLabelMode(mode);
            assertEquals(names, labelsButton(doc).getPreferredSize(), mode.name());
        }
    }

    @Test
    void hiddenLabelsShowAHollowTag() {
        ModelDocument doc = new ModelDocument();
        assertInstanceOf(FontIcon.class, labelsButton(doc).getIcon(), "names: the solid tag");
        doc.getMapPanel().setLabelMode(NodeLabelMode.HIDDEN);
        assertInstanceOf(HollowIcon.class, labelsButton(doc).getIcon());
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
