package com.kalix.ide.builders;

import com.kalix.ide.MapPanel;
import com.kalix.ide.document.ModelDocument;
import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.preferences.PreferencesForTests;
import com.kalix.ide.rendering.NodeLabelMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Pins the View menu's map items: they show and change the active map alone, and save nothing. */
class MapViewMenuTest {

    private File prefsFile;
    private MapPanel activeMap;
    private JMenu viewMenu;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        prefsFile = tempDir.resolve("prefs.json").toFile();
        PreferencesForTests.redirect(prefsFile);
        MenuBarBuilder.MenuBarCallbacks callbacks = (MenuBarBuilder.MenuBarCallbacks) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {MenuBarBuilder.MenuBarCallbacks.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getActiveMapPanel")) {
                    return activeMap;
                }
                return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            });
        JMenuBar menuBar = new MenuBarBuilder(callbacks, null).buildMenuBar();
        viewMenu = menu(menuBar, "View");
    }

    private static JMenu menu(JMenuBar menuBar, String text) {
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            if (text.equals(menuBar.getMenu(i).getText())) {
                return menuBar.getMenu(i);
            }
        }
        return fail("no menu " + text);
    }

    private static JMenuItem item(JMenu menu, String text) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i); // null for a separator
            if (item != null && text.equals(item.getText())) {
                return item;
            }
        }
        return fail("no item " + text);
    }

    private JMenuItem gridlinesItem() {
        return item(viewMenu, "Show Gridlines");
    }

    private JMenu labelsMenu() {
        return (JMenu) item(viewMenu, "Node Labels");
    }

    private JMenuItem labelItem(NodeLabelMode mode) {
        return item(labelsMenu(), mode.displayName());
    }

    private void openViewMenu() {
        for (MenuListener listener : viewMenu.getMenuListeners()) {
            listener.menuSelected(new MenuEvent(viewMenu));
        }
    }

    @Test
    void itemsShowTheActiveMap() {
        activeMap = new ModelDocument().getMapPanel();
        activeMap.setShowGridlines(false);
        activeMap.setLabelMode(NodeLabelMode.AKA);

        openViewMenu();

        assertTrue(gridlinesItem().isEnabled());
        assertTrue(labelsMenu().isEnabled());
        assertFalse(gridlinesItem().isSelected());
        assertTrue(labelItem(NodeLabelMode.AKA).isSelected());
        assertFalse(labelItem(NodeLabelMode.NAMES).isSelected());

        activeMap.setShowGridlines(true);
        activeMap.setLabelMode(NodeLabelMode.HIDDEN);
        openViewMenu();

        assertTrue(gridlinesItem().isSelected());
        assertTrue(labelItem(NodeLabelMode.HIDDEN).isSelected());
        assertFalse(labelItem(NodeLabelMode.AKA).isSelected());
    }

    @Test
    void itemsChangeOnlyTheActiveMapAndSaveNothing() {
        ModelDocument active = new ModelDocument();
        ModelDocument other = new ModelDocument();
        activeMap = active.getMapPanel();
        boolean savedGridlines = PreferenceKeys.MAP_SHOW_GRIDLINES.get();
        String savedLabelMode = PreferenceKeys.MAP_LABEL_MODE.get();
        boolean prefsFileExisted = prefsFile.exists();
        long prefsFileModified = prefsFile.lastModified();

        openViewMenu();
        gridlinesItem().doClick();
        labelItem(NodeLabelMode.HIDDEN).doClick();

        assertFalse(activeMap.isShowGridlines());
        assertEquals(NodeLabelMode.HIDDEN, activeMap.getLabelMode());
        assertTrue(other.getMapPanel().isShowGridlines());
        assertEquals(NodeLabelMode.NAMES, other.getMapPanel().getLabelMode());

        assertEquals(savedGridlines, PreferenceKeys.MAP_SHOW_GRIDLINES.get());
        assertEquals(savedLabelMode, PreferenceKeys.MAP_LABEL_MODE.get());
        assertEquals(prefsFileExisted, prefsFile.exists(), "no preference was written");
        assertEquals(prefsFileModified, prefsFile.lastModified(), "no preference was written");
    }

    @Test
    void noMapDisablesTheItems() {
        activeMap = new ModelDocument().getMapPanel();
        openViewMenu();
        activeMap = null;

        openViewMenu();

        assertFalse(gridlinesItem().isEnabled());
        assertFalse(labelsMenu().isEnabled());
    }
}
