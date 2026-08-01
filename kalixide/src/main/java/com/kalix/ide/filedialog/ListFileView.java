package com.kalix.ide.filedialog;

import com.kalix.ide.io.FileVisuals;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The dialogs' default view: one directory as a Name / Size / Modified table, populated
 * incrementally by the background lister and rendered in the Kalix file visual language
 * ({@link FileVisuals}). Double-click or Enter descends into folders and activates files;
 * right-click offers rename/delete via the host.
 *
 * <p>Columns sort on header click (with the usual direction toggle). The default is the
 * tree's deliberate Name order; Size and Modified sort by the real numeric values captured
 * at enumeration time — directories always group first. Rendering never touches the
 * filesystem: every cell is drawn from the {@link FsEntry} attributes.
 */
final class ListFileView {

    /** Directories group before files under every sort column. */
    private static final Comparator<FsEntry> DIRS_FIRST =
        (a, b) -> a.directory() == b.directory() ? 0 : (a.directory() ? -1 : 1);

    private final FileViewHost host;
    private final EntryTableModel model = new EntryTableModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);

    /** Unfiltered listing of the current directory; the model holds the filtered projection. */
    private final List<FsEntry> allEntries = new ArrayList<>();
    private Path directory;
    private DirectoryLister.Handle handle;
    private boolean programmaticSelection;
    /** One-shot: select this name on the next refilter (pasted-path selection). */
    private String pendingSelectName;

    ListFileView(FileViewHost host) {
        this.host = host;

        table.setSelectionMode(host.allowsMultiSelect()
            ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            : ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(340);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.setDefaultRenderer(Object.class, new EntryCellRenderer());

        TableRowSorter<EntryTableModel> sorter = new TableRowSorter<>(model);
        sorter.setComparator(0, FsEntry.ENTRY_ORDER);
        sorter.setComparator(1, DIRS_FIRST
            .thenComparing(Comparator.comparingLong(FsEntry::size))
            .thenComparing(FsEntry.ENTRY_ORDER));
        sorter.setComparator(2, DIRS_FIRST
            .thenComparing(Comparator.comparingLong(FsEntry::lastModified))
            .thenComparing(FsEntry.ENTRY_ORDER));
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);

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

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
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
        // The whole selection is preserved, not just the lead: listings arrive in batches,
        // so a user selecting during enumeration must not lose earlier picks.
        List<FsEntry> selected = selectedEntries();
        List<FsEntry> visible = new ArrayList<>();
        for (FsEntry entry : allEntries) {
            if (!host.showHidden() && entry.hidden()) {
                continue;
            }
            if (host.passesFilter(entry)) {
                visible.add(entry);
            }
        }
        FsEntry toNotify = null;
        programmaticSelection = true;
        try {
            model.setEntries(visible);
            if (pendingSelectName != null) {
                for (int modelRow = 0; modelRow < visible.size(); modelRow++) {
                    if (visible.get(modelRow).name().equals(pendingSelectName)) {
                        int viewRow = table.convertRowIndexToView(modelRow);
                        table.setRowSelectionInterval(viewRow, viewRow);
                        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                        toNotify = visible.get(modelRow);
                        break;
                    }
                }
                // One-shot: consumed even when not found (the name may not exist here).
                pendingSelectName = null;
            } else {
                for (FsEntry entry : selected) {
                    int modelRow = visible.indexOf(entry);
                    if (modelRow >= 0) {
                        int viewRow = table.convertRowIndexToView(modelRow);
                        table.addRowSelectionInterval(viewRow, viewRow);
                    }
                }
            }
        } finally {
            programmaticSelection = false;
        }
        if (toNotify != null) {
            host.selectionChanged(selectableEntry(toNotify));
        }
    }

    /** Selects the entry with this name (now, or when its listing batch arrives). */
    void selectName(String name) {
        pendingSelectName = name;
        refilter();
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
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        return modelRow < model.entries.size() ? model.entries.get(modelRow) : null;
    }

    /** Every selected entry, in view order (one at most unless the host is multi-select). */
    List<FsEntry> selectedEntries() {
        List<FsEntry> selected = new ArrayList<>();
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow < model.entries.size()) {
                selected.add(model.entries.get(modelRow));
            }
        }
        return selected;
    }

    void focusView() {
        table.requestFocusInWindow();
        if (table.getSelectedRow() < 0 && table.getRowCount() > 0) {
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

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) {
            // Empty space acts on the folder being viewed (context-menu-style §4).
            if (directory != null) {
                host.showContainerContextMenu(directory, table, e.getX(), e.getY());
            }
            return;
        }
        // Right-click selects, as in the project tree — but never collapses an existing
        // multi-selection the user built up just to act on one of its members.
        if (!table.isRowSelected(viewRow)) {
            table.setRowSelectionInterval(viewRow, viewRow);
        }
        // The subject is the row under the pointer, not the selection lead — with several
        // rows selected those differ.
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < model.entries.size()) {
            host.showEntryContextMenu(model.entries.get(modelRow), table, e.getX(), e.getY());
        }
    }

    /** In folder mode files render greyed and never count as the selection. */
    private FsEntry selectableEntry(FsEntry entry) {
        if (entry != null && !entry.directory() && host.directoriesOnly()) {
            return null;
        }
        return entry;
    }

    /** Every column's value IS the entry, so the sorter compares real sizes/dates, not text. */
    private static final class EntryTableModel extends AbstractTableModel {
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
            return entries.get(row);
        }
    }

    private final class EntryCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                table, value, selected, false, row, column);
            FsEntry entry = (FsEntry) value;
            label.setText(switch (column) {
                case 0 -> entry.name();
                case 1 -> EntryFormats.size(entry);
                default -> EntryFormats.modified(entry);
            });
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
