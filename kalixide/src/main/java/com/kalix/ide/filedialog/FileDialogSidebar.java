package com.kalix.ide.filedialog;

import com.kalix.ide.preferences.PreferenceKeys;
import com.kalix.ide.utils.ThemeUtils;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome6.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The dialogs' left-hand sidebar: pinned folders, standard places, mounted volumes, and
 * recent folders. Pins are team-shareable file preferences; recents are machine-local
 * (see {@link FileDialogHistory}).
 *
 * <p>Deliberately synthesized rather than read from the OS sidebar: the standard places
 * cover what native sidebars mostly hold, without the fragile, platform-specific favourite
 * formats (macOS's is undocumented and has changed repeatedly). The valuable tier is the
 * Kalix-owned pins.
 */
final class FileDialogSidebar {

    /** One sidebar row: a section header (path == null) or a navigable location. */
    private record Item(String label, Path path, Ikon glyph) {
        boolean isHeader() {
            return path == null;
        }
    }

    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(model);
    private final JScrollPane scroll;
    private final Consumer<Path> onNavigate;

    FileDialogSidebar(Consumer<Path> onNavigate) {
        this.onNavigate = onNavigate;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SidebarRenderer());
        list.setFixedCellHeight(-1);
        rebuild();

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Item item = list.getSelectedValue();
            if (item == null) {
                return;
            }
            if (item.isHeader()) {
                list.clearSelection();
            } else {
                onNavigate.accept(item.path());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });

        scroll = new JScrollPane(list,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
            UIManager.getColor("Component.borderColor")));
        scroll.setPreferredSize(new Dimension(185, 10));
        scroll.setMinimumSize(new Dimension(140, 10));
    }

    JComponent component() {
        return scroll;
    }

    /** Clears any highlighted row (called when navigation happens elsewhere in the dialog). */
    void clearSelection() {
        list.clearSelection();
    }

    /** Rebuilds all sections from preferences and the filesystem. */
    void rebuild() {
        model.clear();

        List<String> pinned = PreferenceKeys.FILE_DIALOG_PINNED.get();
        if (!pinned.isEmpty()) {
            model.addElement(new Item("Pinned", null, null));
            for (String p : pinned) {
                Path path = Path.of(p);
                model.addElement(new Item(labelFor(path), path, FontAwesomeSolid.THUMBTACK));
            }
        }

        model.addElement(new Item("Places", null, null));
        Path home = Path.of(System.getProperty("user.home"));
        model.addElement(new Item("Home", home, FontAwesomeSolid.HOME));
        addIfDirectory(home.resolve("Desktop"), "Desktop", FontAwesomeSolid.DESKTOP);
        addIfDirectory(home.resolve("Documents"), "Documents", FontAwesomeSolid.FILE_ALT);
        addIfDirectory(home.resolve("Downloads"), "Downloads", FontAwesomeSolid.DOWNLOAD);

        List<Item> volumes = volumes();
        if (!volumes.isEmpty()) {
            model.addElement(new Item("Volumes", null, null));
            volumes.forEach(model::addElement);
        }

        List<String> recents = FileDialogHistory.recentPaths();
        if (!recents.isEmpty()) {
            model.addElement(new Item("Recent", null, null));
            for (String p : recents) {
                Path path = Path.of(p);
                model.addElement(new Item(labelFor(path), path, FontAwesomeSolid.HISTORY));
            }
        }
    }

    // --- Sections ---

    private void addIfDirectory(Path path, String label, Ikon glyph) {
        if (Files.isDirectory(path)) {
            model.addElement(new Item(label, path, glyph));
        }
    }

    /**
     * Mounted volumes: /Volumes children on macOS, drive roots on Windows. Display names are
     * derived from paths only — no {@code FileSystemView.getSystemDisplayName} shell calls,
     * which are a per-item performance trap on network drives.
     */
    private static List<Item> volumes() {
        List<Item> items = new ArrayList<>();
        File macVolumes = new File("/Volumes");
        if (macVolumes.isDirectory()) {
            File[] mounted = macVolumes.listFiles(File::isDirectory);
            if (mounted != null) {
                for (File volume : mounted) {
                    items.add(new Item(volume.getName(), volume.toPath(), FontAwesomeSolid.HDD));
                }
            }
        } else {
            for (File root : File.listRoots()) {
                items.add(new Item(root.getPath(), root.toPath(), FontAwesomeSolid.HDD));
            }
        }
        return items;
    }

    private static String labelFor(Path path) {
        Path name = path.getFileName();
        return name != null ? name.toString() : path.toString();
    }

    // --- Pin management ---

    static boolean isPinned(Path dir) {
        return dir != null
            && PreferenceKeys.FILE_DIALOG_PINNED.get().contains(dir.toString());
    }

    static void togglePin(Path dir) {
        if (dir == null) {
            return;
        }
        List<String> pins = new ArrayList<>(PreferenceKeys.FILE_DIALOG_PINNED.get());
        if (!pins.remove(dir.toString())) {
            pins.add(dir.toString());
        }
        PreferenceKeys.FILE_DIALOG_PINNED.set(pins);
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = list.locationToIndex(e.getPoint());
        if (index < 0) {
            return;
        }
        Item item = model.get(index);
        if (item.isHeader() || item.glyph() != FontAwesomeSolid.THUMBTACK) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem unpin = new JMenuItem("Unpin");
        unpin.addActionListener(ae -> {
            togglePin(item.path());
            rebuild();
        });
        menu.add(unpin);
        menu.show(list, e.getX(), e.getY());
    }

    // --- Rendering ---

    private static final class SidebarRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> jList, Object value, int index,
                                                      boolean selected, boolean focused) {
            Item item = (Item) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(
                jList, item.label(), index, !item.isHeader() && selected, false);
            if (item.isHeader()) {
                Font base = label.getFont();
                label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() - 2f));
                label.setForeground(UIManager.getColor("Kalix.tree.mutedForeground"));
                label.setIcon(null);
                label.setBorder(BorderFactory.createEmptyBorder(index == 0 ? 8 : 14, 10, 2, 8));
            } else {
                label.setIcon(FontIcon.of(item.glyph(), 13,
                    ThemeUtils.iconColor(UIManager.getColor("List.background"))));
                label.setBorder(BorderFactory.createEmptyBorder(3, 18, 3, 8));
            }
            return label;
        }
    }
}
