package com.kalix.ide.filedialog;

import com.kalix.ide.io.FileVisuals;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The macOS-style Miller columns view: the path unfolds left to right, one column per
 * directory, with each column populated incrementally by the background lister. Selecting a
 * folder opens the next column; selecting a file truncates the trail after its column.
 * Left/Right arrows walk the trail, Up/Down move within a column, Enter activates.
 *
 * <p>Navigation to an arbitrary path reuses the longest matching prefix of existing columns,
 * so stepping around a subtree never re-lists directories already on screen. Rendering is
 * entirely I/O-free ({@link FileVisuals} + captured {@link FsEntry} attributes), same as the
 * list view.
 */
final class ColumnsFileView {

    private static final int COLUMN_WIDTH = 220;

    private final FileViewHost host;
    private final JPanel trail = new JPanel();
    private final JScrollPane scroll;
    private final List<BrowserColumn> columns = new ArrayList<>();

    ColumnsFileView(FileViewHost host) {
        this.host = host;
        trail.setLayout(new BoxLayout(trail, BoxLayout.X_AXIS));
        trail.setBackground(UIManager.getColor("List.background"));
        // Left-align columns when the trail is narrower than the viewport.
        JPanel aligner = new JPanel(new java.awt.BorderLayout());
        aligner.setBackground(UIManager.getColor("List.background"));
        aligner.add(trail, java.awt.BorderLayout.WEST);
        scroll = new JScrollPane(aligner,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getHorizontalScrollBar().setUnitIncrement(COLUMN_WIDTH / 4);
    }

    JComponent component() {
        return scroll;
    }

    /**
     * Shows the trail for {@code dir}, reusing the longest matching prefix of the existing
     * columns and listing only the genuinely new ones.
     */
    void navigateTo(Path dir) {
        List<Path> chain = pathChain(dir);
        // Longest prefix of existing columns already showing this chain.
        int keep = 0;
        while (keep < columns.size() && keep < chain.size()
                && columns.get(keep).directory.equals(chain.get(keep))) {
            keep++;
        }
        truncate(keep);
        for (int i = keep; i < chain.size(); i++) {
            BrowserColumn column = new BrowserColumn(chain.get(i));
            columns.add(column);
            trail.add(column.panel);
            // Pre-select the child segment so the trail reads as one continuous path.
            if (i + 1 < chain.size()) {
                column.pendingSelection = chain.get(i + 1).getFileName().toString();
            }
        }
        trail.revalidate();
        trail.repaint();
        scrollToEnd();
    }

    /** Re-applies the hidden/extension filters in every column. */
    void refilter() {
        columns.forEach(BrowserColumn::refilter);
    }

    /** Re-lists every column in the trail (cheap: sequential background listings). */
    void refresh() {
        columns.forEach(BrowserColumn::reload);
    }

    /**
     * Re-lists just the column showing {@code dir} (no-op if it isn't on the trail),
     * optionally selecting {@code selectName} once the fresh listing arrives. Used after
     * in-dialog mutations (new folder, rename, delete) — the dialog has no filesystem
     * watcher, so changes it makes are reflected explicitly.
     */
    void reloadColumn(Path dir, String selectName) {
        for (BrowserColumn column : columns) {
            if (column.directory.equals(dir)) {
                column.pendingSelection = selectName;
                column.reload();
                return;
            }
        }
    }

    /** The deepest directory on the trail (the dialog's current directory in this view). */
    Path deepestDirectory() {
        return columns.isEmpty() ? null : columns.get(columns.size() - 1).directory;
    }

    /**
     * Selects the named entry in the deepest column — now if its listing has arrived,
     * otherwise when it does — and reports the selection to the host (pasted-path flow).
     */
    void selectName(String name) {
        if (columns.isEmpty()) {
            return;
        }
        BrowserColumn last = columns.get(columns.size() - 1);
        last.pendingSelection = name;
        last.refilter();
        FsEntry selected = last.list.getSelectedValue();
        if (selected != null && selected.name().equals(name)) {
            host.selectionChanged(
                !selected.directory() && host.directoriesOnly() ? null : selected);
        }
    }

    void focusView() {
        if (!columns.isEmpty()) {
            columns.get(columns.size() - 1).list.requestFocusInWindow();
        }
    }

    // --- Internals ---

    /** Root → ... → dir, e.g. [/, /Users, /Users/x, /Users/x/project]. */
    private static List<Path> pathChain(Path dir) {
        List<Path> chain = new ArrayList<>();
        for (Path p = dir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            chain.add(0, p);
        }
        return chain;
    }

    private void truncate(int keep) {
        while (columns.size() > keep) {
            BrowserColumn removed = columns.remove(columns.size() - 1);
            removed.dispose();
            trail.remove(removed.panel);
        }
    }

    private void scrollToEnd() {
        SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar bar = scroll.getHorizontalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private int indexOf(BrowserColumn column) {
        return columns.indexOf(column);
    }

    /** One directory of the trail: a fixed-width list fed by its own listing. */
    private final class BrowserColumn {

        final Path directory;
        final JList<FsEntry> list = new JList<>();
        final JPanel panel = new JPanel(new java.awt.BorderLayout());
        final javax.swing.DefaultListModel<FsEntry> model = new javax.swing.DefaultListModel<>();

        private final List<FsEntry> allEntries = new ArrayList<>();
        private DirectoryLister.Handle handle;
        private String pendingSelection;
        private boolean programmaticSelection;

        BrowserColumn(Path directory) {
            this.directory = directory;
            list.setModel(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new ColumnCellRenderer());
            list.setFixedCellHeight(22);

            JScrollPane columnScroll = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            columnScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                UIManager.getColor("Component.borderColor") != null
                    ? UIManager.getColor("Component.borderColor") : Color.LIGHT_GRAY));
            // Horizontal wheel/trackpad gestures arrive as shift+wheel and would be swallowed
            // by this column's (vertical-only) scroll pane; hand them to the trail scroller so
            // sideways scrolling works wherever the pointer is.
            columnScroll.addMouseWheelListener(e -> {
                if (e.isShiftDown()) {
                    javax.swing.JScrollBar bar = scroll.getHorizontalScrollBar();
                    bar.setValue(bar.getValue()
                        + (int) Math.round(e.getPreciseWheelRotation() * bar.getUnitIncrement() * 3));
                }
            });
            panel.add(columnScroll, java.awt.BorderLayout.CENTER);
            Dimension size = new Dimension(COLUMN_WIDTH, 10);
            panel.setPreferredSize(null);
            panel.setMinimumSize(size);
            panel.setMaximumSize(new Dimension(COLUMN_WIDTH, Integer.MAX_VALUE));
            panel.setPreferredSize(new Dimension(COLUMN_WIDTH, 10));

            list.addListSelectionListener(e -> {
                if (e.getValueIsAdjusting() || programmaticSelection) {
                    return;
                }
                onSelection(list.getSelectedValue());
            });
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        FsEntry entry = list.getSelectedValue();
                        if (entry != null && !entry.directory() && !host.directoriesOnly()) {
                            host.entryActivated(entry);
                        }
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }

                private void maybeShowPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        return;
                    }
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0 || !list.getCellBounds(index, index).contains(e.getPoint())) {
                        return;
                    }
                    list.setSelectedIndex(index); // right-click selects, as in the project tree
                    host.showEntryContextMenu(model.get(index), list, e.getX(), e.getY());
                }
            });
            list.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ENTER -> {
                            FsEntry entry = list.getSelectedValue();
                            if (entry != null && !entry.directory() && !host.directoriesOnly()) {
                                host.entryActivated(entry);
                            }
                            e.consume();
                        }
                        case KeyEvent.VK_RIGHT -> {
                            int i = indexOf(BrowserColumn.this);
                            if (i >= 0 && i + 1 < columns.size()) {
                                BrowserColumn next = columns.get(i + 1);
                                next.list.requestFocusInWindow();
                                if (next.list.getSelectedIndex() < 0 && next.model.size() > 0) {
                                    next.list.setSelectedIndex(0);
                                }
                            }
                            e.consume();
                        }
                        case KeyEvent.VK_LEFT -> {
                            int i = indexOf(BrowserColumn.this);
                            if (i > 0) {
                                columns.get(i - 1).list.requestFocusInWindow();
                            }
                            e.consume();
                        }
                        default -> { }
                    }
                }
            });

            reload();
        }

        void reload() {
            if (handle != null) {
                handle.cancel();
            }
            allEntries.clear();
            handle = host.lister().list(directory,
                batch -> {
                    allEntries.clear();
                    allEntries.addAll(batch);
                    refilter();
                },
                error -> {
                    if (error != null) {
                        host.listingFailed(error);
                    }
                });
        }

        void refilter() {
            FsEntry selected = list.getSelectedValue();
            programmaticSelection = true;
            try {
                model.clear();
                for (FsEntry entry : allEntries) {
                    if (!host.showHidden() && entry.hidden()) {
                        continue;
                    }
                    if (host.passesFilter(entry)) {
                        model.addElement(entry);
                    }
                }
                if (pendingSelection != null) {
                    for (int i = 0; i < model.size(); i++) {
                        if (model.get(i).name().equals(pendingSelection)) {
                            list.setSelectedIndex(i);
                            list.ensureIndexIsVisible(i);
                            break;
                        }
                    }
                    // One-shot: consumed now, so later refilters (hidden toggle, filter
                    // change) don't yank the selection back to this entry.
                    pendingSelection = null;
                } else if (selected != null) {
                    int i = model.indexOf(selected);
                    if (i >= 0) {
                        list.setSelectedIndex(i);
                    }
                }
            } finally {
                programmaticSelection = false;
            }
        }

        void dispose() {
            if (handle != null) {
                handle.cancel();
            }
        }

        private void onSelection(FsEntry entry) {
            if (entry == null) {
                return;
            }
            int myIndex = indexOf(this);
            if (entry.directory()) {
                truncate(myIndex + 1);
                BrowserColumn next = new BrowserColumn(entry.path());
                columns.add(next);
                trail.add(next.panel);
                trail.revalidate();
                trail.repaint();
                scrollToEnd();
                host.directoryShown(entry.path());
                host.selectionChanged(host.directoriesOnly() ? entry : null);
            } else {
                truncate(myIndex + 1);
                trail.revalidate();
                trail.repaint();
                host.selectionChanged(host.directoriesOnly() ? null : entry);
            }
        }

        private final class ColumnCellRenderer extends DefaultListCellRenderer {
            @Override
            public Component getListCellRendererComponent(JList<?> jList, Object value, int index,
                                                          boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                    jList, value, index, selected, focused);
                FsEntry entry = (FsEntry) value;
                label.setText(entry.name());
                // Folders full-strength (they are the navigation), files per the language;
                // in folder mode files are context only. No per-row I/O, ever.
                if (entry.directory()) {
                    label.setIcon(FileVisuals.folderIcon(false, true));
                } else {
                    label.setIcon(FileVisuals.fileIcon(entry.name()));
                    if (!selected) {
                        FileVisuals.Tier tier = host.directoriesOnly()
                            ? FileVisuals.Tier.FAINT
                            : FileVisuals.fileTier(entry.name());
                        Color faded = FileVisuals.tierColor(tier);
                        if (faded != null) {
                            label.setForeground(faded);
                        }
                    }
                }
                return label;
            }
        }
    }
}
