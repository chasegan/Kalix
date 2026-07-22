package com.kalix.ide.filedialog;

import com.kalix.ide.io.FileVisuals;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The dialogs' default view: one directory as a Name / Size / Modified table, populated
 * incrementally by the background lister and rendered in the Kalix file visual language
 * ({@link FileVisuals}). Double-click or Enter descends into folders and activates files.
 *
 * <p>Rendering never touches the filesystem: every cell is drawn from the {@link FsEntry}
 * attributes captured at enumeration time.
 */
final class ListFileView {

    private final FileViewHost host;
    private final EntryTableModel model = new EntryTableModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);

    /** Unfiltered listing of the current directory; the model holds the filtered projection. */
    private final List<FsEntry> allEntries = new ArrayList<>();
    private Path directory;
    private DirectoryLister.Handle handle;
    private boolean programmaticSelection;

    ListFileView(FileViewHost host) {
        this.host = host;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(340);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.setDefaultRenderer(Object.class, new EntryCellRenderer());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || programmaticSelection) {
                return;
            }
            host.selectionChanged(selectableEntry(selectedEntry()));
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.rowAtPoint(e.getPoint()) >= 0) {
                    activate(selectedEntry());
                }
            }
        });
        // Enter activates; JTable's default Enter action (move to next row) is overridden.
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    activate(selectedEntry());
                    e.consume();
                }
            }
        });
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "none");
    }

    JComponent component() {
        return scroll;
    }

    /** Navigates the view to {@code dir}, cancelling any in-flight listing. */
    void navigateTo(Path dir) {
        directory = dir;
        if (handle != null) {
            handle.cancel();
        }
        allEntries.clear();
        model.setEntries(List.of());
        host.selectionChanged(null);
        handle = host.lister().list(dir,
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

    /** Re-applies the hidden/extension filters to the cached listing. */
    void refilter() {
        FsEntry selected = selectedEntry();
        List<FsEntry> visible = new ArrayList<>();
        for (FsEntry entry : allEntries) {
            if (!host.showHidden() && entry.hidden()) {
                continue;
            }
            if (host.passesFilter(entry)) {
                visible.add(entry);
            }
        }
        programmaticSelection = true;
        try {
            model.setEntries(visible);
            if (selected != null) {
                int row = visible.indexOf(selected);
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                }
            }
        } finally {
            programmaticSelection = false;
        }
    }

    /** Re-lists the current directory. */
    void refresh() {
        if (directory != null) {
            navigateTo(directory);
        }
    }

    Path directory() {
        return directory;
    }

    FsEntry selectedEntry() {
        int row = table.getSelectedRow();
        return row >= 0 && row < model.entries.size() ? model.entries.get(row) : null;
    }

    void focusView() {
        table.requestFocusInWindow();
        if (table.getSelectedRow() < 0 && model.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private void activate(FsEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.directory()) {
            navigateTo(entry.path());
            host.directoryShown(entry.path());
        } else if (!host.directoriesOnly()) {
            host.entryActivated(entry);
        }
    }

    /** In folder mode files render greyed and never count as the selection. */
    private FsEntry selectableEntry(FsEntry entry) {
        if (entry != null && !entry.directory() && host.directoriesOnly()) {
            return null;
        }
        return entry;
    }

    private final class EntryTableModel extends AbstractTableModel {
        private List<FsEntry> entries = List.of();

        void setEntries(List<FsEntry> entries) {
            this.entries = entries;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Name";
                case 1 -> "Size";
                default -> "Modified";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            FsEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> EntryFormats.size(entry);
                default -> EntryFormats.modified(entry);
            };
        }
    }

    private final class EntryCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, selected, false, row, column);
            FsEntry entry = model.entries.get(row);
            // All folders render full-strength here: in a dialog they are the navigation
            // targets, and the model-folder scan would reintroduce per-row I/O (the exact
            // JFileChooser sin this dialog exists to avoid).
            label.setIcon(column == 0
                ? (entry.directory()
                    ? FileVisuals.folderIcon(false, true)
                    : FileVisuals.fileIcon(entry.name()))
                : null);
            if (!selected) {
                FileVisuals.Tier tier = tierFor(entry, column);
                Color faded = FileVisuals.tierColor(tier);
                label.setForeground(faded != null ? faded : table.getForeground());
            }
            return label;
        }

        private FileVisuals.Tier tierFor(FsEntry entry, int column) {
            if (entry.directory()) {
                return FileVisuals.Tier.FULL; // folders are the destinations here; keep them strong
            }
            if (host.directoriesOnly()) {
                return FileVisuals.Tier.FAINT; // folder mode: files are context, not choices
            }
            // Size/modified columns always sit one step back so names carry the row.
            return column == 0 ? FileVisuals.fileTier(entry.name()) : FileVisuals.Tier.MUTED;
        }
    }
}
